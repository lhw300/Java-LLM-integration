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
        /* 
         * // 🌟 新增：注入签证工具定义
    ArrayNode tools = mapper.createArrayNode();
    ObjectNode tool = tools.addObject();
    tool.put("type", "function");
    ObjectNode function = tool.putObject("function");
    function.put("name", "query_visa_policy");
    function.put("description", "查询特定国家的签证政策");
    ObjectNode parameters = function.putObject("parameters");
    parameters.put("type", "object");
    ObjectNode props = parameters.putObject("properties");
    props.putObject("country").put("type", "string").put("description", "目的地国家");
    // ... 保持简练，按需添加 required
    root.set("tools", tools);
         */
        String toolsJson2 = """
                [
                  {
                    "type": "function",
                    "function": {
                      "name": "query_visa_policy",
                      "description": "当用户咨询签证政策、免签条件或特定国家入境要求时调用此函数。",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "country": { "type": "string", "description": "目的地国家名称" }
                        },
                        "required": ["country"]
                      }
                    }
                  }
                ]
                """;

        String toolsJson = """
                [
                  {
                    "type": "function",
                    "function": {
                      "name": "query_visa_policy",
                      "description": "当用户咨询签证政策时调用。需提取目的地和申请人国籍。",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "country": { "type": "string", "description": "目的地国家名称" },
                          "citizenship": { "type": "string", "description": "申请人的国籍或护照签发国" }
                        },
                        "required": ["country"] 
                      }
                    }
                  }
                ]
                """;
        
        	// 直接解析成 JsonNode 并注入 root
        	root.set("tools", mapper.readTree(toolsJson));
        	 System.out.println("✅ 发送sendRequest..."+root.toString());
        String res= sendRequest(baseUrl + "/chat/completions", root);
       // System.out.println("✅ 收到应答   res="+res);
        return res;
    }
    public String chat2(ArrayNode messages) throws Exception {
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
        ObjectNode root = mapper.createObjectNode();
        root.put("model", this.chatModel);
        root.put("temperature", 0.0);
        root.set("messages", messages);
        // 这里调用底层的发送，不走带 tools 的 chat 方法
        return sendRequest(baseUrl + "/chat/completions", root);
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
            		JsonNode message = root.path("choices").get(0).path("message");
            		// 🌟 核心判断：如果模型想调用工具 /*
            		/*
            		"message": {
            		    "role": "assistant",
            		    "content": null, 
            		    "tool_calls": [
            		        {
            		            "id": "call_abc123",
            		            "type": "function",
            		            "function": {
            		                "name": "query_visa_policy",
            		                "arguments": "{\"country\": \"中国\", \"citizenship\": \"法国\"}"
            		            }
            		        }
            		    ]
            		}
            		*/
            	    if (message.has("tool_calls")) {
            	        return "TOOL_CALL:" + message.path("tool_calls").get(0).toString();
            	    }
                 return root.path("choices").get(0).path("message").path("content").asText().trim();
            }
            return raw; 
        }
    }
}