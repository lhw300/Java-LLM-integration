package com;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

// 🌟 同样实现这两个接口，无缝替换 OpenAI
public class OllamaClient implements LlmClient, EmbeddingClient {
    
    private final String baseUrl; // Ollama 的基础地址
    private final String chatModel; 
    private final String embedModel; 
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaClient(String baseUrl, String chatModel, String embedModel, OkHttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.chatModel = chatModel;
        this.embedModel = embedModel;
        this.httpClient = httpClient;
    }

    @Override
    public String chat(ArrayNode messages) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", this.chatModel);
        root.put("temperature", 0.0);
        root.set("messages", messages);

        // 调用 Ollama 的 OpenAI 兼容对话接口
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

        // 调用 Ollama 的 OpenAI 兼容向量接口
        RequestBody body = RequestBody.create(mapper.writeValueAsString(root), MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .post(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            String raw = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("Ollama Embed Error: " + response.code() + " - " + raw);
            }
            JsonNode rootNode = mapper.readTree(raw);
            JsonNode embeddingNode = rootNode.path("data").get(0).path("embedding");
            double[] vector = new double[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = embeddingNode.get(i).asDouble();
            }
            return vector;
        }
    }

    // 内部 HTTP 通信方法 (去掉了 Authorization 头，因为本地不需要)
    private String sendRequest(String url, ObjectNode bodyNode) throws Exception {
        String bodyJson = mapper.writeValueAsString(bodyNode);
      //  System.out.println("🤖 [OllamaClient] 发送本地请求: " + bodyJson);
        String bodyJson2 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bodyNode);
        
        // 现在这里打印出来的就是易读的格式了
        System.out.println("🤖 [OllamaClient] 发送本地请求: \n" + bodyJson2);
        RequestBody body = RequestBody.create(bodyJson, MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url)
                // Ollama 默认不需要 Bearer Token
                .post(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            String raw = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("Ollama Chat Error: " + response.code() + " - " + raw);
            }
            
            JsonNode root = mapper.readTree(raw);
            if (root.has("choices")) {
                return root.path("choices").get(0).path("message").path("content").asText().trim();
            }
            return raw; 
        }
    }
}