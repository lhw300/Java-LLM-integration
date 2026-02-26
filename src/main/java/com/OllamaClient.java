package com;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

public class OllamaClient implements LlmClient, EmbeddingClient {
    
    private final String baseUrl; 
    private final String chatModel; 
    private final String embedModel; 
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey; 

    public OllamaClient(String baseUrl, String chatModel, String embedModel, OkHttpClient httpClient, String apiKey) {
        this.baseUrl = baseUrl;
        this.chatModel = chatModel;
        this.embedModel = embedModel;
        this.httpClient = httpClient;
        this.apiKey = apiKey; 
    }

    @Override
    public String chat(ArrayNode messages) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", this.chatModel);
        root.put("temperature", 0.0);
        root.set("messages", messages);
        return sendRequest(baseUrl + "/chat/completions", root);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) throws Exception {
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        return chat(messages);
    }

    @Override
    public double[] embed(String text) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", this.embedModel);
        root.put("input", text);
     // 🌟 智能兼容逻辑
        // 1. 如果是阿里百炼在线版 (text-embedding-v3)
        if ("text-embedding-v3".equals(this.embedModel) && this.apiKey != null) {
            root.put("dimensions", 1024); // 
        }
        // 2. 如果是本地 Ollama (如 nomic-embed-text)，不加 dimensions 参数
        // 这样它会保持原有的 768 维逻辑，不会报错
        // 🌟 修复点：调用内部的 sendRequest 而不是手动构建 Request
        // sendRequest 会自动根据是否存在 apiKey 添加 Authorization 头
        String rawResponse = sendRequest(baseUrl + "/embeddings", root);
        
        JsonNode rootNode = mapper.readTree(rawResponse);
        JsonNode embeddingNode = rootNode.path("data").get(0).path("embedding");
        double[] vector = new double[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            vector[i] = embeddingNode.get(i).asDouble();
        }
        return vector;
    }

    private String sendRequest(String url, ObjectNode bodyNode) throws Exception {
        String bodyJson = mapper.writeValueAsString(bodyNode);
        RequestBody body = RequestBody.create(bodyJson, MediaType.parse("application/json; charset=utf-8"));
        
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body);

        // 🌟 这里是正确的：处理了 API Key
        if (this.apiKey != null && !this.apiKey.trim().isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + this.apiKey);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            String raw = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("API Error: " + response.code() + " - " + raw);
            }
            
            JsonNode root = mapper.readTree(raw);
            // 只有 Chat 接口有 choices 字段，Embedding 接口返回原始 JSON 字符串供解析
            if (root.has("choices")) {
                return root.path("choices").get(0).path("message").path("content").asText().trim();
            }
            return raw; 
        }
    }
}