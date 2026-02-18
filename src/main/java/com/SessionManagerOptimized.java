package com;

import java.io.IOException;
import java.util.HashMap;
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

public class SessionManagerOptimized {

    // ⚡ 优化 1: 使用 ConcurrentHashMap 支持并发
    private static final Map<String, ChatSessionOptimized> sessions = new ConcurrentHashMap<>();
    
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    // ⚡ 优化 2: 单例 ObjectMapper
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
    
    private static String defaultSystemMessage;
    private static final String API_KEY = System.getenv("OPENAI_API_KEY");
    
    // ⚡ 优化 4: 缓存系统消息，避免重复读取文件
    static {
        defaultSystemMessage = 
            "You are a customer service assistant.\n" +
            "Answer using the KNOWLEDGE below.\n" +
            "you should answer questions based on the conversation history that give you some information about questioner.\n" +
            "If the answer is not in the knowledge, you may answer using your general knowledge with short answer.\n\n" +
            "Important: Answer in 10 words or less. No pleasantries.\n" +
            "=== KNOWLEDGE ===\n" +
            ChatWithKnowledge.loadFileUtf8("E:\\EIT\\openai\\knowledge.txt") + "\n" +
            "=== END KNOWLEDGE ===\n";
    }
    
    public SessionManagerOptimized() {
        // 构造函数可以为空，CLIENT 已经是静态初始化
    }
    
    public SessionManagerOptimized(String customSystemMessage) {
        defaultSystemMessage = customSystemMessage;
    }

    /**
     * 获取或创建会话
     */
    public static ChatSessionOptimized getSession(String clientId) {
        return sessions.computeIfAbsent(clientId,
                id -> new ChatSessionOptimized(defaultSystemMessage));
    }

    /**
     * 清理会话
     */
    public static void removeSession(String clientId) {
        sessions.remove(clientId);
    }
    
    /**
     * ⚡ 优化版 sendToOpenAI
     */
    public static String sendToOpenAI(String bodyJson) throws Exception {
        if (API_KEY == null || API_KEY.trim().isEmpty()) {
            throw new IllegalStateException("Please set OPENAI_API_KEY");
        }

        // ⚡ 优化 5: 复用 Request 对象的构建方式
        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/responses")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(RequestBody.create(bodyJson, JSON))
                .build();

        // ⚡ 优化 6: 使用 try-with-resources 确保资源释放
        try (Response resp = CLIENT.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String err = resp.body() != null ? resp.body().string() : "";
                throw new IOException("HTTP " + resp.code() + " body=" + err);
            }

            String raw = resp.body().string();
            
            // ⚡ 优化 7: 使用复用的 ObjectMapper
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            JsonNode outputNode = root.path("output");

            if (outputNode.isArray() && outputNode.size() > 0) {
                String aiText = outputNode
                        .get(0)
                        .path("content")
                        .get(0)
                        .path("text")
                        .asText();

                return aiText;
            }

            return "No response from AI.";
        }
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
    
    /**
     * ⚡ 新增: 获取会话统计信息
     */
    public static int getSessionCount() {
        return sessions.size();
    }
    
    /**
     * ⚡ 新增: 清理所有会话
     */
    public static void clearAllSessions() {
        sessions.clear();
    }
}
