package com;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class QwenFinalAsk {
    // --- 核心配置 ---
    private static final String chatModel = "qwen-plus";
    private static final String TEST_JSON_PATH = "c:\\test3.json.txt";
    private static final String KNOWLEDGE_PATH = "c:\\knowledge.txt"; // 全量知识库路径
    private static final String QWEN_COMPATIBLE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    private final String apiKey = System.getenv("QWEN_API_KEY");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("📂 [Qwen 模式] 已监控本地文件: " + TEST_JSON_PATH);
        System.out.println("⌨️  指令说明: \n - 直接回车: 执行标准 chat\n - 输入 '2': 执行全量知识库测试 (chat2)\n - 输入 'exit': 退出");

        QwenFinalAsk aq = new QwenFinalAsk();

        // 1. 预热逻辑
        try {
            System.out.println("🔥 正在建立 TCP 长连接并预热云端 Cache...");
            ArrayNode warmupMessages = mapper.createArrayNode();
            warmupMessages.addObject().put("role", "user").put("content", "hi");
            long p1 = System.currentTimeMillis();
            aq.chat(warmupMessages);
            System.out.println("✅ 预热完成，预热耗时: " + (System.currentTimeMillis() - p1) + " ms");
        } catch (Exception e) {
            System.err.println("⚠️ 预热失败: " + e.getMessage());
        }

        while (true) {
            System.out.print("\n[Qwen 等待指令] > ");
            String input = scanner.nextLine().trim();
            if ("exit".equalsIgnoreCase(input)) break;

            try {
                // 读取 test.json.txt 获取当前对话消息
                String jsonContent = readLocalFile(TEST_JSON_PATH);
                JsonNode rootInput = mapper.readTree(jsonContent);
                ArrayNode messages = (ArrayNode) rootInput.get("messages");

                String aiResponse;
                long start = System.currentTimeMillis();

                if ("2".equals(input)) {
                    System.out.println("📚 执行 chat2: 读取全量知识库并发送...");
                    aiResponse = aq.chat2(messages);
                } else {
                    System.out.println("🚀 执行标准 chat: 发送当前 JSON 消息...");
                    aiResponse = aq.chat(messages);
                }

                long duration = System.currentTimeMillis() - start;

                System.out.println("\n==================== Qwen 结果 ====================");
                System.out.println("⏱️  总耗时: " + duration + " ms");
                System.out.println("🤖 AI 回复内容:");
                System.out.println("--------------------------------------------------");
                System.out.println(aiResponse);
                System.out.println("--------------------------------------------------");

            } catch (Exception e) {
                System.err.println("💥 运行时异常: " + e.getMessage());
            }
        }
    }

    /**
     * chat2 方法：将整个 knowledge.txt 注入到第一个 System Message 中
     */
    public String chat2(ArrayNode originalMessages) throws Exception {
        // 1. 读取本地全量知识库
        String fullKnowledge = readLocalFile(KNOWLEDGE_PATH);

        // 2. 构造包含全量知识的 messages
        ArrayNode newMessages = mapper.createArrayNode();

        // 提取原 System Prompt 并融合
        String baseSystemPrompt = originalMessages.get(0).get("content").asText();
        String enrichedSystemPrompt = baseSystemPrompt +
                "\n\n# 业务知识库规范\n" +
                "数据格式为：角色||摘要||答案。请根据用户身份检索对应的答案。\n" +
                "=== 知识库开始 ===\n" + fullKnowledge + "\n=== 知识库结束 ===";
        newMessages.addObject().put("role", "system").put("content", enrichedSystemPrompt);

        // 拷贝后续对话历史
        for (int i = 1; i < originalMessages.size(); i++) {
            newMessages.add(originalMessages.get(i));
        }

        System.out.println("📊 Context 总字符数: " + enrichedSystemPrompt.length());
        System.out.println("📊 Context : " + newMessages.toString());
        return execute(newMessages);
    }

    /**
     * 标准 chat 方法
     */
    public String chat(ArrayNode messages) throws Exception {
        return execute(messages);
    }

    private String execute(ArrayNode messages) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", "qwen-plus");
        root.put("temperature", 0.0);
        root.set("messages", messages);
        return sendRequest(QWEN_COMPATIBLE_URL, root);
    }

    private String sendRequest(String url, ObjectNode bodyNode) throws Exception {
        RequestBody body = RequestBody.create(
                mapper.writeValueAsString(bodyNode),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String raw = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP Error: " + response.code() + " - " + raw);
            }
            JsonNode root = mapper.readTree(raw);
            return root.path("choices").get(0).path("message").path("content").asText().trim();
        }
    }

    private static String readLocalFile(String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) throw new RuntimeException("文件不存在: " + path);
        String content = Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        return content;
    }
}