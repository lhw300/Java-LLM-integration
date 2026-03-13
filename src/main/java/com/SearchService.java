package com;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SearchService {
    private static HikariDataSource dataSource;
    private static SearcherManager luceneSearcherManager;
    private static final String LUCENE_PATH = "E:\\EIT\\openai\\lucene_index";

    // 标志位，确保初始化只执行一次
    private static boolean isInitialized = false;
    public static class KnowledgeItem {
        public String category; // 分类 (character varying(50))
        public String summary;  // 摘要 (character varying(255))
        public String content;  // 正文 (text)
        public double distance; // 向量距离

        public KnowledgeItem(String category, String summary, String content, double distance) {
            this.category = category;
            this.summary = summary;
            this.content = content;
            this.distance = distance;
        }
    }
    /**
     * 🌟 根据模式进行按需初始化
     * 在 getRelevantKnowledge 调用时自动触发
     */
    private static synchronized void init(String aiType) {
        if (isInitialized) return;

        if ("local".equalsIgnoreCase(aiType)) {
            // 1. 初始化 Lucene 资源
            try {
                if (luceneSearcherManager == null) {
                    FSDirectory dir = FSDirectory.open(Paths.get(LUCENE_PATH));
                    luceneSearcherManager = new SearcherManager(dir, new SearcherFactory());
                    System.out.println("✅ 已按需初始化 Lucene SearcherManager");
                }
            } catch (Exception e) {
                System.err.println("❌ Lucene 初始化失败: " + e.getMessage());
            }
        } else {
            // 2. 初始化 Postgres 连接池
            if (dataSource == null) {
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl("jdbc:postgresql://localhost:5432/postgres");
                config.setUsername("postgres");
                config.setPassword("call");
                config.setMaximumPoolSize(10);
                config.setKeepaliveTime(60000);
                dataSource = new HikariDataSource(config);
                System.out.println("✅ 已按需初始化 Postgres 连接池");
            }
        }
        isInitialized = true;
    }

    public static List<KnowledgeItem> getRelevantKnowledge( String tableName, String query, EmbeddingClient embedClient) throws Exception {

        // 🌟 直接从接口方法获取模式，不再使用 instanceof
        String mode = embedClient.modeType();
        // 自动执行按需初始化
        init(mode);

        // --- Step 1: 向量化 ---
        long startEmbed = System.currentTimeMillis();
        double[] vector = embedClient.embed(query);
        System.out.println("Step 1: [" + mode + "] Embedding took: " + (System.currentTimeMillis() - startEmbed) + " ms");

        // --- Step 2: 分支检索 ---
        List<KnowledgeItem> results;
        long startSearch = System.currentTimeMillis();

        if ("local".equalsIgnoreCase(mode)) {
            results = searchLucene(vector, 15);
        } else {
            results = searchTopKnowledge(tableName, vector, 15);
        }

        System.out.println("Step 2: [" + mode + "] Search took: " + (System.currentTimeMillis() - startSearch) + " ms");
        return results;
    }
    /**
     * 适配 Lucene 的 KNN 向量检索方法
     */
    private static List<KnowledgeItem> searchLucene(double[] vector, int limit) throws Exception {
        List<KnowledgeItem> results = new ArrayList<>();

        // 💡 生产点：LUCENE_PATH 需对应 IngestionService 中的常量路径
        String lucenePath = "E:\\EIT\\openai\\lucene_index";

        // 💡 注意：在高并发生产场景，建议将 reader 设为全局单例，不要在方法内 try-with-resources 打开
        try (FSDirectory dir = FSDirectory.open(Paths.get(lucenePath));
             DirectoryReader reader = DirectoryReader.open(dir)) {

            IndexSearcher searcher = new IndexSearcher(reader);

            // 转换类型：double[] -> float[] 以适配 Lucene KNN
            float[] fVec = new float[vector.length];
            for (int i = 0; i < vector.length; i++) fVec[i] = (float) vector[i];

            // 发起 KNN 搜索
            KnnVectorQuery query = new KnnVectorQuery("embedding", fVec, limit);
            TopDocs topDocs = searcher.search(query, limit);

            for (ScoreDoc sd : topDocs.scoreDocs) {
                org.apache.lucene.document.Document doc = searcher.doc(sd.doc);
                // 🌟 核心转换：将相似度分数转换为你习惯的“距离”
                //正如你看到的 $0.88$，Lucene（以及 Elasticsearch）默认返回的是 得分 (Score)：
                // 设计初衷：搜索引擎（如 Google 或 Lucene）的逻辑是“相关性得分”，分数越高代表结果越匹配。
                // 算法映射：Lucene 9.x 的 HNSW 算法将余弦相似度映射到了 $[0, 1]$ 之间，公式通常为 $\frac{1 + \text{cosine\_similarity}}{2}$。
                // 公式：D = 2 * (1 - S)
                double unifiedDistance = 2.0 * (1.0 - sd.score);
                unifiedDistance = Math.round(unifiedDistance * 100.0) / 100.0;
                results.add(new KnowledgeItem(
                        doc.get("category"),
                        doc.get("summary"),
                        doc.get("content"),
                        unifiedDistance
                ));
            }
        }
        return results;
    }
    /**
     * 执行 pgvector 相似度查询
     */
    private static List<KnowledgeItem> searchTopKnowledge(String tableName, double[] vector, int limit) throws Exception {
        List<KnowledgeItem> results = new ArrayList<>();

        // ⚡ 优化：增加 is_active 过滤，确保只查询激活状态的知识
        String sql = "SELECT category, summary, content, (embedding <=> ?::vector) as distance " +
                "FROM " + tableName + " " +
                "WHERE is_active = true " +
                "ORDER BY distance ASC LIMIT ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // 将 double[] 转换为符合 pgvector 要求的字符串格式
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                sb.append(vector[i]).append(i == vector.length - 1 ? "" : ",");
            }
            sb.append("]");

            pstmt.setString(1, sb.toString());
            pstmt.setInt(2, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new KnowledgeItem(
                            rs.getString("category"), // 获取分类
                            rs.getString("summary"),  // 获取摘要
                            rs.getString("content"),  // 获取正文
                            rs.getDouble("distance")
                    ));
                }
            }
        }
        return results;
    }

    public static void shutdown() {
        try {
            if (dataSource != null) dataSource.close();
            if (luceneSearcherManager != null) luceneSearcherManager.close();
            System.out.println("🌙 资源已关闭");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public static void main_online(String[] args) {
        String[] testQueries = {
                "电脑客户端安装对系统有什么要求？",
                "老师的初始密码是大写的还是小写的？",
                "云教案功能具体包含哪些数字化资源？",
                "我想参加市级的教研员培训，在哪看通知？",
                "安卓手机版本太低能装 3.0 客户端吗？",
                "学生忘记密码，没绑定手机号怎么重置？",
                "学校管理员的账号一般是多少位的？",
                "管理员找回密码可以用手机验证码吗？",
                "粤教翔云平台的教研天地在哪里？",
                "可以用个人身份证号码直接登录吗？"
        };

        // 🌟 生产环境对应的 Postgres 表名
        String tableName = "enterprise_knowledge_qwen_1024";
        System.out.println("=== 🚀 Online RAG 性能压力测试启动 (Qwen + Postgres) ===");

        try {
            // 🌟 1. 切换为 Online 客户端
            // 注意：这里使用你之前定义的 SessionManager 或直接 new QwenClient()
            EmbeddingClient client = SessionManager.createQwenTurboClient();

            String mode = client.modeType();
            System.out.println("检测到模式: " + mode);

            // 🌟 2. 自动执行 Postgres 连接池初始化
            init(mode);

            // Warmup (在线模式主要是为了激活动态连接池)
            System.out.println("正在进行网络预热...");
            client.embed("warmup");

            long totalEmbedTime = 0;
            long totalSearchTime = 0;

            for (int i = 0; i < testQueries.length; i++) {
                String query = testQueries[i];



                // --- 统计检索耗时 (Postgres pgvector) ---
                long sSearch = System.currentTimeMillis();
                // 🌟 传入真实的 tableName
                List<SearchService.KnowledgeItem> results = SearchService.getRelevantKnowledge(tableName, query, client);
                long eSearch = System.currentTimeMillis();
                long searchMs = eSearch - sSearch;




                // 3. 打印详情
                System.out.println("\n" + "=".repeat(100));
                System.out.printf("【测试题目 %d】: %s\n", (i + 1), query);
                System.out.printf("【性能指标】: | 数据库检索: %d ms\n",  searchMs);
                System.out.println("-".repeat(100));

                if (results.isEmpty()) {
                    System.out.println("   ❌ 未能匹配到任何相关知识点");
                } else {
                    int maxRows = Math.min(3, results.size());
                    for (int j = 0; j < maxRows; j++) {
                        SearchService.KnowledgeItem item = results.get(j);
                        // 🌟 此时 item.distance 应该是 0.2 左右的原始距离
                        System.out.printf("   [Top %d] 距离: %.2f | 分类: %s | 摘要: %s\n",
                                (j + 1), item.distance, item.category, item.summary);
                        System.out.println("          内容: " + item.content);
                        if (j < maxRows - 1) System.out.println("          " + ".".repeat(30));
                    }
                }
            }

            // 4. 计算平均值 (修复了 double 转换 Bug)
            System.out.println("\n" + "=".repeat(100));
            System.out.printf("📊 平均向量化耗时 (API): %.2f ms\n", (double)totalEmbedTime / testQueries.length);
            System.out.printf("📊 平均检索耗时 (DB):  %.2f ms\n", (double)totalSearchTime / testQueries.length);
            System.out.println("✅ 测试完成。");

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            SearchService.shutdown();
        }
    }
    public static void main(String[] args) {
        // 10 个覆盖不同业务场景的测试题目
        String[] testQueries = {
                "电脑客户端安装对系统有什么要求？",
                "老师的初始密码是大写的还是小写的？",
                "云教案功能具体包含哪些数字化资源？",
                "我想参加市级的教研员培训，在哪看通知？",
                "安卓手机版本太低能装 3.0 客户端吗？",
                "学生忘记密码，没绑定手机号怎么重置？",
                "学校管理员的账号一般是多少位的？",
                "管理员找回密码可以用手机验证码吗？",
                "粤教翔云平台的教研天地在哪里？",
                "可以用个人身份证号码直接登录吗？"
        };


        System.out.println("=== 🚀 本地 RAG 性能压力测试启动 (i5-1340P) ===");

        try {
            // 初始化本地客户端（触发静态块锁定 DLL 和加载模型）
            EmbeddingClient client = new DJLLocalClient();
            String mode = client.modeType();
            // 自动执行按需初始化
            init(mode);

            client.embed("hello");
            client.embed("hello");
            client.embed("hello");
            long totalEmbedTime = 0;
            long totalSearchTime = 0;

            System.out.printf("%-3s | %-20s | %-10s | %-10s | %-6s\n", "序号", "测试题目", "向量化(ms)", "检索耗时(ms)", "最佳距离");
            System.out.println("----------------------------------------------------------------------------------");

            for (int i = 0; i < testQueries.length; i++) {
                String query = testQueries[i];

                // 执行检索并统计耗时
// 1. 向量化并计算耗时




                long startSearch = System.currentTimeMillis();
                List<SearchService.KnowledgeItem> results = SearchService.getRelevantKnowledge("N/A", query, client);
                long endSearch = System.currentTimeMillis();
                long searchMs = endSearch - startSearch;

                // 3. 打印表头及耗时统计
                System.out.println("\n" + "=".repeat(100));
                System.out.printf("【测试题目 %d】: %s\n", (i + 1), query);
              //  System.out.printf("【性能指标】: 向量化: %d ms | 检索耗时: %d ms\n", embedMs, searchMs);
                System.out.println("-".repeat(100));

                // 4. 打印 Top 3 知识点详情
                if (results.isEmpty()) {
                    System.out.println("   ❌ 未能匹配到任何相关知识点");
                } else {
                    int maxRows = Math.min(3, results.size());
                    for (int j = 0; j < maxRows; j++) {
                        SearchService.KnowledgeItem item = results.get(j);
                        System.out.printf("   [Top %d] 距离: %.2f | 分类: %s | 摘要: %s\n",
                                (j + 1), item.distance, item.category, item.summary);
                        // 内容可能较长，做个简单的缩进处理
                        System.out.println("          内容: " + item.content);
                        if (j < maxRows - 1) System.out.println("          " + ".".repeat(30));
                    }
                }
            }

            // 计算平均值
            System.out.println("----------------------------------------------------------------------------------");
            System.out.printf("📊 平均向量化耗时: %.2f ms\n", (double)totalEmbedTime / testQueries.length);
            System.out.printf("📊 平均检索耗时:   %.2f ms\n", (double)totalSearchTime / testQueries.length);
            System.out.println("✅ 测试完成。");

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            SearchService.shutdown();
        }
    }
    public static void main_single_test(String[] args) {
        String aiType = "local"; // 切换为 "qwen-online" 测试数据库模式
        aiType="online";
            String tableName = "enterprise_knowledge_qwen_1024";
        String query = "学生账号初始密码是什么？";

        System.out.println("=== 🔍 知识库检索测试启动 ===");
        System.out.println("模式: " + aiType);
        System.out.println("问题: " + query);

        EmbeddingClient client = null;
        try {
            // 1. 初始化对应的 Embedding 客户端
            if ("local".equalsIgnoreCase(aiType)) {
                // 确保 E:/EIT/openai/libtorch 下有配套的 DLL
                client = new DJLLocalClient();
                String mode = client.modeType();
                // 自动执行按需初始化
                init(mode);


            } else {
                client = SessionManager.createQwenTurboClient();
                String mode = client.modeType();
                init(mode);

            }
            client.embed("hello");
            client.embed("hello");
            client.embed("hello");


            // 2. 执行语义检索
            List<SearchService.KnowledgeItem> results = SearchService.getRelevantKnowledge(
                      tableName, query, client
            );

            // 3. 输出结果
            System.out.println("\n--- 🎯 检索结果 (Top 3) ---");
            if (results.isEmpty()) {
                System.out.println("❌ 未找到相关知识点");
            } else {
                for (int i = 0; i < Math.min(results.size(), 3); i++) {
                    SearchService.KnowledgeItem item = results.get(i);
                    System.out.printf("[%d] 分类: %s | 摘要: %s\n", (i + 1), item.category, item.summary);
                    System.out.println("    内容: " + item.content);
                    System.out.println("    得分/距离: " + item.distance);
                    System.out.println("-----------------------------------");
                }
            }

        } catch (Exception e) {
            System.err.println("❌ 测试运行出错:");
            e.printStackTrace();
        } finally {
            // 4. 生产建议：关闭资源
            SearchService.shutdown();
        }
    }
}