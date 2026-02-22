 

	package com;

	import java.io.IOException;
	import java.util.Map;
	import java.util.concurrent.ConcurrentHashMap;
	import java.util.concurrent.TimeUnit;
	import com.fasterxml.jackson.databind.JsonNode;
	import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.ConnectionPool;
	import okhttp3.MediaType;
	import okhttp3.OkHttpClient;
	import okhttp3.Request;
	import okhttp3.RequestBody;
	import okhttp3.Response;

	public class SessionManager {

	    private static final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
	    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	   // private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	    private static final ObjectMapper MAPPER = new ObjectMapper();
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

	 // 实例化一个具体的模型客户端 (比如上线用 OpenAI，本地测试换成 OllamaClient)
 
	 // 分别声明两个接口能力
	    private static LlmClient ACTIVE_LLM = null;
	    private static EmbeddingClient ACTIVE_EMBED = null;
	    private static String ACTIVE_TABLE = null; // 🌟 记录当前激活的表名
	    // 🌟 把它改成 public static，这样在 Main 方法里就能调用
	    public static void init(String type) {
	        if (type == null) {
	            throw new IllegalArgumentException("模型类型不能为空！");
	        }
	        
	        if (type.equalsIgnoreCase("openai")) {
	            System.out.println("⚙️ 系统正在初始化 OpenAI 客户端...");
	            OpenAIClient lccc = new OpenAIClient(
	                System.getenv("OPENAI_API_KEY"), 
	                "gpt-4o-mini", 
	                "text-embedding-3-small", 
	                CLIENT
	            );
	            
	            
	         // 将这个全能实例分别赋给两个接口变量
	            ACTIVE_LLM = lccc;
	            ACTIVE_EMBED = lccc;
	            ACTIVE_TABLE = "enterprise_knowledge_1536"; // 👈 动态表名
	            System.out.println("✅ OpenAI 模型客户端已挂载");
	            
	            
	        } else if (type.equalsIgnoreCase("deepseek")) {
	            // 预留给未来的 DeepSeek
	            // ACTIVE_LLM = new DeepSeekClient("your_key", CLIENT);
	            System.out.println("⚙️ 暂未完全实现 DeepSeek...");
	        } 
	        else if (type.equalsIgnoreCase("ollama")) {
	            System.out.println("💻 系统正在初始化本地 Ollama (Qwen) 客户端...");
	            // Ollama 默认运行在 11434 端口
	            OllamaClient ollamaClient = new OllamaClient(
	                "http://localhost:11434/v1", // Ollama 的 OpenAI 兼容接口前缀
	                "qwen2.5:1.5b",                     // 你的本地大语言模型名 (如果你拉的是 2.5，请改成 qwen2.5)
	                "nomic-embed-text",          // 本地专用的向量模型
	                CLIENT
	            );
	            ACTIVE_LLM = ollamaClient;
	            ACTIVE_EMBED = ollamaClient;
	            ACTIVE_TABLE = "enterprise_knowledge_768";  // 👈 动态表名
	        }
	        
	        else {
	            throw new IllegalArgumentException("不支持的大模型类型: " + type);
	        }
	    }

	    public static ChatSession getSession(String clientId) {
	        // 🌟 增加一道安全防线：防止忘记调用 init()
	        if (ACTIVE_LLM == null) {
	            throw new IllegalStateException("大模型客户端尚未初始化！请先调用 SessionManager.init(\"openai\")");
	        }

	        ChatSession session = null;
	        if(sessions.containsKey(clientId)) {
	             session = sessions.get(clientId);
	        } else {
	             session = new ChatSession(ACTIVE_LLM, ACTIVE_EMBED, ACTIVE_TABLE);
	             sessions.put(clientId, session);
	        }
	        return session;
	    }
 
	    public static   String sendToOpenAI(ChatHistory history) throws Exception {
	        // 创建请求 JSON
	        ObjectNode rootNode = MAPPER.createObjectNode();
	        rootNode.put("model", "gpt-4o-mini");
	        rootNode.put("max_tokens", 300);

	        
	        
	     // 关键代码
	        rootNode.set("messages", history.toJsonArray());

	        // 生成 JSON
	        String bodyJson = MAPPER.writeValueAsString(rootNode);
	        /*
	        // 创建 input 数组
	        ArrayNode messagesArray = rootNode.putArray("messages");

	        // ✅ 直接遍历 history，格式更简单
	        for (Message msg : history) {
	            ObjectNode messageNode = messagesArray.addObject();
	            messageNode.put("role", msg.role);
	            messageNode.put("content", msg.content); // ✅ 直接是字符串，不需要 content 数组
	        }
		*/
	 
	        System.out.println("final ask bodyJson:"+bodyJson);
	        // 调用优化版 SessionManagerOptimized
	        return  sendToOpenAI_(bodyJson);
	    }
	    
	    
	    public static String sendToOpenAI_(String bodyJson) throws Exception {
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
            JsonNode root = MAPPER.readTree(raw);
            
            // ✅ 正确解析 chat/completions 响应
            String content = root.path("choices")
                .get(0)
                .path("message")
                .path("content")
                .asText();
            
            return content;
	        }
	    }

	    
	    
	    
	    
	    public static String sendToOpenAI2(String bodyJson) throws Exception {
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
            JsonNode root =  MAPPER.readTree(raw);
            
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
        testNode.put("max_tokens", 50); // 修改：最小值必须 >= 16
        
  
        
        
        
        testNode.put("model", "gpt-4o-mini");
        testNode.put("max_tokens", 50);
        ArrayNode messagesArray = testNode.putArray("messages");
        ObjectNode msgNode = messagesArray.addObject();
        msgNode.put("role", "user");
        msgNode.put("content", "Hi");
        
        
        String testJson = mapper.writeValueAsString(testNode);
        
        try {
            sendToOpenAI_(testJson);
            System.out.println("✅ 连接池预热完成");
        } catch (Exception e) {
            System.err.println("⚠️ 连接池预热失败: " + e.getMessage());
        }
    }
    
    
    public static String rewriteQuery(String query, String history, String systemPrompt) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", "gpt-4o-mini");
        ArrayNode messages = root.putArray("messages");
        root.put("temperature", 0.0);
        // ✅ 使用从文件加载的动态 Prompt
        messages.addObject().put("role", "system").put("content", systemPrompt);
        
        //messages.addObject().put("role", "user").put("content", "历史内容:\n" + history + "\n最新提问: " + query);
        messages.addObject()
        .put("role", "user")
        .put("content", "Conversation History:\n(" + history + ")\n\nCurrent Question: (" + query + ")");
        
        
        String s=MAPPER.writeValueAsString(root);
        System.out.println("rewriteQuery发送 s="+s);
        RequestBody body = RequestBody.create(s, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + API_KEY)
                .post(body).build();

        try (Response response = CLIENT.newCall(request).execute()) {
            JsonNode resJson = MAPPER.readTree(response.body().string());
            return resJson.path("choices").get(0).path("message").path("content").asText().trim();
        }
    }
    public static double[] getEmbedding(String text) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", "text-embedding-3-small");
        root.put("input", text);
        RequestBody body = RequestBody.create(MAPPER.writeValueAsString(root), MediaType.parse("application/json"));
        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/embeddings")
            .header("Authorization", "Bearer " + API_KEY)
            .post(body).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            JsonNode resJson = MAPPER.readTree(response.body().string());
            JsonNode embeddingNode = resJson.path("data").get(0).path("embedding");
            double[] vector = new double[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = embeddingNode.get(i).asDouble();
            }
            return vector;
        }
    }
	}