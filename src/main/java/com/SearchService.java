package com;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SearchService {
    private static final HikariDataSource dataSource;

    static {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/postgres");
        config.setUsername("postgres");
        config.setPassword("call");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        dataSource = new HikariDataSource(config);
    }

    /**
     * 🌟 知识条目结果类
     * 已同步数据库 enterprise_knowledge_qwen_1024 的核心字段
     */
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
     * 语义检索核心入口
     * @param tableName 数据库表名
     * @param query 用户提问内容
     * @param embedClient 向量化客户端
     */
    public static List<KnowledgeItem> getRelevantKnowledge(String tableName, String query, EmbeddingClient embedClient) throws Exception {
        double[] vector = embedClient.embed(query);
        return searchTopKnowledge(tableName, vector, 15);
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
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}