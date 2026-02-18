package com;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

	public class ChatWithKnowledge {

	    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	    private static final OkHttpClient CLIENT = new OkHttpClient();
	 // 实例化 ObjectMapper
	    private static final ObjectMapper objectMapper = new ObjectMapper();
	    // 启动时读一次，缓存到内存
	    private static final String KNOWLEDGE = loadFileUtf8("E:\\EIT\\openai\\knowledge.txt");

	    // 保存多轮对话历史
	    private static final List<Message> history = new ArrayList<>();
	    private static final int MAX_HISTORY = 10;         // 最大保留消息条数
	    private static final int MAX_MESSAGE_LENGTH = 1000; // 单条消息最大字符数
	    
	    public static void main(String[] args) throws Exception {
	        String apiKey = System.getenv("OPENAI_API_KEY");
	        if (apiKey == null || apiKey.trim().isEmpty()) {
	            throw new IllegalStateException("Please set OPENAI_API_KEY");
	        }
	        String systemText =
	                "You are a customer service assistant.\n"
	              + "Answer ONLY using the KNOWLEDGE below.\n"
	              + "If the answer is not in the knowledge, you may answer using your general knowledge with short answer..\n\n"
	              + " Important: try to Answer in 10 words or less. Be extremely direct. No pleasantries."
	              + "=== KNOWLEDGE ===\n"
	              + KNOWLEDGE + "\n"
	              + "=== END KNOWLEDGE ===";
	        
	        history.add(new Message("system", systemText));

	        // 模拟多轮：你可以循环读控制台输入
	        ask(apiKey, "what room for 735 at NYIT vancouver?");
	        ask(apiKey, "what room for 735 at NYIT new York?");
	       // ask(apiKey, "what city is capital of Canada?");
	        ask(apiKey, "I was admitted in Fall 2024.");
	        ask(apiKey, "Do I need to study 775?");
	        //ask(apiKey, "Do I need to study 775 if I was admitted in Fall 2024?");

	        
	        
	    }

	    static void ask(String apiKey, String userQuestion) throws IOException {
	        // 截断消息长度
	        String truncatedQuestion = userQuestion.length() > MAX_MESSAGE_LENGTH
	                ? userQuestion.substring(0, MAX_MESSAGE_LENGTH)
	                : userQuestion;

	        // 将用户消息加入历史
	        history.add(new Message("user", truncatedQuestion));

	        // 保留最近 MAX_HISTORY 条消息（保留系统消息不计数）
	        List<Message> recentHistory = new ArrayList<>();
	        recentHistory.add(history.get(0)); // 系统消息
	        int start = Math.max(1, history.size() - MAX_HISTORY);
	        for (int i = start; i < history.size(); i++) {
	            recentHistory.add(history.get(i));
	        }

	        // 构建 input JSON
	        StringBuilder inputBuilder = new StringBuilder();
	        inputBuilder.append("[");
	        for (int i = 0; i < recentHistory.size(); i++) {
	            Message msg = recentHistory.get(i);
	            inputBuilder.append("{")
	                    .append("\"role\":\"").append(msg.role).append("\",")
	                    .append("\"content\":[{\"type\":\"input_text\",\"text\":\"")
	                    .append(escapeJson(msg.content)).append("\"}]")
	                    .append("}");
	            if (i < recentHistory.size() - 1) inputBuilder.append(",");
	        }
	        inputBuilder.append("]");

	        
	        System.out.println("inputbuilder="+inputBuilder);
	        
	        
	        String bodyJson = "{"
	                + "\"model\": \"gpt-4o-mini\","
	                + "\"input\": " + inputBuilder
	                + ",\"max_output_tokens\": 300"
	                + "}";

	        Request request = new Request.Builder()
	                .url("https://api.openai.com/v1/responses")
	                .addHeader("Authorization", "Bearer " + apiKey)
	                .post(RequestBody.create(bodyJson, JSON))
	                .build();

	        try (Response resp = CLIENT.newCall(request).execute()) {
	            if (!resp.isSuccessful()) {
	                String err = (resp.body() != null) ? resp.body().string() : "";
	                throw new IOException("HTTP " + resp.code() + " " + resp.message() + " body=" + err);
	            }
	            String raw = resp.body().string();

	            JsonNode root = objectMapper.readTree(raw);
	            JsonNode outputNode = root.path("output");
	            if (outputNode.isArray() && outputNode.size() > 0) {
	                String text = outputNode.get(0).path("content").get(0).path("text").asText();
	                System.out.println("Q: " + userQuestion);
	                System.out.println("AI answer: " + text);
	            }
	        } catch (Exception e) {
	            e.printStackTrace();
	        }
	    }

	    // ---------- utils ----------

	    static String loadFileUtf8(String path) {
	        File f = new File(path);
	        if (!f.exists()) {
	            throw new IllegalStateException("Missing file: " + f.getAbsolutePath());
	        }
	        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
	            ByteArrayOutputStream baos = new ByteArrayOutputStream();
	            byte[] buf = new byte[8192];
	            int n;
	            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
	            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
	        } catch (IOException e) {
	            throw new RuntimeException("Failed to read knowledge file: " + path, e);
	        }
	    }

	    static String escapeJson(String s) {
	        // 足够用于构造 JSON 字符串
	        return s.replace("\\", "\\\\")
	                .replace("\"", "\\\"")
	                .replace("\r", "\\r")
	                .replace("\n", "\\n");
	    }
	    static class Message {
	        String role;
	        String content;
	        Message(String role, String content) {
	            this.role = role;
	            this.content = content;
	        }
	    }
	}
