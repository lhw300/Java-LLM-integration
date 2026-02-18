package com;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
public class ChatExample {
    public static void main(String[] args) throws Exception {
        String systemText =
                "You are a customer service assistant.\n" +
                "Answer ONLY using the KNOWLEDGE below.\n" +
              //  "If the answer is not in the knowledge, answer short and direct.\n" +
                "If the answer is not in the knowledge, you may answer using your general knowledge with short answer..\n\n"+
                "Important: Answer in 10 words or less. No pleasantries.\n" +
                "=== KNOWLEDGE ===\n" +
                ChatWithKnowledge.loadFileUtf8("E:\\EIT\\openai\\knowledge.txt") + "\n" +
                "=== END KNOWLEDGE ===\n" 
                ;

        SessionManager sessionManager = new SessionManager(systemText);

        // 假设 clientId 是用户唯一 ID
        String clientId = "user123";
        ChatSession session = sessionManager.getSession(clientId);

        session.addUserMessage("what room for 735 at NYIT vancouver?");
        sendToOpenAI(session); // 调用你封装的 ask() 方法
        session.addUserMessage("I was admitted in 2024 fall.Is it a good time?");
        sendToOpenAI(session);
         session.addUserMessage("Do I need to study 775?");
         sendToOpenAI(session);
         session.addUserMessage("Tell me headquater of NYIT new york Institue technology?");
         sendToOpenAI(session);
         
         
         
    }

    
    private static final MediaType JSON =
            MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT =   
    new OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .readTimeout(75, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build();

    
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

    public static String sendToOpenAI(ChatSession session) throws Exception {

        if (API_KEY == null || API_KEY.trim().isEmpty()) {
            throw new IllegalStateException("Please set OPENAI_API_KEY");
        }

        // ===== 构建 history JSON =====
        /*
        StringBuilder inputBuilder = new StringBuilder();
        inputBuilder.append("[");

        var history = session.getHistory();

        for (int i = 0; i < history.size(); i++) {

            ChatSession.Message msg = history.get(i);

            String type = msg.role.equals("assistant")
                    ? "output_text"
                    : "input_text";

            inputBuilder.append("{")
                    .append("\"role\":\"").append(msg.role).append("\",")
                    .append("\"content\":[{\"type\":\"")
                    .append(type)
                    .append("\",\"text\":\"")
                    .append(escapeJson(msg.content))
                    .append("\"}]")
                    .append("}");
            
            if (i < history.size() - 1) {
                inputBuilder.append(",");
            }
        }

        inputBuilder.append("]");
        System.out.println("inputbuilder="+inputBuilder);
        
        
        String bodyJson = "{"
                + "\"model\": \"gpt-4o-mini\","
                + "\"input\": " + inputBuilder + ","
                + "\"max_output_tokens\": 300"
                + "}";
        */


        // 1. 创建 ObjectMapper 实例 (通常建议作为单例复用)
        ObjectMapper mapper = new ObjectMapper();

        // 2. 创建根节点 (对应原本的 bodyJson)
        ObjectNode rootNode = mapper.createObjectNode();
        rootNode.put("model", "gpt-4o-mini");
        rootNode.put("max_output_tokens", 300);

        // 3. 创建 input 数组节点 (对应原本的 inputBuilder)
        ArrayNode inputArray = rootNode.putArray("input");
        List<ChatSession.Message> history         = session.getHistory();

        for (ChatSession.Message msg : history) {
            // 逻辑保持不变
            String type = "assistant".equals(msg.role) ? "output_text" : "input_text";

            // 创建单条消息对象
            ObjectNode messageNode = inputArray.addObject();
            messageNode.put("role", msg.role);

            // 创建 content 数组
            ArrayNode contentArray = messageNode.putArray("content");
            
            // 创建 content 内部的对象
            ObjectNode contentItem = contentArray.addObject();
            contentItem.put("type", type);
            // 重点：这里不需要 escapeJson()，Jackson 会自动处理所有特殊字符
            contentItem.put("text", msg.content); 
        }
        String bodyJson="";
        // 4. 生成最终 JSON 字符串
        try {
            // 如果你想看 input 部分 (对应原代码的 System.out.println)
          //  System.out.println("inputArray=" + mapper.writeValueAsString(inputArray));

            // 生成最终的 bodyJson
            bodyJson = mapper.writeValueAsString(rootNode);
            System.out.println("Final JSON: " + bodyJson);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        
        

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/responses")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(RequestBody.create(bodyJson, JSON))
                .build();

        try (Response resp = CLIENT.newCall(request).execute()) {

            if (!resp.isSuccessful()) {
                String err = resp.body() != null ? resp.body().string() : "";
                throw new IOException("HTTP " + resp.code() + " body=" + err);
            }

            String raw = resp.body().string();

            JsonNode root = objectMapper.readTree(raw);
            JsonNode outputNode = root.path("output");

            if (outputNode.isArray() && outputNode.size() > 0) {

                String aiText = outputNode
                        .get(0)
                        .path("content")
                        .get(0)
                        .path("text")
                        .asText();

                // ⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐
                // VERY IMPORTANT:
                // 把 AI 回复写回 session！
                // 否则下一轮 AI 会“失忆”
                // ⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐
                session.addAssistantMessage(aiText);
                System.out.println("answer="+aiText);
                return aiText;
            }

            return "No response from AI.";
        }
    }
    static String escapeJson(String s) {
        // 足够用于构造 JSON 字符串
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
