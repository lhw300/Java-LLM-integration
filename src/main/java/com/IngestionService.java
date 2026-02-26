package com;

import okhttp3.OkHttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * 知识库自动化导入服务
 * 适配数据库表: enterprise_knowledge_qwen_1024
 * 输入格式: 分类 || 摘要 || 内容
 */
public class IngestionService {
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String DB_USER = "postgres";
    private static final String DB_PASS = "call";
    private static final OkHttpClient CLIENT = new OkHttpClient();

    public static void main(String[] args) throws Exception {

        // ==========================================
        // 🌟 配置区
        // ==========================================
        String filePath = "e:\\eit\\openai\\aiknowledge.txt";
        String aiType = "qwen-online";

        System.out.println("🚀 启动知识库导入流水线...");

        EmbeddingClient embedClient;
        String tableName;

        // 阿里百炼 Qwen-Online 配置
        if ("qwen-online".equalsIgnoreCase(aiType)) {
            String aliyunApiKey = System.getenv("QWEN_API_KEY");
            String aliyunBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

            embedClient = new OllamaClient(
                    aliyunBaseUrl,
                    "qwen-plus",
                    "text-embedding-v3",  // 1024维模型
                    CLIENT,
                    aliyunApiKey
            );
            tableName = "enterprise_knowledge_qwen_1024";
        } else {
            throw new IllegalArgumentException("当前配置仅支持 qwen-online 演示");
        }

        System.out.println("🗄️ 目标数据库表: " + tableName);

        // 2. 读取文件
        List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
        int successCount = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // 🌟 3. 解析文件内容：分类 || 摘要 || 内容
            String[] parts = line.split("\\|\\|", 3);

            if (parts.length < 3) {
                System.err.println("⚠️ 第 " + (i + 1) + " 行格式不完整，需满足 '分类 || 摘要 || 内容'，已跳过");
                continue;
            }

            String rawCategory = parts[0].trim();
            String rawSummary  = parts[1].trim();
            String content     = parts[2].trim();

            // 🌟 4. 截断保护：匹配数据库字段长度
            String category = rawCategory.length() > 50 ? rawCategory.substring(0, 50) : rawCategory;
            String summary  = rawSummary.length() > 255 ? rawSummary.substring(0, 255) : rawSummary;

            if (rawCategory.length() > 50) System.out.println("✂️ 分类已截断: " + rawCategory);
            if (rawSummary.length() > 255) System.out.println("✂️ 摘要已截断: " + rawSummary);

            try {
                // ✅ 向量化：合并三个字段以增强检索语义
                double[] vector = embedClient.embed(category + " " + summary + " " + content);

                // 5. 存入数据库
                saveToDatabase(tableName, category, summary, content, vector);
                System.out.println("✅ 已导入 [" + category + "]: " + summary);
                successCount++;

            } catch (Exception e) {
                System.err.println("❌ 导入失败 [" + summary + "]: " + e.getMessage());
            }
        }

        System.out.println("✨ 导入完成！共成功处理 " + successCount + " 条知识。");

        CLIENT.dispatcher().executorService().shutdown();
        CLIENT.connectionPool().evictAll();
    }

    /**
     * 执行 SQL 插入
     */
    private static void saveToDatabase(String tableName, String category, String summary, String content, double[] vector) throws Exception {
        // 包含表结构中的关键字段
        String sql = "INSERT INTO " + tableName + " (category, summary, content, embedding, is_active, create_time, source_name) " +
                "VALUES (?, ?, ?, ?::vector, true, CURRENT_TIMESTAMP, 'System_Import')";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, category);
            pstmt.setString(2, summary);
            pstmt.setString(3, content);

            // 向量转换
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                sb.append(vector[i]).append(i == vector.length - 1 ? "" : ",");
            }
            sb.append("]");

            pstmt.setObject(4, sb.toString());

            pstmt.executeUpdate();
        }
    }
}