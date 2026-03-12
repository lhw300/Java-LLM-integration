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

public class OpenAiFinalAsk {
    // --- 核心配置 ---
    private static final String apiKey = System.getenv("OPENAI_API_KEY");
   // private static final String apiKey = "你的_OPENAI_API_KEY";
    private static final String chatModel = "gpt-4o-mini"; // 建议先用 mini 测速度
    private static final String TEST_JSON_PATH = "c:\\test.json.txt";

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("📂 已监控本地文件: " + TEST_JSON_PATH);
        System.out.println("⌨️  修改 JSON 后，回到这里按 [回车键] 立即发送请求 (输入 'exit' 退出)...");

        while (true) {
            System.out.print("\n[等待指令] > ");
            String input = scanner.nextLine();
            if ("exit".equalsIgnoreCase(input)) break;

            try {
                // 1. 读取本地 JSON 内容
                File file = new File(TEST_JSON_PATH);
                if (!file.exists()) {
                    System.err.println("❌ 错误：在 C 盘根目录找不到 test.json 文件！");
                    continue;
                }

                String content = new String(Files.readAllBytes(Paths.get(TEST_JSON_PATH)), StandardCharsets.UTF_8);
                JsonNode rootInput = mapper.readTree(content);

                // 校验 JSON 结构
                if (!rootInput.has("messages")) {
                    System.err.println("❌ 错误：JSON 文件中必须包含 \"messages\" 数组。");
                    continue;
                }

                ArrayNode messages = (ArrayNode) rootInput.get("messages");

                // 2. 触发发送
                long start = System.currentTimeMillis();
                System.out.println("🚀 正在读取并发送请求至 OpenAI...");

                String aiResponse = new OpenAiFinalAsk().chat(messages);

                long duration = System.currentTimeMillis() - start;

                // 3. 输出结果与性能数据
                System.out.println("\n==================== 测试结果 ====================");
                System.out.println("⏱️  总耗时: " + duration + " ms");
                System.out.println("🤖 AI 回复内容:");
                System.out.println("--------------------------------------------------");
                System.out.println(aiResponse);
                System.out.println("--------------------------------------------------");

            } catch (Exception e) {
                System.err.println("💥 运行时异常: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 按照你提供的逻辑封装 chat 方法
     */
    public String chat(ArrayNode messages) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", chatModel);
        root.put("temperature", 0.0);
        root.set("messages", messages);

        return sendRequest("https://api.openai.com/v1/chat/completions", root);
    }

    /**
     * 内部通用的 HTTP 请求方法 (基于你提供的代码实现)
     */
    private String sendRequest(String url, ObjectNode bodyNode) throws Exception {
        // 打印发送的 JSON 详情（美化后的格式）
        String bodyJsonPretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bodyNode);
        System.out.println("🤖 [openAI] 发送本地请求: \n" + bodyJsonPretty);

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
            if (root.has("choices")) {
                // 提取具体的回答内容
                return root.path("choices").get(0).path("message").path("content").asText().trim();
            }
            return raw; // 备用：返回原始响应
        }
    }
}