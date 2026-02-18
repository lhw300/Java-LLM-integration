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
        chunks.add("15天免签政策：持有法国、德国、意大利、荷兰、西班牙、马来西亚护照的普通公民免办签证。");
        chunks.add("144小时过境免签：适用于美国、加拿大及大部分欧洲国家旅客，需持有144小时内确定日期的联程机票。");
        chunks.add("北京总部位于朝阳区中心地带，办理所有行政手续。上海分部位于浦东，负责技术研发。");
        chunks.add("西安办事处位于碑林区，负责西北后勤，不办理签证。西安路门店位于大连沙河口区，是零售网点。");
        chunks.add("紧急电话：报警110，急救120，火警119。哈尔滨冬季极寒（零下20度以下），需备防寒衣。");

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