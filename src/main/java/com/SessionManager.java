 

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
        public static  String configPath="d:\\ai";
        private static String G_QUERY_MODE = "retrieveRerank";

        private static String GLOBAL_QWEN_KEY = null; // 🌟 必须定义为全局静态变量
        // 1. 定义全局变量（默认兜底值）
        private static double G_SIMILARITY = 0.82;
        private static double G_TRUST = 0.25;
        private static double G_COMP_EMBED = 0.45;
        private static double G_COMP_RERANK = 0.80;
        private static int G_MAX_RERANK = 5;
        private static int G_FINAL_LIMIT = 3;
        private static int G_RERANK_TIMEOUT = 5; // 精排超时时间 (秒)


        // 🌟 新增：高级配置参数
        private static double G_RERANK_TRIGGER_MAX = 0.60;
        private static double G_RESCUE_SCORE = 0.60;

        // 🌟 定义全局唯一的 Prompts 和 知识库内容
        private static String globalRewritePrompt;
        private static String globalAskPrompt;
        private static String globalRerankPrompt;
        private static String globalFullText;

        private static ModelRouter     ACTIVE_ROUTER = null;
        private static LlmClient ACTIVE_LLM = null;
        private static EmbeddingClient ACTIVE_EMBED = null;
        private static String ACTIVE_TABLE = null; // 🌟 记录当前激活的表名

      //  public static String LUCENE_PATH = null; //
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
     public static void init(String configPath) {
         // 先用路径初始化配置工具
         AiConfig.init(configPath);

         // 🌟 关键：从刚刚加载的配置中读取 type，如果没有则默认为 hybrid2
         String configType = AiConfig.getStringConfig("system.run.type", "hybrid2");

         // 调用核心初始化逻辑
         init(configType, configPath);
     }
	    // 🌟 把它改成 public static，这样在 Main 方法里就能调用
	    public static void init(String type,String configPath) {
            try {
                System.out.println("📂 [System Init] 正在预加载全局配置文件和知识库...");
                SessionManager.configPath=configPath;
                AiConfig.init(configPath); // 初始化配置工具

// 🌟 1. 统一处理根路径格式：将反斜杠替换为正斜杠，确保 Linux 兼容性
                String base = configPath.replace("\\", "/");

                // 🌟 2. 动态拼接：根路径 + 配置文件中的子路径
                String promptRewritePath = base + AiConfig.getStringConfig("path.prompt.rewrite", "/config/prompt_rewritequery_v1_publish.txt");
                String promptAskPath     = base + AiConfig.getStringConfig("path.prompt.ask", "/config/prompt_finalask_v1_publish.txt");
                String promptRerankPath  = base + AiConfig.getStringConfig("path.prompt.rerank", "/config/prompt_rerank_v1_publish.txt");
                String knowledgePath     = base + AiConfig.getStringConfig("path.knowledge", "/config/knowledge_full.txt");

                // Lucene 路径同样动态拼接
               // LUCENE_PATH = base + AiConfig.getStringConfig("path.lucene", "/lucene_index");


                G_QUERY_MODE = AiConfig.getStringConfig("rag.query.mode", "retrieveRerank");

              //  LUCENE_PATH = "E:\\AI\\lucene_index";

                // 🌟 4. 加载 Prompt 和知识库内容
                globalRewritePrompt = loadPromptFromFile(promptRewritePath, "");
                globalAskPrompt     = loadPromptFromFile(promptAskPath, "");
                globalRerankPrompt  = loadPromptFromFile(promptRerankPath, "");
                globalFullText      = loadKnowledgeBase(knowledgePath);

                // 🌟 2. 从配置表加载 RAG 阈值参数 (后方数值为文件未找到时的默认兜底值)
                /*
                G_SIMILARITY     = AiConfig.getDoubleConfig("rag.threshold.similarity", 0.82);
                G_TRUST          = AiConfig.getDoubleConfig("rag.threshold.trust", 0.25);
                G_COMP_EMBED     = AiConfig.getDoubleConfig("rag.threshold.comp_embed", 0.45);
                G_COMP_RERANK    = AiConfig.getDoubleConfig("rag.threshold.comp_rerank", 0.80);
                G_MAX_RERANK     = AiConfig.getIntConfig("rag.limit.max_rerank", 5);
                G_FINAL_LIMIT    = AiConfig.getIntConfig("rag.limit.final_limit", 3);
                G_RERANK_TIMEOUT = AiConfig.getIntConfig("rag.timeout.rerank", 5);
                */

                GLOBAL_QWEN_KEY = AiConfig.getStringConfig("api.key.qwen", System.getenv("QWEN_API_KEY"));

                // 打印调试（生产环境可关闭）
                System.out.println("DEBUG: 读取到的 QWEN_API_KEY 长度 = " +
                        (GLOBAL_QWEN_KEY != null ? GLOBAL_QWEN_KEY.length() : "null"));

               // String aliyunApiKey = AiConfig.getStringConfig("api.key.qwen", System.getenv("QWEN_API_KEY"));
              //  String openAiApiKey = AiConfig.getStringConfig("api.key.openai", System.getenv("OPENAI_API_KEY"));


                SearchService.init(AiConfig.getStringConfig("storage.type","local"));
                System.out.println("✅ [System Init] 全局资源加载完成。type="+type);

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
                    // OpenAI 向量空间比较紧凑，可以严格一点


                    G_SIMILARITY     = AiConfig.getDoubleConfig("rag.threshold.similarity", 0.75);
                    G_TRUST          = AiConfig.getDoubleConfig("rag.threshold.trust", 0.20);
                    G_COMP_EMBED     = AiConfig.getDoubleConfig("rag.threshold.comp_embed", 0.35);
                    G_COMP_RERANK    = AiConfig.getDoubleConfig("rag.threshold.comp_rerank", 0.85);
                    G_MAX_RERANK     = AiConfig.getIntConfig("rag.limit.max_rerank", 5);
                    G_FINAL_LIMIT    = AiConfig.getIntConfig("rag.limit.final_limit", 3);
                    G_RERANK_TIMEOUT = AiConfig.getIntConfig("rag.timeout.rerank", 5);
// 🌟 新增：读取高级阈值
                    G_RERANK_TRIGGER_MAX = AiConfig.getDoubleConfig("rag.threshold.rerank_trigger_max", 0.60);
                    G_RESCUE_SCORE       = AiConfig.getDoubleConfig("rag.threshold.rescue_score", 0.60);

                    // OpenAI 场景下三个角色暂时都用同一个客户端
                    ACTIVE_ROUTER = new ModelRouter(client, client, client);
                    ACTIVE_LLM = client;
                    ACTIVE_EMBED = client;
                    ACTIVE_TABLE = "enterprise_knowledge_1536";
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
                    ACTIVE_LLM = ollamaClient;
                    ACTIVE_EMBED = ollamaClient;
                    ACTIVE_TABLE = "enterprise_knowledge_768";

                } else if (type.equalsIgnoreCase("qwen-online")) {
                    System.out.println("☁️ 正在初始化全链路阿里云百炼 (Qwen Online) — 模型路由模式...");



                    String aliyunBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

                    // ==========================================
                    // 🌟 Qwen 在线 API 专属阈值配置
                    // ==========================================
                    // 1. 最终拒答线：0.82
                    // 解释：如果 qwen-turbo 给出的分数低于 0.18（距离 > 0.82），说明它极度不看好，拦截掉。
                 // G_SIMILARITY = 0.82;
                    // 2. 信任快道：0.25
                    // 解释：阿里的 text-embedding-v3 如果算出距离小于 0.25，说明这文本跟用户问题几乎一模一样（比如直接复制了文档里的原话），直接放行，省下调用 turbo 精排的 Token 钱和时间！
                 // G_TRUST = 0.25;
                    // 3. 抢救资格线 (粗排)：0.45
                    // 解释：只要向量距离在 0.45 以内，说明语义上是在聊同一件事，哪怕细节有互斥，也值得留给大模型做最后的“拒答判定”，不能轻易丢弃。
                  //G_COMP_EMBED = 0.45;
                    // 4. 抢救触发线 (精排)：0.80
                    // 解释：如果 turbo 精排因为“苹果手机”和“电脑”这种互斥逻辑直接打了 0.1 分（距离 0.90，大于 0.80），触发防误杀机制，强制拉回及格线！
                 // G_COMP_RERANK = 0.80;
                  //G_MAX_RERANK = 4;        // 减少并发 HTTP 请求，防止拥塞
                //  G_FINAL_LIMIT = 3;
                  //G_RERANK_TIMEOUT = 8;    // 在线 API 容易波动，多等一会

                    G_SIMILARITY     = AiConfig.getDoubleConfig("rag.threshold.similarity", 0.82);
                    G_TRUST          = AiConfig.getDoubleConfig("rag.threshold.trust", 0.25);
                    G_COMP_EMBED     = AiConfig.getDoubleConfig("rag.threshold.comp_embed", 0.45);
                    G_COMP_RERANK    = AiConfig.getDoubleConfig("rag.threshold.comp_rerank", 0.80);
                    G_MAX_RERANK     = AiConfig.getIntConfig("rag.limit.max_rerank", 4);
                    G_FINAL_LIMIT    = AiConfig.getIntConfig("rag.limit.final_limit", 3);
                    G_RERANK_TIMEOUT = AiConfig.getIntConfig("rag.timeout.rerank", 8);
// 🌟 新增：读取高级阈值
                    G_RERANK_TRIGGER_MAX = AiConfig.getDoubleConfig("rag.threshold.rerank_trigger_max", 0.60);
                    G_RESCUE_SCORE       = AiConfig.getDoubleConfig("rag.threshold.rescue_score", 0.60);

                    // ── 轻量级客户端：负责 rewrite + rerank ──────────────────────────
                    OllamaClient turboClient = new OllamaClient(
                            aliyunBaseUrl,
                            "qwen-turbo",          // 快速、低成本
                            "text-embedding-v3",   // embed 模型（turboClient 兼任 embedding）
                            CLIENT,
                            GLOBAL_QWEN_KEY
                    );

                    // ── 重量级客户端：负责 finalAsk ──────────────────────────────────
                    OllamaClient plusClient = new OllamaClient(
                            aliyunBaseUrl,
                            "qwen-plus",           // 高质量回答
                            "text-embedding-v3",   // 占位，实际 embed 由 turboClient 承担
                            CLIENT,
                            GLOBAL_QWEN_KEY
                    );

                    // ── 组装路由器 ────────────────────────────────────────────────────
                    ACTIVE_ROUTER = ModelRouter.of(turboClient, plusClient);
                    //   rewrite → turboClient
                    //   rerank  → turboClient
                    //   final   → plusClient

                    ACTIVE_LLM = plusClient;   // 兼容旧 warmUp 路径
                    ACTIVE_EMBED = turboClient;  // embedding 由 turbo 负责
                    ACTIVE_TABLE = "enterprise_knowledge_qwen_1024";

                    System.out.println("✅ Qwen 路由客户端已挂载：rewrite/rerank → turbo | finalAsk → plus");
                    System.out.println("   请确保 " + ACTIVE_TABLE + " 表已存放阿里版向量数据。");

                } else if (type.equalsIgnoreCase("hybrid") || type.equalsIgnoreCase("混合模式")) {
                    // ⭐⭐⭐ 新增：混合模式 ⭐⭐⭐
                    System.out.println("🔄 正在初始化混合模式...");
                    System.out.println("   架构: 本地 Ollama (rewrite/rerank) + 云端 Qwen-Plus (final)");


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
                            GLOBAL_QWEN_KEY
                    );

                    // ── 组装路由器 ────────────────────────────────────────────────
                    ACTIVE_ROUTER = new ModelRouter(
                            localClient,  // rewrite → 本地 Ollama
                            localClient,  // rerank  → 本地 Ollama
                            cloudClient   // final   → 阿里云 qwen-plus
                    );

                    ACTIVE_LLM = cloudClient;   // 兼容旧代码
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

                } else if (type.equalsIgnoreCase("hybrid2")) {
                    // ⭐⭐⭐ 新增：混合模式 ⭐⭐⭐
                    System.out.println("🔄 正在初始化混合模式...");
                   // System.out.println("   架构: 本地 qwenonline (rewrite/rerank) + 云端 Qwen-Plus (final)");


                    String aliyunBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

// ==========================================
// 🎛️ Hybrid RAG Routing & Threshold Config
// 注：内部计算统一转换为距离 (Distance = 1.0 - Score)，值越小越相关
// Note: Internal logic uses distance (1.0 - score), smaller is more relevant.
// ==========================================

// 最终拒答防线：若精排后的最优距离 > 0.85，直接拦截并回复“未找到”，防止大模型产生幻觉。
// Final Cutoff: If the best distance > 0.85 after reranking, reject and reply "not found" to prevent LLM hallucination.
                //  G_SIMILARITY = 0.85;
// 信任快道：若粗排距离 < 0.30，视为完美匹配，跳过本地精排以实现极速响应。
// Fast Track: If vector distance < 0.30, treat as an exact match and skip local rerank for ultra-fast response.
                //  G_TRUST = 0.30;
// 误杀抢救(粗排资格线)：粗排距离必须 < 0.50，证明大方向相关，才有资格触发抢救。
// Rescue Mechanism (Embed Qualifier): Vector distance must be < 0.50 to prove general semantic relevance for a rescue attempt.
                //  G_COMP_EMBED = 0.50;
// 误杀抢救(精排触发线)：满足粗排资格且精排给出极差分数(距离>0.80)时，强制拉回及格线，交由云端裁决。
// Rescue Mechanism (Rerank Trigger): If embed qualifies but rerank score is terrible (> 0.80), force a passing grade for cloud LLM to decide.
                //  G_COMP_RERANK = 0.80;
// ==========================================
// ⚙️ Performance, Cost & Concurrency Control
// ==========================================
// 本地精排候选池：从粗排最多取 10 条进入本地打分（本地无 Token 成本，可适度扩大）。
// Local Rerank Pool: Fetch up to 10 items for local scoring (free of token cost, so pool can be larger).
                //  G_MAX_RERANK = 10;
// 云端上下文限制：最终只取前 3 条发往云端大模型，控制成本并防止 AI 注意力分散。
// Cloud Context Limit: Send only the top 3 items to the cloud LLM to save tokens and keep the AI focused.
                //  G_FINAL_LIMIT = 3;
// 精排熔断时长(秒)：异步精排最多等待 2 秒，超时直接降级返回粗排结果，保障接口响应速度。
// Rerank Timeout (sec): Wait max 2 seconds for async reranking. If timeout occurs, fallback to vector results to ensure responsiveness.
                //  G_RERANK_TIMEOUT = 2;
// DJL本地精排 + 云端Qwen，这里定制专属的 4 个阈值

                    G_SIMILARITY     = AiConfig.getDoubleConfig("rag.threshold.similarity", 0.85);
                    G_TRUST          = AiConfig.getDoubleConfig("rag.threshold.trust", 0.30);
                    G_COMP_EMBED     = AiConfig.getDoubleConfig("rag.threshold.comp_embed", 0.50);
                    G_COMP_RERANK    = AiConfig.getDoubleConfig("rag.threshold.comp_rerank", 0.80);
                    G_MAX_RERANK     = AiConfig.getIntConfig("rag.limit.max_rerank", 10);
                    G_FINAL_LIMIT    = AiConfig.getIntConfig("rag.limit.final_limit", 3);
                    G_RERANK_TIMEOUT = AiConfig.getIntConfig("rag.timeout.rerank", 2);
// 🌟 新增：读取高级阈值
                    G_RERANK_TRIGGER_MAX = AiConfig.getDoubleConfig("rag.threshold.rerank_trigger_max", 0.60);
                    G_RESCUE_SCORE       = AiConfig.getDoubleConfig("rag.threshold.rescue_score", 0.60);

                    DJLLocalClient djlLocalClient= new DJLLocalClient();
                    // ── 轻量级客户端：负责 rewrite + rerank ──────────────────────────
                    OllamaClient turboClient = new OllamaClient(
                            aliyunBaseUrl,
                            "qwen-turbo",          // 快速、低成本
                            "text-embedding-v3",   // embed 模型（turboClient 兼任 embedding）
                            CLIENT,
                            GLOBAL_QWEN_KEY
                    );

                    // ── 重量级客户端：负责 finalAsk ──────────────────────────────────
                    OllamaClient plusClient = new OllamaClient(
                            aliyunBaseUrl,
                            "qwen-plus",           // 高质量回答
                            "text-embedding-v3",   // 占位，实际 embed 由 turboClient 承担
                            CLIENT,
                            GLOBAL_QWEN_KEY
                    );

                    // ── 组装路由器 ────────────────────────────────────────────────
                    ACTIVE_ROUTER = new ModelRouter(
                            turboClient,  // rewrite → turboClient
                            djlLocalClient,  // rerank  → 本地
                            plusClient   // final   → 阿里云 qwen-plus
                    );

                    ACTIVE_LLM = plusClient;
                    ACTIVE_EMBED = djlLocalClient;
                    ACTIVE_TABLE = "enterprise_knowledge_1024"; // 本地向量维度

                    System.out.println("✅ 混合模式已激活");
                    System.out.println("   ├─ Rewrite:  云端 Qwen-turboClient");
                    System.out.println("   ├─ Rerank:   DJLLocalClient");
                    System.out.println("   ├─ Final:    云端 Qwen-Plus");
                    System.out.println("   └─ Embed:    本地 DJLLocalClient");
                    System.out.println("");


                } else {
                    throw new IllegalArgumentException("不支持的大模型类型: " + type);
                }


            } catch (Exception e) {

                System.err.println("❌ [System Init] 初始化失败！");
                e.printStackTrace();
                throw new RuntimeException("SessionManager 初始化失败", e);
            }
        }
        public static EmbeddingClient createQwenTurboClient() {
           // String aliyunApiKey = AiConfig.getStringConfig("api.key.qwen", System.getenv("QWEN_API_KEY"));

            // 1. 从环境变量获取 API KEY
           // String aliyunApiKey = System.getenv("QWEN_API_KEY");

            if (GLOBAL_QWEN_KEY == null || GLOBAL_QWEN_KEY.isEmpty()) {
                throw new RuntimeException("❌ 错误：环境变量 QWEN_API_KEY 未设置！");
            }

            String aliyunBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

            // 2. 初始化你的 OllamaClient (它已经实现了 EmbeddingClient 接口)
            // 对于 Ingestion 来说，只需要一个能算向量的客户端即可
            // 我们使用 qwen-turbo 对应的配置，指定 text-embedding-v3
            return new OllamaClient(
                    aliyunBaseUrl,
                    "qwen-turbo",          // 聊天模型占位
                    "text-embedding-v3",   // 🌟 实际使用的 Embedding 模型
                    CLIENT,                // 这里的 CLIENT 是你类里定义的静态 OkHttpClient
                    GLOBAL_QWEN_KEY
            );
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

// ✅ 就是这里！4个核心距离参数在这里一键注入给 session
                session.setThresholds(G_SIMILARITY, G_TRUST, G_COMP_EMBED, G_COMP_RERANK);
                // 🌟 新增：注入高级阈值
                session.setAdvancedThresholds(G_RERANK_TRIGGER_MAX, G_RESCUE_SCORE);

                session.setTopK(G_MAX_RERANK, G_FINAL_LIMIT, G_RERANK_TIMEOUT);
                // 💡 如果你把“召回数量”也提炼出来了，同样在这里一并注入：
                // session.setTopK(10, 3);

                // 2. 🌟 分开 set，注入 init 方法中加载好的全局资源
                // 注意：这里必须使用静态变量 globalFullText 等（小写开头，与你类定义一致）
                session.setFulltext(globalFullText);
                session.setRewrite_prompt(globalRewritePrompt);
                session.setAsk_prompt(globalAskPrompt);

                session.setRerankSys_prompt(globalRerankPrompt);   // 保留 session 自己存一份（给 warmUp 或日志用）
                session.getRouter().setRerankPrompt(globalRerankPrompt); // 绑定到路由层
                //session.setUseRerank(false);
				session.setQueryMode(G_QUERY_MODE);//fullText //retrieveOnly //retrieveRerank

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
                long start=System.currentTimeMillis();
                ACTIVE_ROUTER.rewriter().generate("system", "hi");
                // 改后
                //ACTIVE_ROUTER.rerank("hi", "hi");
                ACTIVE_ROUTER.rerank("北京", "北京是中国的首都");
                ACTIVE_ROUTER.finalLlm().generate("system", "hi");

                ACTIVE_EMBED.embed("hello");



                System.out.println("✅ 全链路连接池预热完成 t="+(System.currentTimeMillis()-start) );
            } catch (Exception e) {
                System.err.println("⚠️ 预热过程中发生异常: " + e.getMessage());
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
        /**
         * 释放单个用户的会话
         */
        public static void removeSession(String clientId) {
            ChatSession session = sessions.remove(clientId);
            if (session != null) {
                session.close();
                System.out.println("🗑️ 会话 [" + clientId + "] 已从管理器中移除。");
            }
        }

        /**
         * 释放所有会话（用于系统重启或内存告警时）
         */
        public static void clearAllSessions() {
            System.out.println("🧹 正在清理所有历史会话...");
            for (ChatSession session : sessions.values()) {
                session.close();
            }
            sessions.clear();

            // 顺便关闭全局线程池
            ChatSession.shutdownExecutor();
        }
    }