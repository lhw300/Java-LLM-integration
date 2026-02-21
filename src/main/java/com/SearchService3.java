package com;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import okhttp3.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SearchService3 {
    // ... 前面的 HikariConfig 和 静态变量保持不变 ...
    private static final HikariDataSource dataSource;
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient CLIENT = new OkHttpClient();

    static {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/postgres");
        config.setUsername("postgres");
        config.setPassword("call");
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);
    }
    /* prompt 注意事项 *
     * 
     * 解决“污染”问题 区分 User 和 AI 的权重：在重写时，应明确告知引擎：以 User 提供的信息为准，AI 的回复仅供参考。这能避免第 5 轮那种把全名单都列进去的情况。
     * 约束“脑补”意图：如果最新提问只是一个名词（如“法国护照”），不要将其扩展为具体的动作（如“怎么申请”），应改写为“法国普通护照 相关政策”。
     * 在工业界有一句玩笑话：“RAG 系统，一半是调 SQL，一半是写 Prompt。”
     */

    public static void main(String[] args) throws Exception {
        // 模拟一轮连续的对话测试
        String[] userSteps = {
        		"中国免签政策",
            "我是法国人。", 
            "我免签吗？", 
            "那我的孩子呢？他14岁。" // 进一步测试补充条件
        };

        String[] scenario1 = {
        	    "我想了解德国的入境政策。",
        	    "哦不对，我记错了，我是法国人。", 
        	    "那我免签中国吗？", // 验证：搜索词是否能剔除德国，锁定法国
        	    "如果我还要带一只狗呢？" // 验证：在身份（法国）基础上叠加新实体（宠物）
        	};
        
        String[] scenario3 = {
        	    "法国普通护照。", 
        	    "我是这种。", 
        	    "去中国免签吗？", 
        	    "我孩子14岁。",
        	    "他呢？", // 故意不说“孩子”，看 history 里的“14岁”或者之前的对话能否对齐
        	     
        	};
        
        String[] reversalScenario = {
        	    "法国普通护照。",
        	    "我免签吗？",
        	    "那如果我是美国人呢？", // 重点：测试 AI 能否意识到“法国”已经失效，“美国”才是最新重要信息
        	    "我有这种护照。"
        	};
        String[] reversalScenario2= {
        	    "我是法国人",
        	    "去中国",
        	    "那如果去法国呢", // 重点：测试 AI 能否意识到“法国”已经失效，“美国”才是最新重要信息
        	    "我是中国人"
        	};
        
        String[] scenario2 = {
        	    "我是法国人，准备去北京旅游。",
        	    "北京现在的天气怎么样？", // 干扰项：非知识库业务
        	    "我需要办签证吗？", // 验证：能否跳过天气，把“法国人”和“签证”重新关联
        	    "15天够玩吗？" // 验证：对“15天”这个数字（来自之前AI给出的免签天数）的语境理解
        	};
        
        String[] ambiguityScenario = {
        	    "我在大连旅游。",
        	    "我想去西安路买东西，那里有门店吗？", // 预期搜索词：大连 西安路 零售网点
        	    "那如果我在西安市呢？能办签证吗？"  // 预期搜索词：西安 办事处 签证办理
        	};// //验证重点：看第 3 轮是否能识别出“西安”是城市名，并匹配到“西安办事处...不办理签证”这条记录
        String[] policyScenario = {
        	    "我是美国人。",
        	    "我打算去上海玩 3 天，然后飞日本。", // 关键点：3天 < 144小时
        	    "我需要提前办签证吗？"              // 预期搜索词：美国护照 上海 144小时过境免签
        	};
        String[] policyScenario2 = {
 
        	    "法国人去中国需要提前办签证吗？" ,             // 预期搜索词：美国护照 上海 144小时过境免签
        		 "我需要提前办签证吗？"   ,
        		 "简要回答上个问题，可以还是不可以？" 
        	};
        //多重约束下的过境政策（测试 144 小时 vs 15 天）
        //测试当用户身份同时符合多个知识点时，系统能否通过搜索词引导到最精确的那条。
        
        testChatLoop(policyScenario2);
    }

    /**
     * ✅ 循环调用测试函数
     * @param questions 用户按顺序提出的问题数组
     */
    public static void testChatLoop(String[] questions) throws Exception {
        // 使用 List 存储历史，方便进行切片（滑动窗口）
        List<String> historyList = new ArrayList<>();
        
        // 业务特有的系统提示词，可以从配置文件读取
        String visaPrompt = "你是一个精准的搜索词提取助手。你的任务是根据对话历史，提取用户当前的重要信息。\n" +
                            "要求：\n" +
                            "1. 忽略历史中不相关的干扰信息（如已."
                            + "变更的国籍或过时的问题）。\n" +
                            "2. 必须保留用户最新的身份信息（如国籍）。\n" +
                            "3. 只输出最适合数据库搜索的关键词组合。";
        
        String configPath = "e:\\eit\\openai\\prompt_rewritequery2.txt";
        String systemPrompt = loadPromptFromFile(configPath);
        System.out.println("📂 已从配置文件加载提示词。");

        for (int i = 0; i < questions.length; i++) {
            String currentQuery = questions[i];
            System.out.println("\n--- 第 " + (i + 1) + " 轮对话 ---");

            // ✅ 获取最近 3 轮的历史记录进行重写
            String context = getLimitedHistory(historyList, 3);
            String optimizedQuery = SessionManager.rewriteQuery(currentQuery, context,systemPrompt);
            System.out.println("用户输入: " + currentQuery);
            System.out.println("优化搜索词: " + optimizedQuery);

            double[] vector = SessionManager.getEmbedding(optimizedQuery);
            List<KnowledgeItem> results = searchTopKnowledge(vector, 3);

            
            if (!results.isEmpty() && results.get(0).distance <= 0.5) {
                String content = results.get(0).content;
                System.out.println("✅ 匹配知识: " + content + " (距离: " + results.get(0).distance + ")");
                
                // 更新 List 形式的历史记录
                historyList.add("User: " + currentQuery);
                
                String summary = content.split("：")[0].replace("【", "").replace("】", "");

                System.out.println("✅ Match Found: " + summary);
                
                historyList.add("Context: " + summary);
            } else {
                System.out.println("⚠️ 拦截: 距离太大，未找到准确知识。");
                historyList.add("User: " + currentQuery);
                historyList.add("Context: No matched knowledge。");
            }
          //  if(i==0) break;
        }
    }
    
    private static String loadPromptFromFile(String filePath) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)), 
                             java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("⚠️ 警告：无法从 " + filePath + " 读取配置，将使用默认 Prompt。原因: " + e.getMessage());
            // 返回一个保底的默认 Prompt，防止程序崩溃
            return "你是一个搜索词优化助手。请结合历史对话，提取用户最新的核心意图。";
        }
    }
    /**
     * ✅ 保持对话历史在一个合理的长度（例如最近 4 条）
     */
    /**
     * ✅ 优化：滑动窗口获取对话历史
     * @param historyList 存储所有对话条目的列表
     * @param lastK 保留最近的几轮对话（建议 3-5 轮）
     */
    private static String getLimitedHistory(List<String> historyList, int lastK) {
        if (historyList == null || historyList.isEmpty()) {
            return "";
        }

        // 一轮对话包含 User 和 AI 两个条目，所以窗口大小为 lastK * 2
        int windowSize = lastK * 2;
        int size = historyList.size();
        int start = Math.max(0, size - windowSize);

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < size; i++) {
            sb.append(historyList.get(i)).append("\n");
        }
        return sb.toString().trim();
    }
    // ... rewriteQuery, getEmbedding, searchTopKnowledge 等方法保持不变 ...
 



    private static List<KnowledgeItem> searchTopKnowledge(double[] vector, int limit) throws Exception {
        List<KnowledgeItem> results = new ArrayList<>();
        String sql = "SELECT content, (embedding <=> ?::vector) as distance FROM enterprise_knowledge ORDER BY distance ASC LIMIT ?";
        try (java.sql.Connection conn = dataSource.getConnection();
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
                    results.add(new KnowledgeItem(rs.getString("content"), rs.getDouble("distance")));
                }
            }
        }
        return results;
    }

    public static class KnowledgeItem {
        public String content;
        public double distance;
        public KnowledgeItem(String content, double distance) {
            this.content = content;
            this.distance = distance;
        }
    }
}