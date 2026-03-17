package com.lcallai;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory ;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Comparator; // 排序也需要这个


/*
 * 1. ask() 函数：智能 Agent (Function Calling) 模式
此函数将 AI 视为一个具备决策能力的 智能体。它不直接检索数据库，而是先询问 AI 是否需要外部工具的支持。

核心逻辑：

意图判定：首先调用大模型，并在请求中注入工具（Tools）定义。

参数提取：AI 如果认为需要查库，会返回 TOOL_CALL: 信号，并精准提取结构化参数（如目的地 country 和国籍 citizenship）。

多维精准检索：Java 拦截信号后，利用提取的参数在向量数据库中进行多次精准匹配。

去重与排序：使用 LinkedHashSet 过滤重复知识条目，并按相似度距离（Distance）从小到大排序，确保最相关的知识排在最前面。

二次总结：将检索到的原始知识反馈给 AI，由 AI 进行逻辑推理并生成最终的人性化回答。

适用场景：适用于需要精准参数提取的业务咨询（如：签证政策查询、特定产品参数比对）。

2. ask2() 函数：传统 RAG (语义搜索) 模式
此函数采用标准的 检索增强生成（RAG） 流程，是一个线性的、预设好的处理链路。

核心逻辑：

查询重写：结合历史对话背景，将用户的模糊提问重写为语义丰富的 optimizedQuery。

盲目检索（Retrieve）：不论问题性质，直接将重写后的整句长文本转化为向量，在数据库中进行模糊语义搜索。

相似度过滤：设置了严格的相似度阈值（SIMILARITY_THRESHOLD），如果匹配度不足则直接报错。

直接生成：将检索到的上下文通过 setSystemMessage 注入，由 AI 根据这些参考资料生成回答。

适用场景：适用于开放式问答、知识库百科或不需要精确参数提取的泛闲聊场景。
 */
public class ChatSession {
	private static final int MAX_HISTORY = 60;
	private static final int MAX_QUERY_HISTORY = 16;
	private static final int MAX_ASK_HISTORY = 40;
	private static final int MAX_MESSAGE_LENGTH = 1000;
	//private static final double SIMILARITY_THRESHOLD = 0.82;
    //double TRUST_THRESHOLD = 0.25; // 根据你的日志，0.25 是一个安全的黄金分割点
    // ==========================================
    // 🎛️ RAG 核心路由与拦截阈值配置
    // ==========================================

    // 1. 拒答防线 (Final Cutoff): 最终精排距离大于此值，直接回复“知识库未找到”
    private double similarityThreshold = 0.82;

    // 2. 信任快道 (Fast Track): 粗排距离小于此值，视为“上帝视角”绝对命中，跳过精排
    private double trustThreshold = 0.25;

    // 3. 补偿机制-粗排上限 (Compensate Embed): 粗排距离必须小于此值，才有资格被抢救
    private double compensateEmbedMax = 0.45;

    // 4. 补偿机制-精排下限 (Compensate Rerank): 精排距离大于此值（被误杀），触发抢救
    private double compensateRerankMin = 0.80;




    // 🌟 新增：进入精排池的最大允许距离
    private double rerankTriggerMax = 0.60;
    // 🌟 新增：触发补偿后强行赋予的得分
    private double rescueScore = 0.60;


    private int maxRerankCandidates = 5;       // 粗排后拿几条去精排
    private int finalContextLimit = 3;         // 最后给 AI 几条知识
    private int rerankTimeoutSeconds = 5;

	// ⚡ 优化 1: 复用 ObjectMapper (单例)
	private static final ObjectMapper MAPPER = new ObjectMapper();
    // 模型路由配置


	private ChatHistory history = new ChatHistory(MAX_ASK_HISTORY);
	QueryHistory queryHistory = new QueryHistory();
	private String systemMessage = "";
	ChatAnswer ca = new ChatAnswer(-1,null);
	// 🌟 核心：引入抽象的大模型客户端，而不是写死的 SessionManager 调用
	//rivate final LlmClient llmClient;
    private final ModelRouter router;
	private final String tableName; // 🌟 新增表名属性
	private final EmbeddingClient embeddingClient; // ✅ 新增：专门负责向量化的接口
	String rewrite_prompt = null;
	String ask_prompt = null;
    public String rerankSys_prompt=null;
    String fulltext=null;
    String queryMode="fullText";
    // 1. 在类成员变量处增加线程池定义
    // 建议替换 ChatSession.java 中的定义
// 替换 ChatSession.java 中的线程池初始化代码
    private static final ThreadPoolExecutor rerankExecutor = new ThreadPoolExecutor(
            8,
            16,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(1);
                public Thread newThread(Runnable r) {
                    return new Thread(r, "rerank-pool-" + count.getAndIncrement());
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    // 🌟 构造函数只接收核心能力组件
    public ChatSession(ModelRouter router, EmbeddingClient embeddingClient, String tableName) {
        this.router = router;
        this.embeddingClient = embeddingClient;
        this.tableName = tableName;
    }
    // 🌟 统一的配置注入方法
    public void setThresholds(double similarity, double trust, double compEmbedMax, double compRerankMin) {
        this.similarityThreshold = similarity;
        this.trustThreshold = trust;
        this.compensateEmbedMax = compEmbedMax;
        this.compensateRerankMin = compRerankMin;

    }
    // 🌟 新增：高级阈值注入
    public void setAdvancedThresholds(double triggerMax, double rescueScore) {
        this.rerankTriggerMax = triggerMax;
        this.rescueScore = rescueScore;
    }

    public void setTopK(int rerankCandidates, int contextLimit, int timeout) {
        this.maxRerankCandidates = rerankCandidates;
        this.finalContextLimit = contextLimit;
        this.rerankTimeoutSeconds = timeout;
    }
    // 🌟 提供相应的 Setter 方法给 SessionManager 调用
    public void setRewrite_prompt(String rewrite_prompt) { this.rewrite_prompt = rewrite_prompt; }
    public void setAsk_prompt(String ask_prompt) { this.ask_prompt = ask_prompt; }
    public void setRerankSys_prompt(String rerankSys_prompt) { this.rerankSys_prompt = rerankSys_prompt; }
    public void setFulltext(String fulltext) { this.fulltext = fulltext; }
    //public void setUseRerank(boolean useRerank) { this.useRerank = useRerank; }
    public void setQueryMode(String queryMode) { this.queryMode = queryMode; }
/*
	// 🌟 规范修改：构造函数要求外部把两个能力分别传进来
    public ChatSession(ModelRouter router, EmbeddingClient embeddingClient, String tableName) {
        this.router = router;
        this.embeddingClient = embeddingClient;
        this.tableName = tableName; // 🌟 接收表名
		rewrite_prompt = loadPromptFromFile("e:\\eit\\openai\\prompt_rewritequery_v1_publish.txt", "");
		ask_prompt = loadPromptFromFile("e:\\eit\\openai\\prompt_finalask_v1_publish.txt", "");
       // String rerankSys = "你是一个精确的文档评测专家。请判断提供的【内容】是否包含回答【问题】所需的关键信息。相关请打 0.8-1.0 分，完全不相关打 0.0-0.2 分。只输出数字。";
        rerankSys_prompt = loadPromptFromFile("e:\\eit\\openai\\prompt_rerank_v1_publish.txt", "");
        fulltext=loadKnowledgeBase("c:\\knowledge.txt");
        useRerank=false;

    }
    public void setUseRerank(boolean useRerank) {
        this.useRerank = useRerank;
    }
    */

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
	public ChatAnswer askOLD(String text) {
	    System.out.println("✅ Agent-mode ask AI (Function Calling)...");
	    if (text == null || text.isEmpty()) {
	        ca.code = -1; ca.answer = "客户问题为空"; return ca;
	    }
	    if (text.length() > MAX_MESSAGE_LENGTH) text = text.substring(0, MAX_MESSAGE_LENGTH);

	    try {
	        // --- 步骤 1: Rewrite (重写用户意图) ---
	        String optimizedQuery = text;
	        String historyContextStr = queryHistory.getMessageWindowSize(MAX_QUERY_HISTORY);
	        if (!historyContextStr.trim().isEmpty() && rewrite_prompt != null) {
	            System.out.println("🔄 正在重写用户查询...");
	            String userPrompt = "Conversation History:\n(" + historyContextStr + ")\n\nCurrent Question: (" + text + ")";
                String rewritten = router.rewriter().generate(rewrite_prompt, userPrompt);
	            if (rewritten != null && !rewritten.isEmpty()) {
	            	optimizedQuery = rewritten;
	            }
	            System.out.println("🔄 正在重写结果:"+optimizedQuery);
	        }

	        // --- 步骤 2: 意图判定 (第一次调用 AI) ---
	        // 此时 history 中存入优化后的问题
	        history.addMessage("user", optimizedQuery);
	        
	        System.out.println("✅ 发送意图判定请求 (带 Tools 定义)...");
	        // llmClient.chat 内部会注入 tools 定义
            String firstResponse = router.finalLlm().chat(history.toJsonArray());
	        if (firstResponse.startsWith("TOOL_CALL:")) {
	            String jsonStr = firstResponse.substring(10);
	            JsonNode toolCallNode = MAPPER.readTree(jsonStr);
	            String callId = toolCallNode.path("id").asText(); // 获取 call_id
	            
	            Map<String, String> args = parseArgsFromAiResponse(firstResponse);
	            String country = args.get("country");
	            String citizenship = args.get("citizenship");
	            if(!country.isEmpty() ){
	            	
	            	// 🌟 核心改进：执行多维度检索
	            	// 🌟 改进 2：使用 LinkedHashSet 保证条目唯一性且保留插入顺序
	                Set<SearchService.KnowledgeItem> uniqueItems = new LinkedHashSet<>();
	                
	                // 1. 查目的地的通用政策（如 144小时免签）
	                uniqueItems.addAll(SearchService.getRelevantKnowledge(tableName, country, embeddingClient));
	                
	                // 2. 如果有国籍，查针对该国籍的特殊政策（如 15天单方面免签）
	                if (!citizenship.isEmpty()) {
	                    System.out.println("🔍 正在追加国籍检索: " + citizenship);
	                    uniqueItems.addAll(SearchService.getRelevantKnowledge(tableName, citizenship, embeddingClient));
	                }
	                
	                
	             // 🌟 改进 3：将 Set 转回 List 并按相似度距离排序 (确保最相关的在前)
	                List<SearchService.KnowledgeItem> sortedItems = new ArrayList<>(uniqueItems);
	                sortedItems.sort(Comparator.comparingDouble(item -> item.distance));

	                // 🌟 改进 4：拼接前 N 条内容 (防止上下文爆炸，例如取 Top 5)
	                StringBuilder contextSb = new StringBuilder();
	                int limit = Math.min(5, sortedItems.size()); 
	                for (int i = 0; i < limit; i++) {
	                    contextSb.append(sortedItems.get(i).content).append("\n");
	                }
	                String toolKnowledge = sortedItems.isEmpty() ? "抱歉，知识库中没有查到关于 " + country + " 的政策。" : contextSb.toString();
	                // 3. 构造完整消息链反馈给 AI
	                // 必须包含：User 问题 -> Assistant(TOOL_CALL) -> Tool(执行结果)
	                ArrayNode conversationContext = history.toJsonArray();
	                
	                // 添加 AI 刚才发出的 Tool Call 指令 (这一步非常重要，否则 context 不完整)
	                ObjectNode assistantMsg = MAPPER.createObjectNode();
	                assistantMsg.put("role", "assistant");
	                assistantMsg.set("tool_calls", MAPPER.createArrayNode().add(toolCallNode));
	                conversationContext.add(assistantMsg);

	                // 添加 Tool 的执行结果反馈
	                ObjectNode toolResultMsg = MAPPER.createObjectNode();
	                toolResultMsg.put("role", "tool");
	                toolResultMsg.put("tool_call_id", callId);
	                toolResultMsg.put("content", toolKnowledge);
	                System.out.println("最终contenct : " + toolKnowledge); 
	                conversationContext.add(toolResultMsg);

	                // 4. 第二次调用 AI：生成最终的人类语言回答
	                System.out.println("💬 AI 正在根据工具结果生成最终回复...");
                    String finalAnswer = router.finalLlm().chat(conversationContext);
	                
	                // 更新记录
	                queryHistory.addMessage("User", text);
	                queryHistory.addMessage("Context", sortedItems.isEmpty() ? "无检索结果" :  contextSb.toString());
	                
	                ca.answer = finalAnswer;
	            	
	            } else {
	                ca.answer = "提取参数失败，请明确您要查询的国家。";
	            }
	           
	       
	        	
	        } else {
	            // 如果 AI 没有调用工具，直接作为普通回答
	            ca.answer = firstResponse;
	            queryHistory.addMessage("User", text);
	            queryHistory.addMessage("Context", "【通用对话】");
	        }

	        // --- 步骤 4: 结果存档 ---
	        if (ca.answer != null && !ca.answer.isEmpty()) {
	            history.addMessage("assistant", ca.answer);
	            history.trim(MAX_HISTORY);
	        }
	        ca.code = 0;
	        return ca;

	    } catch (Exception e) {
	        e.printStackTrace();
	        ca.code = -1; ca.answer = "机器人系统故障: " + e.getMessage();
	        return ca;
	    }
	}

 
	private Map<String, String> parseArgsFromAiResponse(String answer) {
	    Map<String, String> args = new HashMap<>();
	    try {
	        String jsonStr = answer.substring(10);
	        JsonNode root = MAPPER.readTree(jsonStr);
	        String argsStr = root.path("function").path("arguments").asText();
	        JsonNode argsNode = MAPPER.readTree(argsStr);
	        
	        args.put("country", argsNode.path("country").asText(""));
	        args.put("citizenship", argsNode.path("citizenship").asText(""));
	    } catch (Exception e) {
	        e.printStackTrace();
	    }
	    return args;
	}

    /**
     * 辅助方法：处理空结果
     */
    private ChatAnswer handleEmptyResult(String text, ChatAnswer ca) {
        ca.code = -100;
        ca.answer = "知识库中没有找到任何内容";
        recordHistory(text, "【未匹配到相关知识】", "抱歉，知识库中没有找到任何内容。");
        return ca;
    }

    /**
     * 辅助方法：处理低相似度
     */
    private ChatAnswer handleLowSimilarity(String text, ChatAnswer ca) {
        ca.code = -101;
        ca.answer = "抱歉，我在知识库中未找到与您问题完全相关的信息。";
        // 即使失败，也要在 queryHistory 中记录一次，保持上下文连贯
        queryHistory.addMessage("User", text);
        queryHistory.addMessage("Context", "【未匹配到相关知识】");
        queryHistory.trim(MAX_QUERY_HISTORY);

        // 🌟 关键修复：记录到正式对话历史（用于发给 AI）
        history.addMessage("user", text);
        history.addMessage("assistant", ca.answer);
        history.trim(MAX_HISTORY);

        return ca;
    }

    /**
     * 统一记录历史记录
     */
    private void recordHistory(String userText, String contextText, String assistantText) {
        queryHistory.addMessage("User", userText);
        queryHistory.addMessage("Context", contextText);
        queryHistory.trim(MAX_HISTORY);
        history.addMessage("user", userText);
        history.addMessage("assistant", assistantText);
        history.trim(MAX_HISTORY);
    }
    public ChatAnswer ask(String text) {
        if("fullText".equalsIgnoreCase(queryMode)){
            return askFullContext(text);
        }else //"retrieveOnly".equalsIgnoreCase(queryMode)   or   retrieveRerank
            return ask3(text);

    }

    public ChatAnswer ask3(String text) {
        System.out.println("🚀 执行高级 RAG 流程 (重构版 ask3)...");
        ChatAnswer ca = new ChatAnswer(-1,null);

        // 1. 预处理：合法性检查与长度截断
        // 修复校验逻辑
        if (text == null || text.trim().isEmpty()) {
            ca.code = -1; ca.answer = "客户问题为空"; return ca;
        }
        String processedText = (text.length() > MAX_MESSAGE_LENGTH) ? text.substring(0, MAX_MESSAGE_LENGTH) : text;

        try {
            long requeryStart=System.currentTimeMillis();
            // 步骤 1: Rewrite - 结合历史上下文生成优化查询
            String optimizedQuery = performQueryRewrite(processedText);
            System.out.println("rewrite耗时="+(System.currentTimeMillis() - requeryStart) + " ms");

            // 步骤 2: Retrieve - 两阶段检索（粗排 + 本地 Qwen 精排）
            List<SearchService.KnowledgeItem> finalItems = performTwoStageRetrievalAsync(optimizedQuery);

            System.out.println("🔍 检索到的候选列表 after Retrieve - 两阶段检索:");
            finalItems.forEach(item ->
                    System.out.println("距离: " + formatDouble(item.distance) + " 分类:"+item.category+" | 摘要: " + item.summary)
            );
            // 步骤 3: Validate - 结果校验与拦截
            if (finalItems.isEmpty()) return handleEmptyResult(processedText, ca);
            if (finalItems.get(0).distance >similarityThreshold) return handleLowSimilarity(processedText, ca);

            // 4. 构建上下文
            StringBuilder fullCtx = new StringBuilder();
            for (int i = 0; i < finalItems.size(); i++) {
                fullCtx.append(String.format("%d. 【%s-%s】%s\n", i + 1,finalItems.get(i).category, finalItems.get(i).summary, finalItems.get(i).content));
            }

            // 🌟 5. 核心：执行封装好的动作
            // 记录摘要历史（用于下一轮重写）
            recordQueryHistory(processedText, finalItems);

            // 执行 AI 生成答案（用于当前回答）
            String ans = executeFinalChat(fullCtx.toString(), optimizedQuery);
            if(ans!=null) {
                ca.answer = ans;
                ca.code = 0;
            }else{
                ca.code = -500;
                ca.answer = "AI 响应为空，请稍后重试。";
            }
            return ca;
        } catch (Exception e) {
            e.printStackTrace();
            ca.code = -1;
            ca.answer = "机器人系统故障";
            return ca;
        }
    }
    //查询重写逻辑 (Rewrite)
    private String performQueryRewrite(String text) throws Exception {
        String historyContextStr = queryHistory.getMessageWindowSize(MAX_QUERY_HISTORY);
        if (historyContextStr.trim().isEmpty() || rewrite_prompt == null) return text;


        String userPrompt = "Conversation History:\n(" + historyContextStr + ")\n\nCurrent Question: (" + text + ")";
        System.out.println("🔄 正在利用上下文重写用户查询...userPrompt="+userPrompt);
        long startTime = System.currentTimeMillis();
        String rewritten = router.rewriter().generate(rewrite_prompt, userPrompt);
        System.out.println("⏱️ AI rewritten Time: " + (System.currentTimeMillis() - startTime) + " ms 重写后:"+rewritten);

        return (rewritten != null && !rewritten.isEmpty()) ? rewritten : text;
    }
    //两阶段检索引擎 (Retrieval & Rerank)
    private List<SearchService.KnowledgeItem> performTwoStageRetrieval(String query) throws Exception {
        // 阶段一：粗排（向量检索，建议 limit=15）
        List<SearchService.KnowledgeItem> candidates = SearchService.getRelevantKnowledge(tableName, query, embeddingClient);
        if (candidates.isEmpty()) return candidates;

        // 阶段二：本地精排（Ollama + Qwen 打分）
        System.out.println("🎯 正在执行本地 Qwen 精排 (Rerank)...");
        long rerankStart = System.currentTimeMillis();

        for (SearchService.KnowledgeItem item : candidates) {
            item.distance = calculateSemanticDistance(query,item.category,item.summary, item.content);
        }

        // 按精排得分重新排序
        candidates.sort(Comparator.comparingDouble(a -> a.distance));
        System.out.println("⏱️ Rerank 总耗时: " + (System.currentTimeMillis() - rerankStart) + " ms");

        return candidates.subList(0, Math.min(3, candidates.size()));
    }

    // 独立的语义距离计算逻辑
    private double calculateSemanticDistance(String query, String category, String summary, String content) {
        // ✅ 修复：只保留纯粹的文档特征，绝不能把 query 混进去
        String document = "分类：" + category + "\n" +
                "摘要：" + summary + "\n" +
                "内容：" + content;

        try {
            // router.rerank 内部会正确地将 query 和 document 区分开来处理
            double score = router.rerank(query, document);

            // 分数转换为距离 (得分越高，距离越近)
            double re = 1.0 - Math.min(score, 1.0);
            System.out.println("⏱️ Rerank score=" + formatDouble(re) + " category=" + category + " summary=" + summary + " 原始评分: " + score);
            return Math.round(re * 100.0) / 100.0;

        } catch (Exception e) {
            System.out.println("⏱️ Rerank 异常: " + e.getMessage());
        }
        return 1.0; // 默认不相关
    }
    private List<SearchService.KnowledgeItem> performTwoStageRetrievalAsyncNoRerank(String query) throws Exception {
        // 1. 粗排：从向量数据库获取候选 (这是你目前觉得准的地方)
        List<SearchService.KnowledgeItem> candidates = SearchService.getRelevantKnowledge(tableName, query, embeddingClient);

        System.out.println("🔍 [仅粗排模式] 检索到的候选列表:");
        candidates.forEach(item ->
                System.out.println("原始向量距离: " + formatDouble(item.distance) + " | 摘要: " + item.summary)
        );

        if (candidates.isEmpty()) return candidates;

        // 2. 🌟 跳过精排逻辑 🌟
        // 直接按原始向量距离排序（通常数据库返回时已经排好了，这里保底再排一次）
        candidates.sort(Comparator.comparingDouble(a -> a.distance));

        System.out.println("⚠️ 已跳过 Rerank 精排环节，直接返回粗排前 3 名。");

        // 直接返回前 N 个结果给 finalAsk
        return candidates.subList(0, Math.min(3, candidates.size()));
    }
    //10个全参与精排
    private List<SearchService.KnowledgeItem> performTwoStageRetrievalAsync2(String query) throws Exception {
        // 1. 粗排：从数据库获取候选
        List<SearchService.KnowledgeItem> candidates = SearchService.getRelevantKnowledge(tableName, query, embeddingClient);

        System.out.println("🔍 检索到的候选列表getRelevantKnowledge:");
        candidates.forEach(item ->
                System.out.println("距离: " + formatDouble(item.distance) + " | 摘要: " + item.summary)
        );
        if (candidates.isEmpty()) return candidates;

        System.out.println("🎯 开始并行精排 (For 循环异步版)...");
        long rerankStart = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (SearchService.KnowledgeItem item : candidates) {
            // 保存原始的向量距离，防止被精排分数覆盖
            double originalVectorDistance = item.distance;

            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                // 执行精排打分
                double rerankDist = calculateSemanticDistance(query, item.category, item.summary, item.content);

                // 🌟 核心逻辑：双重保险
                // 如果向量检索距离 < 0.3 (极其优秀)，则即便精排不看好，也强制给一个及格分（如 0.6）
                // 这样它就能通过你设置的 0.82 阈值，进入 finalAsk
                if (originalVectorDistance < 0.35 && rerankDist > 0.8) {
                    System.out.println("💡 [命中补偿] 摘要: " + item.summary + " 粗排距离 " + originalVectorDistance + " 极优，强制修正精排分。");
                    item.distance = rescueScore;
                } else {
                    item.distance = rerankDist;
                }
            }, rerankExecutor);

            futures.add(task);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(rerankTimeoutSeconds, TimeUnit.SECONDS);

            candidates.sort(Comparator.comparingDouble(a -> a.distance));
            System.out.println("⏱️ 并行 Rerank 成功，耗时: " + (System.currentTimeMillis() - rerankStart) + " ms");

        } catch (Exception e) {
            System.err.println("⚠️ 部分精排任务超时，执行现有结果排序。");
            // 💡 建议补上一句：取消未完成的任务，防止 CPU 被长期占用
            for (CompletableFuture<Void> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }
        return candidates.subList(0, Math.min(3, candidates.size()));
    }
    //“语义快道 (Semantic Fast Track)”。
    //通过这个逻辑，你可以大幅降低那些“送分题”的延迟。对于你的问题：是的，< 0.2 的结果应该直接视为“优胜者”直接保留，不再参与后续耗时的精排计算。
    private List<SearchService.KnowledgeItem> performTwoStageRetrievalAsync(String query) throws Exception {

        long queryStart = System.currentTimeMillis();
        // 1. 向量检索：从数据库获取初步候选 (假设召回 10 个)
        List<SearchService.KnowledgeItem> allCandidates = SearchService.getRelevantKnowledge(tableName, query, embeddingClient);
        if (allCandidates.isEmpty()) return allCandidates;
        System.out.println("🔍 检索到的候选列表getRelevantKnowledge: 粗排耗时="+(System.currentTimeMillis() - queryStart) + " ms");
        allCandidates.forEach(item ->
                System.out.println("距离: " + formatDouble(item.distance )+ "分类: " + item.category + "分类| 摘要: " + item.summary)
        );


        if ("retrieveOnly".equalsIgnoreCase(queryMode)) {
        // 🌟 不升模型方案：直接信任向量检索的前 3 名，彻底跳过 Rerank
         System.out.println("⚠️ 混合模式性能优化：跳过 精排，直接返回粗排结果。");
         return allCandidates.subList(0, Math.min(allCandidates.size(), 3));
        }
        List<SearchService.KnowledgeItem> fastTrackItems = new ArrayList<>(); // 快道池
        List<SearchService.KnowledgeItem> slowPool = new ArrayList<>();      // 待定池

        // 🏆 策略：基于对粗排结果的信任进行分流
        for (SearchService.KnowledgeItem item : allCandidates) {

            if (item.distance <trustThreshold) {
                // 🚀 [绝对信任] 距离极小，视为“上帝视角”直接命中的条目，不参与精排耗时
                System.out.println("绝对信任 直接命中 距离: " + formatDouble(item.distance) + " | 摘要: " + item.summary);
                fastTrackItems.add(item);
            } else if (item.distance < this.rerankTriggerMax) {
                // 🔍 [待定筛选] 距离在 0.2-0.6 之间，可能相关，送去给精排做逻辑裁决
                slowPool.add(item);
            }
        }
        // 🌟 性能熔断：如果快道已经足够好，直接收工，不再进入昂贵的精排逻辑
        if (fastTrackItems.size() >= 1) {
            // 原代码是 >= 3，对于电话机器人，只要有 1 个极度相关的条目（距离 < 0.2），
            // 往往就能命中知识点，直接返回可以极大降低 P99 延迟。
            System.out.println("🚀 [性能熔断] 命中上帝视角条目，直接返回，彻底跳过精排任务。");
            return fastTrackItems.subList(0, Math.min(fastTrackItems.size(), 3));
        }
        if (slowPool.isEmpty()) {
            System.out.println("没有候选需要精排，直接返回已有的 跳过精排任务。");
            return fastTrackItems; // 没有候选需要精排，直接返回已有的
        }
        // 🌟 固定取待定池中的前 5 个进行精排 (Top 5 优胜者制)
        List<SearchService.KnowledgeItem> needRerankItems = slowPool.stream()
                .limit(maxRerankCandidates)
                .toList();



        System.out.println("🎯 启动并行精排，样本数: " + needRerankItems.size());
        long rerankStart = System.currentTimeMillis();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (SearchService.KnowledgeItem item : needRerankItems) {
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                double originalDist = item.distance;
                // 调用大模型精排评分
                double rerankDist = calculateSemanticDistance(query, item.category, item.summary, item.content);

                // 🛡️ 补偿机制：如果粗排距离较近(0.3以内)，即使精排判死刑，也强制拉回及格线
                        // 🛡️ 补偿机制：动态阈值判断
                if (originalDist < compensateEmbedMax && rerankDist > compensateRerankMin) {
                //if (originalDist < 0.35 && rerankDist > 0.8) {
                    System.out.println("💡 [命中补偿] 摘要: " + item.summary + " 粗排距离 " + originalDist + " 极优，强制修正精排分。");

                    item.distance = rescueScore;
                } else {
                    item.distance = rerankDist;
                }
            }, rerankExecutor);
            futures.add(task);
        }

        // 等待所有精排任务完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("⚠️ 部分精排任务超时，执行现有结果排序。");
        }

        // 3. 合并：将“保送生”和“优等生”放到一起
        List<SearchService.KnowledgeItem> finalResults = new ArrayList<>();
        finalResults.addAll(fastTrackItems);
        finalResults.addAll(needRerankItems);

        // 最终排序：按修正后的距离排序，取前 3 名喂给 AI
        finalResults.sort(Comparator.comparingDouble(a -> a.distance));

        System.out.println("⏱️ rerank检索全链路耗时: " + (System.currentTimeMillis() - rerankStart) + " ms");

        return finalResults.subList(0, Math.min(finalContextLimit, finalResults.size()));
    }
    /**
     * 动作 1：记录重写历史 (仅限 queryHistory)
     */
// 确认你的 recordQueryHistory 是这样的：
    private void recordQueryHistory(String rawText, List<SearchService.KnowledgeItem> items) {
        StringBuilder sumCtx = new StringBuilder();
        for (SearchService.KnowledgeItem item : items) {
            if (item.summary != null && !item.summary.isEmpty()) {
                // 这里可以根据需要加 【】 符号辅助 Prompt 识别
                sumCtx.append("【").append(item.summary).append("】 ");
            }
        }
        queryHistory.addMessage("User", rawText);
        queryHistory.addMessage("Context", sumCtx.toString().trim());
        queryHistory.trim(MAX_QUERY_HISTORY);
    }

    /**
     * 动作 2：执行最终对话并处理存档 (替换原 generateFinalAnswer 的核心部分)
     */
    private String executeFinalChat(String fullContext, String optimizedQuery) throws Exception {

        // 1. 设置系统提示词（注入全文知识）
        setSystemMessage(fullContext);
        history.addMessage("user", optimizedQuery);
        history.trim(MAX_HISTORY);

        // 2. 调用 LLM
        long chatStart = System.currentTimeMillis();
// 在发送请求前获取 json 字符串
        String jsonPayload = history.toJsonArray().toString();
        System.out.println("⚡ Context 长度: " + jsonPayload.length() + " chars");

// 执行 chat
        String answer = router.finalLlm().chat(history.toJsonArray());
        System.out.println("⏱️ 最终答案生成耗时: " + (System.currentTimeMillis() - chatStart) + " ms");

        // 3. 安全截断并存入对话历史 (history)
        if (answer != null && !answer.isEmpty()) {
            if (answer.length() > MAX_MESSAGE_LENGTH) {
                answer = answer.substring(0, MAX_MESSAGE_LENGTH);
            }
            history.addMessage("assistant", answer);
            history.trim(MAX_HISTORY);
        }
        return answer;
    }

	/**
	 * 🌟 新增：解析 AI 提取的工具参数
	 * 预期格式：TOOL_CALL:{"id":"...","function":{"name":"query_visa_policy","arguments":"{\"country\":\"法国\"}"}}
	 */

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
    public static String formatDouble(double value) {
        // %.4f 表示保留 4 位小数
        return String.format("%.2f", value);
    }

    /**
     * 全量知识库模式 (Stuffing Mode)
     * 逻辑：重写问题 -> 加载全量知识 -> 注入 System Prompt -> 生成回答
     */
    public ChatAnswer askFullContext(String text) {
        System.out.println("🚀 执行全量知识库 Stuffing 流程 (askFullContext)...");
        ChatAnswer ca = new ChatAnswer(-1, null);

        // 1. 预处理：合法性检查与长度截断
        if (text == null || text.trim().isEmpty()) {
            ca.code = -1; ca.answer = "客户问题为空"; return ca;
        }
        String processedText = (text.length() > MAX_MESSAGE_LENGTH) ? text.substring(0, MAX_MESSAGE_LENGTH) : text;

        try {
            // 2. 步骤 1: Rewrite - 结合历史上下文生成优化查询
            // 确保在多轮对话中能够正确指代（如“他”、“那个”）
            String optimizedQuery = performQueryRewrite(processedText);

            // 3. 步骤 2: 加载全量知识库内容
            // 路径建议与你之前的 QwenFinalAsk 测试保持一致

            if (fulltext==null||fulltext.trim().length()<10) {
                ca.code = -404;
                ca.answer = "知识库内容为空或加载失败，请检查";
                return ca;
            }

            // 4. 步骤 3: 记录轻量化历史 (仅限 queryHistory)
            // 关键：不使用 recordQueryHistory(processedText, finalItems)，防止历史记录膨胀
            queryHistory.addMessage("User", processedText);
            queryHistory.addMessage("Context", "【全量知识库模式】");
            queryHistory.trim(MAX_QUERY_HISTORY);
            long chatStart = System.currentTimeMillis();
            // 5. 步骤 4: 执行最终对话并生成答案
            // 注入 fullKnowledge 到 System Message，并发送优化后的问题
            String ans = executeFinalChat(fulltext, optimizedQuery);
            long chatDuration = System.currentTimeMillis() - chatStart;

            System.out.println("⏱️ [Step 2] AI 生成答案耗时: " + chatDuration + " ms");
            if (ans != null) {
                ca.answer = ans;
                ca.code = 0;
            } else {
                ca.code = -500;
                ca.answer = "AI 响应为空，请稍后重试。";
            }
            return ca;

        } catch (Exception e) {
            e.printStackTrace();
            ca.code = -1;
            ca.answer = "机器人系统故障: " + e.getMessage();
            return ca;
        }
    }

    /**
     * 核心配套：独立的知识库加载函数
     * 解决 GBK 环境下读取 UTF-8 文件的编码与 BOM 问题
     */

    /**
     * 独立的知识库加载函数
     * 强制使用 UTF-8 编码读取，并自动处理 Windows 记事本可能产生的 BOM 头
     * @param filePath 知识库文件路径 (如 c:\\knowledge.txt)
     * @return 干净的知识库字符串内容
     */
    private String loadKnowledgeBase(String filePath) {
        try {
            java.io.File file = new java.io.File(filePath);
            if (!file.exists()) {
                System.err.println("❌ 知识库文件不存在: " + filePath);
                return "";
            }

            // 1. 显式按字节读取所有内容，防止受 JVM 默认编码 (GBK) 干扰
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath));

            // 2. 将字节数组转换为 UTF-8 字符串
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

            // 3. 处理 UTF-8 BOM (Byte Order Mark, \uFEFF)
            // 这是导致 "Input length = 1" 或 JSON 解析错误的常见元凶
            if (content.length() > 0 && content.charAt(0) == '\uFEFF') {
                content = content.substring(1);
            }

            System.out.println("📚 知识库加载成功: " + filePath + " (长度: " + content.length() + " 字符)");
            return content.trim();

        } catch (java.io.IOException e) {
            System.err.println("💥 加载知识库时发生 I/O 异常: " + e.getMessage());
            return "";
        } catch (Exception e) {
            System.err.println("💥 加载知识库时发生未知错误: " + e.getMessage());
            return "";
        }
    }
    public ModelRouter getRouter(){
        return router;
    }
    public void close() {
        System.out.println("🗑️ 正在释放 ChatSession 资源...");

        // 1. 清空历史记录
        if (this.history != null) {
            // 如果你的 ChatHistory 类没有 clear() 方法，可以直接 new 一个新的或者置空
            this.history = null;
        }
        if (this.queryHistory != null) {
            this.queryHistory = null;
        }

        // 2. 切断对大文本的引用
        this.fulltext = null;
        this.systemMessage = null;

        System.out.println("✅ ChatSession 释放完毕。");
    }

    // 💡 针对静态线程池的全局关闭方法（在应用停机时调用）
    public static void shutdownExecutor() {
        if (rerankExecutor != null && !rerankExecutor.isShutdown()) {
            System.out.println("🛑 正在关闭 Rerank 线程池...");
            rerankExecutor.shutdown();
            try {
                if (!rerankExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    rerankExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                rerankExecutor.shutdownNow();
            }
        }
    }
}
