 

	package com;

	import java.io.IOException;
	import java.util.Map;
	import java.util.concurrent.ConcurrentHashMap;
	import java.util.concurrent.TimeUnit;
	import com.fasterxml.jackson.databind.JsonNode;
	import com.fasterxml.jackson.databind.ObjectMapper;
	import okhttp3.ConnectionPool;
	import okhttp3.MediaType;
	import okhttp3.OkHttpClient;
	import okhttp3.Request;
	import okhttp3.RequestBody;
	import okhttp3.Response;

	public class SessionManager {

	    private static final Map<String, ChatSessionOptimized> sessions = new ConcurrentHashMap<>();
	    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	    
	    // ⚡ 优化 3: 连接池优化
	    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
	        .connectTimeout(5, TimeUnit.SECONDS)
	        .writeTimeout(20, TimeUnit.SECONDS)
	        .readTimeout(75, TimeUnit.SECONDS)
	        .callTimeout(90, TimeUnit.SECONDS)
	        .retryOnConnectionFailure(true)
	        // ⚡ 关键: 连接池复用
	        .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
	        // ⚡ HTTP/2 支持
	        .protocols(java.util.Arrays.asList(
	            okhttp3.Protocol.HTTP_2,
	            okhttp3.Protocol.HTTP_1_1
	        ))
	        .build();
	    
	    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

	    /**
	     * ⚡ 核心改动：不再静态加载 knowledge.txt
	     * 而是提供一个构建动态 System Message 的模板
	     */
		/*
		 * public static String buildDynamicSystemMessage(String knowledgeContext) {
		 * if(knowledgeContext==null) return null; return
		 * "You are a helpful enterprise assistant.\n" +
		 * "Use the following pieces of retrieved context to answer the question.\n" +
		 * "If you don't know the answer, just say you don't know. Keep the answer concise.\n\n"
		 * + "=== CONTEXT ===\n" + knowledgeContext + "\n" + "=== END CONTEXT ===\n"; }
		 */

	    /**
	     * 获取会话。如果是新会话，先给一个基础的 System Message
	     */
		/*
		 * public static ChatSessionOptimized getSession(String clientId) { return
		 * sessions.computeIfAbsent(clientId, id -> new
		 * ChatSessionOptimized("You are a helpful assistant.")); }
		 */

	    /**
	     * ⚡ 新增：专门为 RAG 场景更新会话的 System Message
	     */
		/*
		 * public static ChatSessionOptimized getSession(String clientId, String
		 * knowledgeContext) {
		 * 
		 * String fullSystemMessage = buildDynamicSystemMessage(knowledgeContext);
		 * 
		 * ChatSessionOptimized session=null;
		 * 
		 * if(sessions.containsKey(clientId)) {
		 * session=(ChatSessionOptimized)sessions.get(clientId);
		 * 
		 * }else { session = new ChatSessionOptimized( ); sessions.put(clientId,
		 * session); } // 如果会话已存在，我们可以更新它的第一条 System 消息（根据 ChatSessionOptimized 的实现调整）
		 * // 这里简单处理：如果知识变化，直接创建/覆盖新会话以保证知识最新 if(fullSystemMessage!=null)
		 * session.setSystemMessage(fullSystemMessage);
		 * 
		 * 
		 * return session; }
		 */
	    
		/*
		 * public static void setSystemMessage(ChatSessionOptimized session, String
		 * knowledgeContext) { String fullSystemMessage =
		 * buildDynamicSystemMessage(knowledgeContext); if(fullSystemMessage!=null)
		 * session.setSystemMessage(fullSystemMessage); }
		 */
	    public static ChatSessionOptimized getSession(String clientId ) {
	    	
	       //   String fullSystemMessage = buildDynamicSystemMessage(knowledgeContext);
	          
	          ChatSessionOptimized session=null;
	          		
	         if(sessions.containsKey(clientId)) {
	        	 	  session=(ChatSessionOptimized)sessions.get(clientId);
	        	 	
	         }else {
	        		  session = new ChatSessionOptimized( );
	        		  sessions.put(clientId, session);
	         }
	        // 如果会话已存在，我们可以更新它的第一条 System 消息（根据 ChatSessionOptimized 的实现调整）
	        // 这里简单处理：如果知识变化，直接创建/覆盖新会话以保证知识最新
	    //    if(fullSystemMessage!=null)
	        	//	session.setSystemMessage(fullSystemMessage);
	        
	       
	        return session;
	    }

	    public static String sendToOpenAI(String bodyJson) throws Exception {
	        if (API_KEY == null || API_KEY.trim().isEmpty()) {
	            throw new IllegalStateException("Please set OPENAI_API_KEY");
	        }

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions") // chat/completions API
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(RequestBody.create(bodyJson, JSON))
                .build();

        try (Response resp = CLIENT.newCall(request).execute()) {
            // ✅ 修复: 只读一次 body
            String raw = resp.body().string();
            System.out.println("Response raw:"+raw);
            if (!resp.isSuccessful()) {
                // ✅ 使用已读取的 raw
                throw new IOException("HTTP " + resp.code() + " body=" + raw);
            }
	                        // ✅ 使用已读取的 raw
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            
            // ✅ 正确解析 chat/completions 响应
            String content = root.path("choices")
                .get(0)
                .path("message")
                .path("content")
                .asText();
            
            return content;
	        }
	    }

	    public static int getSessionCount() { return sessions.size(); }
    public static void clearAllSessions() {
        sessions.clear();
    }

    /**
     * ⚡ 新增: 预热连接池
     * 在应用启动时调用，建立初始连接
     */
    public static void warmUp() throws Exception {
        // 发送一个简单的测试请求来预热连接
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode testNode = mapper.createObjectNode();
        testNode.put("model", "gpt-4o-mini");
        testNode.put("max_output_tokens", 50); // 修改：最小值必须 >= 16
        
        com.fasterxml.jackson.databind.node.ArrayNode inputArray = testNode.putArray("input");
        com.fasterxml.jackson.databind.node.ObjectNode msgNode = inputArray.addObject();
        msgNode.put("role", "user");
        
        com.fasterxml.jackson.databind.node.ArrayNode contentArray = msgNode.putArray("content");
        com.fasterxml.jackson.databind.node.ObjectNode contentItem = contentArray.addObject();
        contentItem.put("type", "input_text");
        contentItem.put("text", "Hi");
        
        String testJson = mapper.writeValueAsString(testNode);
        
        try {
            sendToOpenAI(testJson);
            System.out.println("✅ 连接池预热完成");
        } catch (Exception e) {
            System.err.println("⚠️ 连接池预热失败: " + e.getMessage());
        }
    }
	}