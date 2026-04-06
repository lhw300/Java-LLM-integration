package com.lcallai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class OpenAIClient implements LlmClient, EmbeddingClient {
    private static final Logger logger = LogManager.getLogger(OpenAIClient.class);
    private final String apiKey;
    private final String chatModel; // 例如 gpt-4o-mini
    private final String embedModel; // 例如 text-embedding-3-small
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    // 构造函数：初始化配置
    public OpenAIClient(String apiKey, String chatModel, String embedModel, OkHttpClient httpClient) {
        this.apiKey = apiKey;
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

        return sendRequest("https://api.openai.com/v1/chat/completions", root);
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

        String responseStr = sendRequest("https://api.openai.com/v1/embeddings", root);
        JsonNode embeddingNode = mapper.readTree(responseStr).path("data").get(0).path("embedding");
        double[] vector = new double[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            vector[i] = embeddingNode.get(i).asDouble();
        }
        return vector;
    }

    // 内部通用的 HTTP 请求方法
    private String sendRequest(String url, ObjectNode bodyNode) throws Exception {
    	
    
    	
        //  logger.debug("🤖 [OllamaClient] 发送本地请求: " + bodyJson);
        String bodyJson2 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bodyNode);
        
        // 现在这里打印出来的就是易读的格式了
        logger.debug("🤖 [openAI] 发送本地请求: \n" + bodyJson2);
        
        RequestBody body = RequestBody.create(mapper.writeValueAsString(bodyNode), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            String raw = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP Error: " + response.code() + " - " + raw);
            }
            // 解析 Chat 结果 (简单的结构判断，实际应用可根据业务细化)
            JsonNode root = mapper.readTree(raw);
            if (root.has("choices")) {
                return root.path("choices").get(0).path("message").path("content").asText().trim();
            }
            return raw; // 如果是 embedding，返回 raw 交给上层处理
        }
    }
}