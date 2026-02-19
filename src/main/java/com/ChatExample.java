
	package com;

	import java.util.List;

	public class ChatExample {
	    public static void main(String[] args) throws Exception {
	        String clientId = "user_001";
	        String userQuery = "免签的国家有哪些？"; // 测试输入

			/*
			 * // 1. 检索阶段 List<SearchService.KnowledgeItem> items =
			 * SearchService.getRelevantKnowledge(userQuery);
			 * 
			 * // 2. 距离判断 (阈值过滤) if (items.isEmpty() || items.get(0).distance > 0.5) {
			 * System.out.println("❌ [系统拦截] 数据库中未找到足够相关的知识（最小距离: " + (items.isEmpty() ?
			 * "N/A" : items.get(0).distance) + "）。");
			 * System.out.println("🤖 回复：抱歉，在知识库中未找到相关内容。"); return; // ⚡ 直接退出，不请求 AI }
			 * 
			 * // 3. 正常 RAG 流程 StringBuilder context = new StringBuilder(); for
			 * (SearchService.KnowledgeItem item : items) {
			 * context.append("- ").append(item.content).append("\n"); }
			 */

	        ChatSessionOptimized session = SessionManager.getSession(clientId);
	        //String finalPrompt = "参考资料：\n" + context + "\n问题：" + userQuery;
	        
	        System.out.println("正在请求 AI...");
	        ChatAnswer answer = session.ask(userQuery);
	        System.out.println("💬 回答: " + answer.code+" "+answer.answer);
	          userQuery = "我是法国人"; // 测试输入
	            answer = session.ask(userQuery);
		        System.out.println("💬 回答: " + answer.code+" "+answer.answer);
	        
		          userQuery = "我去中国需要签证吗"; // 测试输入
		            answer = session.ask(userQuery);
			        System.out.println("💬 回答: " + answer.code+" "+answer.answer);
	    }
	}