	    
	    package com;

	    import com.fasterxml.jackson.databind.JsonNode;
	    import com.fasterxml.jackson.databind.ObjectMapper;
	    import com.fasterxml.jackson.databind.node.ObjectNode;
	    import com.zaxxer.hikari.HikariConfig;
	    import com.zaxxer.hikari.HikariDataSource;
	    import okhttp3.*;
	    import java.sql.*;
	    import java.util.ArrayList;
	    import java.util.List;

	    public class SearchService {
	        // ⚡ 优化 1: 使用 HikariCP 连接池
	        private static final HikariDataSource dataSource;
	        private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
	        private static final ObjectMapper MAPPER = new ObjectMapper();
	        private static final OkHttpClient CLIENT = new OkHttpClient();

	        static {
	            HikariConfig config = new HikariConfig();
	            config.setJdbcUrl("jdbc:postgresql://localhost:5432/postgres");
	            config.setUsername("postgres");
	            config.setPassword("call");
	            config.setMaximumPoolSize(10); // 根据实际需要调整
	            config.setMinimumIdle(2);
	                    // ✅ 添加连接池配置
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
	            dataSource = new HikariDataSource(config);
	        }
		    public static void main(String[] args) throws Exception {
		        // 模拟用户提问
		        String query = "免签的国家有哪些？"; 
		        // 尝试那个法语跨语言实验：String query = "Est-ce qu'il fait froid à Harbin ?";

		        System.out.println("🔍 正在搜索与问题相关的内容: " + query);

		        // 1. 将问题转化为向量
		        double[] queryVector = getEmbedding(query);
		        System.out.println("queryVector "+queryVector);
		        // 2. 去数据库执行向量搜索
		        List<KnowledgeItem> results = getRelevantKnowledge("免签的国家有哪些？");

		        // 3. 打印结果
		        System.out.println("\n✨ 匹配到的最相关知识：");
		        for (int i = 0; i < results.size(); i++) {
		            System.out.println((i + 1) + ". " + results.get(i).content+" "+ results.get(i).distance);
		        }
		    }
		    

	        // 定义结果类，包含距离
	        public static class KnowledgeItem {
	            public String content;
	            public double distance;
	            public KnowledgeItem(String content, double distance) {
	                this.content = content;
	                this.distance = distance;
	            }
	        }

	        public static List<KnowledgeItem> getRelevantKnowledge(String query) throws Exception {
	            double[] vector = getEmbedding(query);
	            return searchTopKnowledge(vector, 3);
	        }

	        private static List<KnowledgeItem> searchTopKnowledge(double[] vector, int limit) throws Exception {
	            List<KnowledgeItem> results = new ArrayList<>();
	            // ⚡ 修改 SQL 同时获取 content 和距离 <=>
	            String sql = "SELECT content, (embedding <=> ?::vector) as distance FROM enterprise_knowledge ORDER BY distance ASC LIMIT ?";

	            try (java.sql.Connection conn = dataSource.getConnection(); // 从连接池获取连接
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
	                            rs.getString("content"),
	                            rs.getDouble("distance")
	                        ));
	                    }
	                }
	            }
	            return results;
	        }

	        private static double[] getEmbedding(String text) throws Exception {
	            ObjectNode root = MAPPER.createObjectNode();
	            root.put("model", "text-embedding-3-small"); // 对应 1536 维度
	            root.put("input", text);

	            RequestBody body = RequestBody.create(MAPPER.writeValueAsString(root), MediaType.parse("application/json"));
	            Request request = new Request.Builder()
	                .url("https://api.openai.com/v1/embeddings")
	                .header("Authorization", "Bearer " + OPENAI_API_KEY)
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
	        
	            /**
     * ✅ 新增: 关闭数据源（应用关闭时调用）
     */
    public static void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
	    }
	