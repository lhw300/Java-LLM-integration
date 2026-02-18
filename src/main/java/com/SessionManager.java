package com;


import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SessionManager {

    private static final Map<String, ChatSession> sessions = new HashMap<>();
    private static final MediaType JSON =
            MediaType.parse("application/json; charset=utf-8");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    static  OkHttpClient CLIENT =   null;
    private static  String defaultSystemMessage=
            "You are a customer service assistant.\n" +
            "Answer using the KNOWLEDGE below.\n" +
          //  "If the answer is not in the knowledge, answer short and direct.\n" +
            "you should answer questions based on the conversation history that give you some information about questioner.\n"+
            "If the answer is not in the knowledge, you may answer using your general knowledge with short answer..\n\n"+
            "Important: Answer in 10 words or less. No pleasantries.\n" +
            "=== KNOWLEDGE ===\n" +
            ChatWithKnowledge.loadFileUtf8("E:\\EIT\\openai\\knowledge.txt") + "\n" +
            "=== END KNOWLEDGE ===\n" 
            ;
    private static final String API_KEY = System.getenv("OPENAI_API_KEY");
    
    public SessionManager( ) {
         CLIENT =   
        	    new OkHttpClient.Builder()
        	    .connectTimeout(5, TimeUnit.SECONDS)
        	    .writeTimeout(20, TimeUnit.SECONDS)
        	    .readTimeout(75, TimeUnit.SECONDS)
        	    .callTimeout(90, TimeUnit.SECONDS)
        	    .retryOnConnectionFailure(true)
        	    .build();
    }
    
    public  SessionManager(String defaultSystemMessage) {
        this.defaultSystemMessage = defaultSystemMessage;
    }

    // 获取或创建一个客户会话
    public static ChatSession getSession(String clientId) {
        return sessions.computeIfAbsent(clientId,
                id -> new ChatSession(defaultSystemMessage));
    }

    // 可选：清理一个客户会话
    public static void removeSession(String clientId) {
        sessions.remove(clientId);
    }
    
    public static String sendToOpenAI(String bodyJson ) throws Exception{
    	

        if (API_KEY == null || API_KEY.trim().isEmpty()) {
            throw new IllegalStateException("Please set OPENAI_API_KEY");
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
               
               // System.out.println("answer="+aiText);
                return aiText;
            }

            return "No response from AI.";
    }
}
}