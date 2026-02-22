package com;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ChatSession {
	private static final int MAX_HISTORY = 60;
	private static final int MAX_QUERY_HISTORY = 16;
	private static final int MAX_ASK_HISTORY = 40;
	private static final int MAX_MESSAGE_LENGTH = 1000;
	private static final double SIMILARITY_THRESHOLD = 0.5;
	// ⚡ 优化 1: 复用 ObjectMapper (单例)
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ChatHistory history = new ChatHistory(MAX_ASK_HISTORY);
	QueryHistory queryHistory = new QueryHistory();
	private String systemMessage = "";
	ChatAnswer ca = new ChatAnswer();
	// 🌟 核心：引入抽象的大模型客户端，而不是写死的 SessionManager 调用
	private final LlmClient llmClient;
	private final String tableName; // 🌟 新增表名属性
	private final EmbeddingClient embeddingClient; // ✅ 新增：专门负责向量化的接口
	String rewrite_prompt = null;
	String ask_prompt = null;

	// 🌟 规范修改：构造函数要求外部把两个能力分别传进来
	public ChatSession(LlmClient llmClient, EmbeddingClient embeddingClient, String tableName) {
        this.llmClient = llmClient;
        this.embeddingClient = embeddingClient;
        this.tableName = tableName; // 🌟 接收表名
		rewrite_prompt = loadPromptFromFile("e:\\eit\\openai\\prompt_rewritequery.txt", "");
		ask_prompt = loadPromptFromFile("e:\\eit\\openai\\prompt_finalask.txt", "");
	}

	void setSystemMessage(String system) {
		// this.systemMessage = system;
		buildSystem(system);
		history.addMessage("system", systemMessage);

	}

	public void buildSystem(String knowledgeContext) {
		if (knowledgeContext == null)
			return;
		systemMessage = ask_prompt + "=== CONTEXT ===\n" + knowledgeContext + "\n" + "=== END CONTEXT ===\n";

	}

	/**
	 * ⚡ 优化版 ask() - 速度提升点： 1. 减少字符串拼接 2. 复用 ObjectMapper 3. 减少不必要的对象创建
	 */
	public void addUserHis(String text) {
		// 添加用户消息
		history.addMessage("user", text);
		history.trim(MAX_HISTORY);
		// trimHistory();
	}

	public ChatAnswer ask(String text) {
		System.out.println("✅ ask AI...");
		// 提前验证和处理输入
		if (text == null || text.isEmpty()) {
			ca.code = -1;
			ca.answer = "客户问题为空";
			return ca;
		}

		// 截断过长消息
		if (text.length() > MAX_MESSAGE_LENGTH) {
			text = text.substring(0, MAX_MESSAGE_LENGTH);
		}

		try {
			// ==========================================
			// 步骤 1: Rewrite - 结合 History(User/Context) 生成 OptimizedQuery
			// ==========================================
			String optimizedQuery = text;

			String historyContextStr = queryHistory.getMessageWindowSize(MAX_QUERY_HISTORY);
			System.out.println("🔄 historyContextStr=" + historyContextStr);
			// 如果有历史记录且读取到了 prompt，则调用大模型重写问题
			if (!historyContextStr.trim().isEmpty() && rewrite_prompt != null && !rewrite_prompt.isEmpty()) {
				System.out.println("🔄 正在重写用户查询...");
				// String rewritten = SessionManager.rewriteQuery(text, historyContextStr,
				// rewrite_prompt);

				// 🌟 改动点：调用接口的 generate 方法，而不是静态的 SessionManager
				String userPrompt = "Conversation History:\n(" + historyContextStr + ")\n\nCurrent Question: (" + text
						+ ")";
				String rewritten = llmClient.generate(rewrite_prompt, userPrompt);

				if (rewritten != null && !rewritten.isEmpty()) {
					optimizedQuery = rewritten;
					System.out.println("✨ 查询已重写为: " + optimizedQuery);
				}
			}

			// ==========================================
			// 步骤 2: Retrieve - 使用 OptimizedQuery 搜索匹配知识库
			// ==========================================
			System.out.println("🔍 使用优化后的问题检索知识库...");
			List<SearchService.KnowledgeItem> items = SearchService.getRelevantKnowledge(tableName,optimizedQuery,
					embeddingClient);
			System.out.println("✅ 找到匹配项: " + items.size());

			if (items.isEmpty()) {
				System.out.println("⚠️ 知识库中没有找到任何内容");
				ca.code = -2;
				ca.answer = "知识库中没有找到任何内容";

				// 🌟 新增：即使没查到，也要把用户的实体和意图记录下来
				queryHistory.addMessage("User", text);
				queryHistory.addMessage("Context", "【未匹配到相关知识】");
				queryHistory.trim(MAX_HISTORY);

				// 🌟 新增：聊天历史也要记录，保证多轮对话的完整性
				history.addMessage("user", text);
				history.addMessage("assistant", "抱歉，知识库中没有找到任何内容。");
				history.trim(MAX_HISTORY);

				return ca;
			}

			double bestDistance = items.get(0).distance;
			System.out.println("📊 最佳匹配距离: " + String.format("%.4f", bestDistance));

			if (bestDistance > SIMILARITY_THRESHOLD) {
				System.out.println("⚠️ 相似度不足 (阈值: " + SIMILARITY_THRESHOLD + ")");
				ca.code = -100;
				ca.answer = "抱歉，我在知识库中未找到与您问题完全相关的信息,您可以尝试: 换一种方式描述您的问题";
				// 🌟 新增：记录低相似度的废弃查询
				queryHistory.addMessage("User", text);
				queryHistory.addMessage("Context", "【未匹配到相关知识】");
				queryHistory.trim(MAX_HISTORY);

				// 🌟 新增：聊天历史也要记录
				history.addMessage("user", text);
				history.addMessage("assistant", "抱歉，我在知识库中未找到与您问题完全相关的信息,您可以尝试: 换一种方式描述您的问题");
				history.trim(MAX_HISTORY);

				return ca;
			}

			// 构建匹配到的知识库上下文
			StringBuilder contextBuilder = new StringBuilder();
			// 分别构建【完整知识】和【摘要知识】
			StringBuilder fullContextBuilder = new StringBuilder();
			StringBuilder summaryContextBuilder = new StringBuilder();

			for (int i = 0; i < items.size(); i++) {
				SearchService.KnowledgeItem item = items.get(i);

				// 拼接给大模型看的完整内容（加上摘要作为标题）
	            fullContextBuilder.append(String.format("%d. 【%s】%s\n", i + 1, item.summary, item.content));
	            
	            // 拼接存入历史记录的精简摘要
	            summaryContextBuilder.append(String.format("%d. %s\n", i + 1, item.summary));
			}
			String matchedFullContext = fullContextBuilder.toString();
			String matchedSummaryContext = summaryContextBuilder.toString();

			System.out.println("📚 检索到的完整知识:\n" + matchedFullContext);
			System.out.println("📝 提取的摘要知识(用于下一轮重写):\n" + matchedSummaryContext);

			// 🌟 核心修改：记录到 QueryHistory 中的是【摘要】而不是完整内容
			queryHistory.addMessage("User", text);
			// 🌟 终极杀招：存入“优化后的标准问句”，让实体（如美国护照）在历史中彻底显性化！
			// queryHistory.addMessage("User", optimizedQuery);
			queryHistory.addMessage("Context", matchedSummaryContext);
			queryHistory.trim(MAX_HISTORY);

			// ==========================================
			// 步骤 3: Construct - 发给 AI 生成最终回答
			// ==========================================
			System.out.println("✅ 正在构建最终 Prompt 发送给 AI...");

			// 🌟 这里必须传完整内容，因为用户需要看详细的知识解答
			setSystemMessage(matchedFullContext);

			// 2. 将当前优化后的问题追加到 History (历史的 User 和 AI 回答已经在里面了)
			history.addMessage("user", optimizedQuery);
			history.trim(MAX_HISTORY);

			// 发送到 OpenAI 生成最终回答
			// String answer = SessionManager.sendToOpenAI(history);
			// 🌟 改动点：调用接口的 chat 方法获取回答
			// Start the timer
			long startTime = System.currentTimeMillis();

			// 🌟 Call the chat method to get the answer
			String answer = llmClient.chat(history.toJsonArray());

			// End the timer and print the duration
			long endTime = System.currentTimeMillis();
			System.out.println("⏱️ AI Generation Time: " + (endTime - startTime) + " ms");

			// 将 AI 回答添加到 ChatHistory 中
			if (answer != null && !answer.isEmpty()) {
				if (answer.length() > MAX_MESSAGE_LENGTH) {
					answer = answer.substring(0, MAX_MESSAGE_LENGTH);
				}
				history.addMessage("assistant", answer);
				history.trim(MAX_HISTORY);
			}

			ca.code = 0;
			ca.answer = answer;
			return ca;

		} catch (Exception e) {
			e.printStackTrace();
			ca.code = -1;
			ca.answer = "机器人系统故障";
			return ca;
		}
	}

	/*
	 * public ChatAnswer ask2(String text) { System.out.println("✅ ask AI..."); //
	 * 提前验证和处理输入 if (text == null || text.isEmpty()) { ca.code = -1; ca.answer =
	 * "客户问题为空"; return ca; }
	 * 
	 * // 截断过长消息 if (text.length() > MAX_MESSAGE_LENGTH) { text = text.substring(0,
	 * MAX_MESSAGE_LENGTH); }
	 * 
	 * try { // ========================================== // 步骤 1: Rewrite - 结合
	 * History(User/Context) 生成 OptimizedQuery //
	 * ========================================== String optimizedQuery = text;
	 * 
	 * // 构建只包含历史问题和知识库的字符串上下文 StringBuilder queryHistoryBuilder = new
	 * StringBuilder(); for (QueryHistory.Message msg : queryHistory.getMessages())
	 * {
	 * queryHistoryBuilder.append(msg.getRole()).append(": ").append(msg.getContent(
	 * )).append("\n"); } String historyContextStr = queryHistoryBuilder.toString();
	 * 
	 * 
	 * String historyContextStr =
	 * queryHistory.getMessageWindowSize(MAX_QUERY_HISTORY);
	 * System.out.println("🔄 historyContextStr="+historyContextStr); //
	 * 如果有历史记录且读取到了 prompt，则调用大模型重写问题 if (!historyContextStr.trim().isEmpty() &&
	 * rewrite_prompt != null && !rewrite_prompt.isEmpty()) {
	 * System.out.println("🔄 正在重写用户查询..."); String rewritten =
	 * SessionManager.rewriteQuery(text, historyContextStr, rewrite_prompt); if
	 * (rewritten != null && !rewritten.isEmpty()) { optimizedQuery = rewritten;
	 * System.out.println("✨ 查询已重写为: " + optimizedQuery); } }
	 * 
	 * // ========================================== // 步骤 2: Retrieve - 使用
	 * OptimizedQuery 搜索匹配知识库 // ==========================================
	 * System.out.println("🔍 使用优化后的问题检索知识库..."); List<SearchService.KnowledgeItem>
	 * items = SearchService.getRelevantKnowledge(optimizedQuery);
	 * System.out.println("✅ 找到匹配项: " + items.size());
	 * 
	 * if (items.isEmpty()) { System.out.println("⚠️ 知识库中没有找到任何内容"); ca.code = -2;
	 * ca.answer = "知识库中没有找到任何内容";
	 * 
	 * 
	 * // 🌟 新增：即使没查到，也要把用户的实体和意图记录下来 queryHistory.addMessage("User", text);
	 * queryHistory.addMessage("Context", "【未匹配到相关知识】");
	 * queryHistory.trim(MAX_HISTORY);
	 * 
	 * // 🌟 新增：聊天历史也要记录，保证多轮对话的完整性 history.addMessage("user", text);
	 * history.addMessage("assistant", "抱歉，知识库中没有找到任何内容。");
	 * history.trim(MAX_HISTORY);
	 * 
	 * return ca; }
	 * 
	 * double bestDistance = items.get(0).distance; System.out.println("📊 最佳匹配距离: "
	 * + String.format("%.4f", bestDistance));
	 * 
	 * if (bestDistance > SIMILARITY_THRESHOLD) {
	 * System.out.println("⚠️ 相似度不足 (阈值: " + SIMILARITY_THRESHOLD + ")"); ca.code =
	 * -100; ca.answer = "抱歉，我在知识库中未找到与您问题完全相关的信息,您可以尝试: 换一种方式描述您的问题"; // 🌟
	 * 新增：记录低相似度的废弃查询 queryHistory.addMessage("User", text);
	 * queryHistory.addMessage("Context", "【未匹配到相关知识】");
	 * queryHistory.trim(MAX_HISTORY);
	 * 
	 * // 🌟 新增：聊天历史也要记录 history.addMessage("user", text);
	 * history.addMessage("assistant",
	 * "抱歉，我在知识库中未找到与您问题完全相关的信息,您可以尝试: 换一种方式描述您的问题"); history.trim(MAX_HISTORY);
	 * 
	 * return ca; }
	 * 
	 * // 构建匹配到的知识库上下文 StringBuilder contextBuilder = new StringBuilder(); //
	 * 分别构建【完整知识】和【摘要知识】 StringBuilder fullContextBuilder = new StringBuilder();
	 * StringBuilder summaryContextBuilder = new StringBuilder();
	 * 
	 * for (int i = 0; i < items.size(); i++) { SearchService.KnowledgeItem item =
	 * items.get(i);
	 * 
	 * // 1. 完整知识 (发给大模型做最终回答用) fullContextBuilder.append(String.format("%d. %s\n",
	 * i + 1, item.content));
	 * 
	 * // 2. 摘要知识 (专门提取【】里的内容) String summary = extractSummary(item.content);
	 * summaryContextBuilder.append(String.format("%d. %s\n", i + 1, summary)); }
	 * String matchedFullContext = fullContextBuilder.toString(); String
	 * matchedSummaryContext = summaryContextBuilder.toString();
	 * 
	 * System.out.println("📚 检索到的完整知识:\n" + matchedFullContext);
	 * System.out.println("📝 提取的摘要知识(用于下一轮重写):\n" + matchedSummaryContext);
	 * 
	 * // 🌟 核心修改：记录到 QueryHistory 中的是【摘要】而不是完整内容 queryHistory.addMessage("User",
	 * text); // 🌟 终极杀招：存入“优化后的标准问句”，让实体（如美国护照）在历史中彻底显性化！ //
	 * queryHistory.addMessage("User", optimizedQuery);
	 * queryHistory.addMessage("Context", matchedSummaryContext);
	 * queryHistory.trim(MAX_HISTORY);
	 * 
	 * // ========================================== // 步骤 3: Construct - 发给 AI
	 * 生成最终回答 // ==========================================
	 * System.out.println("✅ 正在构建最终 Prompt 发送给 AI...");
	 * 
	 * // 🌟 这里必须传完整内容，因为用户需要看详细的知识解答 setSystemMessage(matchedFullContext);
	 * 
	 * 
	 * 
	 * // 2. 将当前优化后的问题追加到 History (历史的 User 和 AI 回答已经在里面了)
	 * history.addMessage("user", optimizedQuery); history.trim(MAX_HISTORY);
	 * 
	 * // 发送到 OpenAI 生成最终回答 String answer = SessionManager.sendToOpenAI(history);
	 * 
	 * // 将 AI 回答添加到 ChatHistory 中 if (answer != null && !answer.isEmpty()) { if
	 * (answer.length() > MAX_MESSAGE_LENGTH) { answer = answer.substring(0,
	 * MAX_MESSAGE_LENGTH); } history.addMessage("assistant", answer);
	 * history.trim(MAX_HISTORY); }
	 * 
	 * ca.code = 0; ca.answer = answer; return ca;
	 * 
	 * } catch (Exception e) { e.printStackTrace(); ca.code = -1; ca.answer =
	 * "机器人系统故障"; return ca; } }
	 * 
	 * 
	 * 
	 * public ChatAnswer ask3(String text) { System.out.println("✅ ask AI..."); //
	 * 提前验证和处理输入 if (text == null || text.isEmpty()) { ca.code = -1; ca.answer =
	 * "客户问题为空"; return ca; // return "Invalid input"; }
	 * 
	 * // 截断过长消息 if (text.length() > MAX_MESSAGE_LENGTH) { text = text.substring(0,
	 * MAX_MESSAGE_LENGTH); }
	 * 
	 * // 添加用户消息 addUserHis(text); try { List<SearchService.KnowledgeItem> items =
	 * SearchService.getRelevantKnowledge(text); System.out.println("✅ items ." +
	 * items.size()); if (items.isEmpty()) { System.out.println("⚠️ 知识库中没有找到任何内容");
	 * // handleNoKnowledge(clientId, userQuery); // session.addHis(userQuery);
	 * 
	 * ca.code = -2; ca.answer = "知识库中没有找到任何内容"; return ca; }
	 * 
	 * double bestDistance = items.get(0).distance; System.out.println("📊 最佳匹配距离: "
	 * + String.format("%.4f", bestDistance));
	 * 
	 * if (bestDistance > SIMILARITY_THRESHOLD) {
	 * System.out.println("⚠️ 相似度不足 (阈值: " + SIMILARITY_THRESHOLD + ")");
	 * 
	 * System.out.println("🔄 检测到低相似度，但仍尝试使用最接近的知识\n");
	 * 
	 * // 显示找到的知识 System.out.println("📚 找到的最接近内容:"); for (int i = 0; i <
	 * Math.min(3, items.size()); i++) { System.out.println((i + 1) + ". " +
	 * items.get(i).content + " (距离: " + String.format("%.4f",
	 * items.get(i).distance) + ")"); } System.out.println();
	 * 
	 * // 选项1: 直接返回预设回答
	 * 
	 * System.out.println("💬 系统回答:");
	 * System.out.println("抱歉，我在知识库中未找到与您问题完全相关的信息。"); System.out.println("您可以尝试:");
	 * System.out.println("1. 换一种方式描述您的问题"); System.out.println("2. 联系人工客服获取帮助");
	 * 
	 * 
	 * ca.code = -100; ca.answer = "抱歉，我在知识库中未找到与您问题完全相关的信息,您可以尝试: 换一种方式描述您的问题";
	 * 
	 * return ca; }
	 * 
	 * System.out.println("✅ 找到相关知识，正在构建上下文...\n");
	 * 
	 * StringBuilder context = new StringBuilder(); for (int i = 0; i <
	 * items.size(); i++) { SearchService.KnowledgeItem item = items.get(i);
	 * context.append(String.format("%d. %s (相似度: %.4f)\n", i + 1, item.content,
	 * item.distance)); }
	 * 
	 * System.out.println("📚 检索到的知识:"); System.out.println(context.toString());
	 * 
	 * // buildSystem( context.toString()); setSystemMessage(context.toString()); //
	 * 发送到 OpenAI String answer = SessionManager.sendToOpenAI(history);
	 * 
	 * // 添加助手回复 if (answer != null && !answer.isEmpty()) { if (answer.length() >
	 * MAX_MESSAGE_LENGTH) { answer = answer.substring(0, MAX_MESSAGE_LENGTH); }
	 * 
	 * history.addMessage("assistant", answer); history.trim(MAX_HISTORY); }
	 * 
	 * ca.code = 0; ca.answer = answer; return ca;
	 * 
	 * } catch (Exception e) { e.printStackTrace(); ca.code = -1; ca.answer =
	 * "机器人系统故障"; return ca; // return "System error"; } }
	 */

	/**
	 * ⚡ 优化版 sendToOpenAI()
	 */
	/*
	 * private String sendToOpenAI() throws Exception { // 创建请求 JSON ObjectNode
	 * rootNode = MAPPER.createObjectNode(); rootNode.put("model", "gpt-4o-mini");
	 * rootNode.put("max_tokens", 300);
	 * 
	 * 
	 * 
	 * // 关键代码 rootNode.set("messages", history.toJsonArray());
	 * 
	 * // 生成 JSON String bodyJson = MAPPER.writeValueAsString(rootNode);
	 * 
	 * // 创建 input 数组 ArrayNode messagesArray = rootNode.putArray("messages");
	 * 
	 * // ✅ 直接遍历 history，格式更简单 for (Message msg : history) { ObjectNode messageNode
	 * = messagesArray.addObject(); messageNode.put("role", msg.role);
	 * messageNode.put("content", msg.content); // ✅ 直接是字符串，不需要 content 数组 }
	 * 
	 * 
	 * System.out.println("bodyJson:"+bodyJson); // 调用优化版 SessionManagerOptimized
	 * return SessionManager.sendToOpenAI(bodyJson); }
	 */
	private static String loadPromptFromFile(String filePath, String defauts) {
		try {
			// 1. 读取原始文件内容
			String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)),
					java.nio.charset.StandardCharsets.UTF_8);

			// 2. 剔除 /* ... */ 多行块注释
			// (?s) 开启 DOTALL 模式，让小数点(.)可以匹配到换行符
			content = content.replaceAll("(?s)/\\*.*?\\*/", "");

			// 3. 剔除 // 单行注释
			content = content.replaceAll("//.*", "");

			// 4. (可选) 清理因为删除注释而产生的多余空行，保持发给 AI 的文本紧凑
			content = content.replaceAll("(?m)^\\s*\\n", "");

			return content.trim();
		} catch (Exception e) {
			System.err.println("⚠️ 警告：无法从 " + filePath + " 读取配置，将使用默认 Prompt。原因: " + e.getMessage());
			return defauts;
		}
	}

	/**
	 * 提取文本中所有【】内的内容作为摘要。 如果没有找到【】，则默认截取前 30 个字符作为保底策略。
	 */
	private static String extractSummary(String content) {
		if (content == null || content.isEmpty()) {
			return "";
		}

		java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("【([^】]+)】");
		java.util.regex.Matcher matcher = pattern.matcher(content);
		StringBuilder summary = new StringBuilder();

		while (matcher.find()) {
			// 提取括号内的内容，用空格隔开
			summary.append(matcher.group(1)).append(" ");
		}

		String result = summary.toString().trim();

		// 兜底策略：如果这段知识库没有配【】标签，截取前30个字避免丢失上下文
		if (result.isEmpty()) {
			return content.length() > 30 ? content.substring(0, 30) + "..." : content;
		}

		return result;
	}

}
