package com;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

    public static String sendToOpenAI(ChatSession session) throws Exception {

        if (API_KEY == null || API_KEY.trim().isEmpty()) {
            throw new IllegalStateException("Please set OPENAI_API_KEY");
        }

        // ===== 构建 history JSON =====
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
        
        // ===== 请求体 =====
        String bodyJson = "{"
                + "\"model\": \"gpt-4o-mini\","
                + "\"input\": " + inputBuilder + ","
                + "\"max_output_tokens\": 300"
                + "}";

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
