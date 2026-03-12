 

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


        // 🌟 定义全局唯一的 Prompts 和 知识库内容
        private static String globalRewritePrompt;
        private static String globalAskPrompt;
        private static String globalRerankPrompt;
        private static String globalFullText;

        private static ModelRouter     ACTIVE_ROUTER = null;
        private static LlmClient ACTIVE_LLM = null;
        private static EmbeddingClient ACTIVE_EMBED = null;
        private static String ACTIVE_TABLE = null; // 🌟 记录当前激活的表名


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

	    // 🌟 把它改成 public static，这样在 Main 方法里就能调用
	    public static void init(String type) {
	        if (type == null) {
	            throw new IllegalArgumentException("模型类型不能为空！");
	        }

            if (type.equalsIgnoreCase("openai")) {
                System.out.println("⚙️ 系统正在初始化 OpenAI 客户端...");
                OpenAIClient client = new OpenAIClient(
                        System.getenv("OPENAI_API_KEY"),
                        "gpt-4o-mini",
                        "text-embedding-3-small",
                        CLIENT
                );
                // OpenAI 场景下三个角色暂时都用同一个客户端
                ACTIVE_ROUTER = new ModelRouter(client, client, client);
                ACTIVE_LLM    = client;
                ACTIVE_EMBED  = client;
                ACTIVE_TABLE  = "enterprise_knowledge_1536";
                System.out.println("✅ OpenAI 模型客户端已挂载");

            } else if (type.equalsIgnoreCase("deepseek")) {
                System.out.println("⚙️ 暂未完全实现 DeepSeek...");

            } else if (type.equalsIgnoreCase("ollama")) {
                System.out.println("💻 系统正在初始化本地 Ollama (Qwen) 客户端...");
                OllamaClient ollamaClient = new OllamaClient(
                        "http://192.168.1.23:11434/v1",
                        "qwen2.5:7b",
                        "nomic-embed-text",
                        CLIENT,
                        null
                );
                // 本地场景：三个角色共用同一模型（本地无 turbo/plus 之分）
                ACTIVE_ROUTER = new ModelRouter(ollamaClient, ollamaClient, ollamaClient);
                ACTIVE_LLM    = ollamaClient;
                ACTIVE_EMBED  = ollamaClient;
                ACTIVE_TABLE  = "enterprise_knowledge_768";

            } else if (type.equalsIgnoreCase("qwen-online")) {
                System.out.println("☁️ 正在初始化全链路阿里云百炼 (Qwen Online) — 模型路由模式...");

                String aliyunApiKey = System.getenv("QWEN_API_KEY");
                String aliyunBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

                // ── 轻量级客户端：负责 rewrite + rerank ──────────────────────────
                OllamaClient turboClient = new OllamaClient(
                        aliyunBaseUrl,
                        "qwen-turbo",          // 快速、低成本
                        "text-embedding-v3",   // embed 模型（turboClient 兼任 embedding）
                        CLIENT,
                        aliyunApiKey
                );

                // ── 重量级客户端：负责 finalAsk ──────────────────────────────────
                OllamaClient plusClient = new OllamaClient(
                        aliyunBaseUrl,
                        "qwen-plus",           // 高质量回答
                        "text-embedding-v3",   // 占位，实际 embed 由 turboClient 承担
                        CLIENT,
                        aliyunApiKey
                );

                // ── 组装路由器 ────────────────────────────────────────────────────
                ACTIVE_ROUTER = ModelRouter.of(turboClient, plusClient);
                //   rewrite → turboClient
                //   rerank  → turboClient
                //   final   → plusClient

                ACTIVE_LLM   = plusClient;   // 兼容旧 warmUp 路径
                ACTIVE_EMBED = turboClient;  // embedding 由 turbo 负责
                ACTIVE_TABLE = "enterprise_knowledge_qwen_1024";

                System.out.println("✅ Qwen 路由客户端已挂载：rewrite/rerank → turbo | finalAsk → plus");
                System.out.println("   请确保 " + ACTIVE_TABLE + " 表已存放阿里版向量数据。");

            }  else if (type.equalsIgnoreCase("hybrid") || type.equalsIgnoreCase("混合模式")) {
                // ⭐⭐⭐ 新增：混合模式 ⭐⭐⭐
                System.out.println("🔄 正在初始化混合模式...");
                System.out.println("   架构: 本地 Ollama (rewrite/rerank) + 云端 Qwen-Plus (final)");

                String aliyunApiKey = System.getenv("QWEN_API_KEY");
                String aliyunBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

                // ── 本地 Ollama 客户端（负责 rewrite + rerank）────────────────
                OllamaClient localClient = new OllamaClient(
                        "http://localhost:11434/v1",      // 本地 Ollama 地址
                        "qwen2.5:1.5b",                     // 本地模型
                        "nomic-embed-text",               // 本地向量模型
                        CLIENT,
                        null                              // 本地无需 API Key
                );

                // ── 阿里云客户端（仅负责 final）──────────────────────────────
                OllamaClient cloudClient = new OllamaClient(
                        aliyunBaseUrl,
                        "qwen-plus",                      // 高质量模型
                        "text-embedding-v3",              // 占位，实际用本地 embed
                        CLIENT,
                        aliyunApiKey
                );

                // ── 组装路由器 ────────────────────────────────────────────────
                ACTIVE_ROUTER = new ModelRouter(
                        localClient,  // rewrite → 本地 Ollama
                        localClient,  // rerank  → 本地 Ollama
                        cloudClient   // final   → 阿里云 qwen-plus
                );

                ACTIVE_LLM   = cloudClient;   // 兼容旧代码
                ACTIVE_EMBED = localClient;   // 使用本地 embed
                ACTIVE_TABLE = "enterprise_knowledge_768"; // 本地向量维度

                System.out.println("✅ 混合模式已激活");
                System.out.println("   ├─ Rewrite:  本地 Ollama Qwen2.5:1.5b");
                System.out.println("   ├─ Rerank:   本地 Ollama Qwen2.5:1.5b");
                System.out.println("   ├─ Final:    云端 Qwen-Plus");
                System.out.println("   └─ Embed:    本地 nomic-embed-text (768维)");
                System.out.println("");
                System.out.println("💰 成本节省: ~40% (仅 final 调用云端)");
                System.out.println("⚡ 延迟优化: ~50% (rewrite/rerank 本地执行)");

            } else {
                throw new IllegalArgumentException("不支持的大模型类型: " + type);
            }

            System.out.println("📂 [System Init] 正在预加载全局配置文件和知识库...");

            // 🌟 统一在这里读取文件，只读一次
            globalRewritePrompt = loadPromptFromFile("e:\\eit\\openai\\prompt_rewritequery_v1_publish.txt", "");
            globalAskPrompt = loadPromptFromFile("e:\\eit\\openai\\prompt_finalask_v1_publish.txt", "");
            globalRerankPrompt = loadPromptFromFile("e:\\eit\\openai\\prompt_rerank_v1_publish.txt", "");

            // 🌟 加载全量知识库，避免 GBK 乱码
            globalFullText = loadKnowledgeBase("c:\\knowledge.txt");

            System.out.println("✅ [System Init] 全局资源加载完成。");

        }

        public static ChatSession getSession(String clientId) {
            // 🌟 增加一道安全防线：防止忘记调用 init()
            if (ACTIVE_ROUTER == null) {
                throw new IllegalStateException("大模型客户端尚未初始化！请先调用 SessionManager.init(\"openai\")");
            }

            ChatSession session = sessions.get(clientId);
            if (session == null) {
                // 1. 创建基础能力会话
                session = new ChatSession(ACTIVE_ROUTER, ACTIVE_EMBED, ACTIVE_TABLE);

                // 2. 🌟 分开 set，注入 init 方法中加载好的全局资源
                // 注意：这里必须使用静态变量 globalFullText 等（小写开头，与你类定义一致）
                session.setFulltext(globalFullText);
                session.setRewrite_prompt(globalRewritePrompt);
                session.setAsk_prompt(globalAskPrompt);
                session.setRerankSys_prompt(globalRerankPrompt);

                session.setUseRerank(false);

                sessions.put(clientId, session);
                System.out.println("🆕 为客户端 [" + clientId + "] 创建了新会话，并已注入全局 Prompt 和知识库引用。");
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
        /**
         * 全链路预热：分别触发 rewrite 路径（turbo）和 embed 路径，建立连接池。
         */
        public static void warmUp() {
            if (ACTIVE_ROUTER == null || ACTIVE_EMBED == null) {
                System.out.println("⚠️ 预热失败：模型客户端尚未初始化。");
                return;
            }
            System.out.println("⏳ 正在进行全链路预热 (rewriter + embed)...");
            try {
                ACTIVE_ROUTER.rewriter().generate("system", "hi");
                ACTIVE_EMBED.embed("hello");
                System.out.println("✅ 全链路连接池预热完成");
            } catch (Exception e) {
                System.err.println("⚠️ 预热过程中发生异常: " + e.getMessage());
            }
        }

        // ⭐ 新增：动态切换模型（可选）
        public static void switchRewriteModel(LlmClient newRewriter) {
            if (ACTIVE_ROUTER != null) {
                ACTIVE_ROUTER = new ModelRouter(
                        newRewriter,                  // 新的 rewrite 客户端
                        ACTIVE_ROUTER.reranker(),     // 保留原有 rerank
                        ACTIVE_ROUTER.finalLlm()      // 保留原有 final
                );
                System.out.println("✅ Rewrite 模型已切换");
            }
        }
        public static void switchRerankModel(LlmClient newReranker) {
            if (ACTIVE_ROUTER != null) {
                ACTIVE_ROUTER = new ModelRouter(
                        ACTIVE_ROUTER.rewriter(),     // 保留原有 rewrite
                        newReranker,                  // 新的 rerank 客户端
                        ACTIVE_ROUTER.finalLlm()      // 保留原有 final
                );
                System.out.println("✅ Rerank 模型已切换");
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
         * 独立的知识库加载函数
         * 强制使用 UTF-8 编码读取，并自动处理 Windows 记事本可能产生的 BOM 头
         * @param filePath 知识库文件路径 (如 c:\\knowledge.txt)
         * @return 干净的知识库字符串内容
         */
        private static String loadKnowledgeBase(String filePath) {
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

    }