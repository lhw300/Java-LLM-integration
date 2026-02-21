package com;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

public class IngestionService {
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/postgres"; // 替换为你的库名
    private static final String DB_USER = "postgres";
    private static final String DB_PASS = "call";
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient CLIENT = new OkHttpClient();

    public static void main(String[] args) throws Exception {
        // 1. 模拟知识库分块 (针对那份中国概念知识库)
        List<String> chunks = new ArrayList<>();
     // 核心政策：包含明确的主体和条件
        chunks.add("【15天中国免签政策】：中国对法国、德国、意大利、荷兰、西班牙、马来西亚 6 国持普通护照人员试行单方面免签政策。入境事由支持经商、旅游、探亲及过境，停留期限不超过15天。");
        // 核心政策：包含特定限制条件（联程机票）
        chunks.add("【144小时中国过境免签政策】：适用于美国、加拿大、英国等54国旅客。要求持有效国际旅行证件和144小时内确定日期、座位的联程客票前往第三国（地区），从指定口岸过境中国。");
        chunks.add("【紧急电话】：报警110，急救120，火警119。【哈尔滨冬季情况】哈尔滨冬季极寒（零下20度以下），需备防寒衣。");

     // 修改这一条，重点突出“哪里能办，哪里不能办”
        chunks.add("【中国签证办理地点说明】：北京总部（朝阳区）受理所有签证申请和行政手续。上海分部（浦东）仅负责研发。西安办事处（碑林区）仅负责后勤，【不设签证窗口，无法办理签证】。");

        // 修改这一条，防止地理名词误导
        chunks.add("【门店网点】：大连西安路门店属于零售网点。请注意，该门店位于大连市，与西安市的签证业务无关，不提供任何签证咨询。");
        
        System.out.println("🚀 开始向量化并导入数据库...");

        for (String chunk : chunks) {
            // 2. 调用 OpenAI 获取向量
            double[] vector = getEmbedding(chunk);
            
            // 3. 存入 PostgreSQL
            saveToDatabase(chunk, vector);
            System.out.println("✅ 已完成分块导入: " + chunk.substring(0, 15) + "...");
        }
        System.out.println("✨ 所有知识库向量已就绪！");
    }

    private static double[] getEmbedding(String text) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", "text-embedding-3-small");
        root.put("input", text);

        RequestBody body = RequestBody.create(
            MAPPER.writeValueAsString(root), 
            MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/embeddings")
            .header("Authorization", "Bearer " + OPENAI_API_KEY)
            .post(body)
            .build();

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

    private static void saveToDatabase(String content, double[] vector) throws Exception {
        String sql = "INSERT INTO enterprise_knowledge (content, embedding) VALUES (?, ?::vector)";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, content);
            
            // 将 double[] 转换为 pgvector 要求的字符串格式 "[0.1, 0.2, ...]"
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                sb.append(vector[i]).append(i == vector.length - 1 ? "" : ",");
            }
            sb.append("]");
            
            pstmt.setObject(2, sb.toString());
            pstmt.executeUpdate();
        }
    }
}