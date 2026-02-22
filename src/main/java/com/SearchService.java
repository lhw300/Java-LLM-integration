package com;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SearchService {
    // ⚡ 优化 1: 使用 HikariCP 连接池
    private static final HikariDataSource dataSource;

    static {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/postgres");
        config.setUsername("postgres");
        config.setPassword("call");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        // ✅ 添加连接池配置
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        dataSource = new HikariDataSource(config);
    }

    // 🌟 定义结果类，新增 summary 字段
    public static class KnowledgeItem {
        public String summary; // 摘要
        public String content; // 正文
        public double distance; // 向量距离

        public KnowledgeItem(String summary, String content, double distance) {
            this.summary = summary;
            this.content = content;
            this.distance = distance;
        }
    }

    // 🌟 规范修改：方法签名增加 tableName 参数，实现动态切表
    public static List<KnowledgeItem> getRelevantKnowledge(String tableName, String query, EmbeddingClient embedClient) throws Exception {
        // 使用外部传入的接口将文字转为向量
        double[] vector = embedClient.embed(query);
        return searchTopKnowledge(tableName, vector, 3);
    }

    private static List<KnowledgeItem> searchTopKnowledge(String tableName, double[] vector, int limit) throws Exception {
        List<KnowledgeItem> results = new ArrayList<>();
        
        // ⚡ 动态表名，同时查出 summary 和 content
        // 注意：表名不能用 ? 占位符，必须用字符串拼接
        String sql = "SELECT summary, content, (embedding <=> ?::vector) as distance FROM " + tableName + " ORDER BY distance ASC LIMIT ?";

        try (Connection conn = dataSource.getConnection(); 
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
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
                        rs.getString("summary"), // ✅ 映射新增的摘要字段
                        rs.getString("content"),
                        rs.getDouble("distance")
                    ));
                }
            }
        }
        return results;
    }

    /**
     * ✅ 关闭数据源（应用关闭时调用）
     */
    public static void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}