package com;

import okhttp3.OkHttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;

public class IngestionService {
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/postgres"; // 替换为你的库名
    private static final String DB_USER = "postgres";
    private static final String DB_PASS = "call";
    private static final OkHttpClient CLIENT = new OkHttpClient();

    public static void main(String[] args) throws Exception {
        
        // ==========================================
        // 🌟 配置区：自由切换你的数据源和 AI 类型
        // ==========================================
        String filePath = "e:\\eit\\openai\\aiknowledge.txt"; // 你的知识库文件路径
        String aiType = "qwen-online"; // 切换: "ollama" 或 "openai"
      //  aiType = "openai";
        System.out.println("🚀 启动知识库导入流水线...");
        System.out.println("📦 目标 AI 引擎: " + aiType);

        // 1. 根据 AI 类型，动态初始化 EmbeddingClient 和 对应的数据库表名
        EmbeddingClient embedClient;
        String tableName;

        if ("openai".equalsIgnoreCase(aiType)) {
            embedClient = new OpenAIClient(
                System.getenv("OPENAI_API_KEY"), 
                "gpt-4o-mini", 
                "text-embedding-3-small", 
                CLIENT
            );
            tableName = "enterprise_knowledge_1536"; // OpenAI 专用表 (1536维)
            
        } else if ("ollama".equalsIgnoreCase(aiType)) {
            embedClient = new OllamaClient(
                "http://localhost:11434/v1", 
                "qwen2.5:1.5b", 
                "nomic-embed-text", 
                CLIENT,null
            );
            tableName = "enterprise_knowledge_768"; // Nomic 专用表 (768维)
            
        } else if ("qwen-online".equalsIgnoreCase(aiType)) {
        		System.out.println("☁️ 正在使用阿里云百炼进行全量数据重刷...");
            
         //   String aliyunApiKey = "你的_DASHSCOPE_API_KEY"; // 建议使用 System.getenv("DASHSCOPE_API_KEY")
            String aliyunApiKey = System.getenv("QWEN_API_KEY"); // 请确保环境变量已设置
            System.out.println("☁️ 正在使用阿里云百炼进行全量数据重刷..."+aliyunApiKey);
            String aliyunBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

            embedClient = new OllamaClient(
                aliyunBaseUrl, 
                "qwen-plus",          // 聊天模型
                "text-embedding-v3",  // 🌟 阿里专用向量模型
                CLIENT,
                aliyunApiKey          // 🌟 传入 Key 以支持鉴权
            );
            
            // 🌟 关键：表名必须指向您为阿里向量准备的新表
            tableName = "enterprise_knowledge_qwen_1024";
                
            }
        
        
        else {
            throw new IllegalArgumentException("不支持的 AI 类型: " + aiType);
        }
        
        System.out.println("🗄️ 目标数据库表: " + tableName);

        // 2. 从文件按行读取知识库
        List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
        int successCount = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            // 跳过空行和注释行(以#开头)
            if (line.isEmpty() || line.startsWith("#")) continue;

            // 🌟 3. 解析文件内容 (使用双竖线 || 作为分隔符，正则需转义)
            String[] parts = line.split("\\|\\|", 2); 
            
            if (parts.length < 2) {
                System.err.println("⚠️ 第 " + (i + 1) + " 行格式错误，缺少分隔符 '||'，已跳过: " + line);
                continue;
            }

            String summary = parts[0].trim();
            String content = parts[1].trim();

            try {
                // 4. 调用对应的 AI 模型生成向量 (只对正文做向量化)
              //  double[] vector = embedClient.embed(content);
             // ✅ 修复代码 (将摘要和正文拼接到一起算向量，找回丢失的关键字权重)
                double[] vector = embedClient.embed(summary + " " + content);
                // 5. 动态插入对应的数据库表
                saveToDatabase(tableName, summary, content, vector);
                System.out.println("✅ 已导入: " + summary);
                successCount++;
                
            } catch (Exception e) {
                System.err.println("❌ 导入失败 [" + summary + "]: " + e.getMessage());
            }
        }
        
        System.out.println("✨ 导入完成！共成功处理 " + successCount + " 条知识。");
        
        // 优雅退出
        CLIENT.dispatcher().executorService().shutdown();
        CLIENT.connectionPool().evictAll();
    }

    private static void saveToDatabase(String tableName, String summary, String content, double[] vector) throws Exception {
        // 动态拼装 SQL (注意：表名不能用 ? 占位符，必须直接拼进去)
        String sql = "INSERT INTO " + tableName + " (summary, content, embedding, category) VALUES (?, ?, ?::vector, ?)";
        
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, summary);
            pstmt.setString(2, content);
            
            // 将 double[] 转换为 pgvector 格式 "[0.1, 0.2, ...]"
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                sb.append(vector[i]).append(i == vector.length - 1 ? "" : ",");
            }
            sb.append("]");
            
            pstmt.setObject(3, sb.toString());
            pstmt.setString(4, "基础业务知识"); // 默认分类
            
            pstmt.executeUpdate();
        }
    }
}