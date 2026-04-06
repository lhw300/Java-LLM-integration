package com.asrtts;

 
import okhttp3.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MiniTtsEngine {
    private static final Logger logger = LogManager.getLogger(MiniTtsEngine.class);
    // 替换为你的真实 API Key
    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

    public static void main(String[] args) throws IOException {
        OkHttpClient client = new OkHttpClient();
        
        //   "input": "Welcome! I'm your virtual assistant. How may I assist you?",
        //Hello, I'm sorry, I didn't catch that. Please tell me how I can help you.
        //I’m sorry, but I can't hear you. Please call back when you are available. Goodbye
        // 使用 Java 17 文本块构造 JSON
        String jsonPayload = """
            {
                "model": "gpt-4o-mini-tts",
                "voice": "nova",
                "input": "I’m sorry, but I can't hear you. Please call back when you are available. Goodbye",
                "response_format": "wav"
            }
            """;

        RequestBody body = RequestBody.create(
            jsonPayload, 
            MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer " + API_KEY)
            .post(body)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            // 直接获取字节流并保存
            byte[] audio = response.body().bytes();
            Files.write(Path.of("tts.wav"), audio);
            
            logger.debug("Audio saved to tts.wav - Succès !");
        }
    }
}