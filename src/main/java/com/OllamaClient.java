package com;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;

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
    // 在 OllamaClient.java 中添加
    @Override
    public int getDimension() {
        // 如果是阿里的 text-embedding-v3，默认维度通常是 1024
        // 如果你使用的是 v2 或者是其他模型，请按需修改
        return 1024;
    }
    @Override
    public String modeType() {
        return "online"; // 声明自己是在线 API
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
        	//root.set("tools", mapper.readTree(toolsJson));

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
        // 1. 记录开始时间 (Record start time)
        long startTime = System.nanoTime();

        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", this.embedModel);
            root.put("input", text);

            if ("text-embedding-v3".equals(this.embedModel) && this.apiKey != null) {
                root.put("dimensions", 1024);
            }

            String rawResponse = sendRequest(baseUrl + "/embeddings", root);

            JsonNode rootNode = mapper.readTree(rawResponse);
            JsonNode embeddingNode = rootNode.path("data").get(0).path("embedding");
            double[] vector = new double[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = embeddingNode.get(i).asDouble();
            }
            return vector;

        } finally {
            // 2. 记录结束时间并计算耗时 (Record end time and calculate duration)
            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1_000_000; // 转换为毫秒 (Convert to milliseconds)

            // 3. 打印或记录日志 (Log the performance metrics)
            System.out.printf("Embedding completed in %d ms for model: %s%n", durationMs, this.embedModel);
        }
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


    /**
     * 打印向量距离：使用 1.0 - CosineSimilarity 逻辑 (与 Postgres <=> 对齐)
     */
    private static void printDistance(String label, String t1, String t2, double[] v1, double[] v2) {
        double dotProduct = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }
        double cosineSim = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));

        // 🌟 与 Postgres <=> 逻辑保持一致
        double distance = 1.0 - cosineSim;

        System.out.printf("[%s]\n   👉 语义距离 (越小越近): %.4f\n\n", label, distance);
    }



    public static void main(String[] args) {
        try {
            System.out.println("⏳ 正在初始化 Qwen Online 引擎...");

            // ==========================================
            // 1. 严格参考 SessionManager 的 qwen-online 初始化
            // ==========================================
            OkHttpClient httpClient = new OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();

            String qwenBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
            String qwenApiKey =  System.getenv("QWEN_API_KEY");

            // 初始化 Qwen 客户端
            OllamaClient qwenClient = new OllamaClient(
                    qwenBaseUrl,
                    "qwen-plus",
                    "text-embedding-v3",
                    httpClient,
                    qwenApiKey
            );
            System.out.println("✅ Qwen Online 初始化完成！\n");

            // ==========================================
            // 0. 🔥 在线 API 网络预热 (建立 HTTP 缓存连接池)
            // ==========================================
            System.out.println("🔥 正在进行网络预热 (建立 TCP/TLS 连接池以消除冷启动延迟)...");
            long warmupStart = System.currentTimeMillis();
            // 在线预热调用1次即可完成握手缓存（注意：这里会产生极少量的 Token 计费）
            qwenClient.embed("预热占位文本");
            qwenClient.generate("请回复数字1", "测试");
            long warmupTime = System.currentTimeMillis() - warmupStart;
            System.out.println("✅ 网络预热完成！(预热阶段总耗时: " + warmupTime + " ms)\n");


            // ==========================================
            // 2. 构造极端的“困难负样本”测试集
            // ==========================================
            String query = "教师的默认登录口令是什么？";
            String docA_Correct = "【适用对象：老师】初始密码统一设置为大写A202101小写b。";
            String docB_Trap = "【适用对象：学生】的默认登录口令是身份证后六位。";

            System.out.println("【用户提问】: " + query);
            System.out.println("---------------------------------------------------------");
            System.out.println("[文档 A - 正确但字面不重合]: " + docA_Correct);
            System.out.println("[文档 B - 错误但字面强重合]: " + docB_Trap);
            System.out.println("---------------------------------------------------------\n");

            // ==========================================
            // 🧪 阶段一：纯 Qwen Embedding 向量距离测试 (粗排)
            // ==========================================
            System.out.println("=== 🧪 阶段一：Qwen Embedding (text-embedding-v3) 距离计算 ===");
            System.out.println("公式: 1.0 - CosineSim (与 Postgres <=> 对齐，越小越近)");

            long embedStart = System.currentTimeMillis();
            double[] vQuery = qwenClient.embed(query);
            double[] vDocA = qwenClient.embed(docA_Correct);
            double[] vDocB = qwenClient.embed(docB_Trap);
            long embedTime = System.currentTimeMillis() - embedStart;

            System.out.printf("⏱️ Embedding 3条文本实际请求总耗时: %d ms (平均: %d ms/条)\n\n", embedTime, embedTime / 3);

            printDistance("正确答案 A", query, docA_Correct, vQuery, vDocA);
            printDistance("陷阱答案 B", query, docB_Trap, vQuery, vDocB);


            // ==========================================
            // 🎯 阶段二：纯 Qwen Rerank 测试 (精排)
            // ==========================================
            System.out.println("\n=== 🎯 阶段二：Qwen LLM Rerank (qwen-plus) 语义打分 ===");
            System.out.println("说明: 利用大模型强大的逻辑推理能力，判断适用对象是老师还是学生。");

            // 参考 ask3 里的 Rerank Prompt 设计逻辑
            String rerankPrompt = "你是一个严格的搜索相关性排序专家。请判断以下文档是否能正确回答用户的问题。" +
                    "请注意区分适用对象（如老师、学生等角色差异）。" +
                    "如果完全匹配且能解决问题，请只回复数字 1.0；如果完全不相关或主体错误，请只回复数字 0.0；部分相关回复 0.1 到 0.9 之间的小数。" +
                    "请仅输出数字，不要输出任何额外解释。";

            long rerankTotalStart = System.currentTimeMillis();

            long startA = System.currentTimeMillis();
            String qwenScoreA = qwenClient.generate(rerankPrompt, "问题: " + query + "\n文档: " + docA_Correct);
            long timeA = System.currentTimeMillis() - startA;

            long startB = System.currentTimeMillis();
            String qwenScoreB = qwenClient.generate(rerankPrompt, "问题: " + query + "\n文档: " + docB_Trap);
            long timeB = System.currentTimeMillis() - startB;

            long rerankTotalTime = System.currentTimeMillis() - rerankTotalStart;

            System.out.printf("[Doc A] 耗时 %d ms 👉 Qwen Rerank 最终打分: %s\n", timeA, qwenScoreA.trim());
            System.out.printf("[Doc B] 耗时 %d ms 👉 Qwen Rerank 最终打分: %s\n", timeB, qwenScoreB.trim());
            System.out.printf("⏱️ Rerank 2次网络请求+推理总耗时: %d ms\n\n", rerankTotalTime);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 距离计算工具：使用与 Postgres 对齐的 1.0 - S 逻辑
     */

    /*
     * 距离计算工具：使用与 Postgres 对齐的 1.0 - S 逻辑

    private static void printDistance(String label, String t1, String t2, double[] v1, double[] v2) {
        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }
        double cosineSim = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        double distance = 1.0 - cosineSim;
        System.out.printf("   [%s] 👉 向量距离: %.4f\n", label, distance);
    }
     */
}