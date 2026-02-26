package com;



import okhttp3.*;
import okio.ByteString;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

public class LocalSpeechSynthesizer {

    private final OkHttpClient client;
    private final LocalSpeechSynthesizerListener listener;
    private WebSocket webSocket;
    private CountDownLatch latch;

    // --- 模仿阿里云的配置参数 ---
    private String serverUrl = "ws://localhost:8000/tts"; // 你的 Python 后端地址
    private int sampleRate = 16000;
    private String voice = "0";      // 对应后端的 sid (发音人)
    private float speed = 1.0f;      // 对应后端的 speed (语速)
    private String text = "";        // 待合成的文本

    /**
     * 构造函数，对应 Aliyun 的 new SpeechSynthesizer(client, listener2)
     */
    public LocalSpeechSynthesizer(OkHttpClient client, LocalSpeechSynthesizerListener listener) {
        // 如果外部没传 client，自己建一个带心跳保活的
        this.client = client != null ? client : new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        this.listener = listener;
    }

    // --- 模仿阿里云的 Setter 方法 ---
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
    // 我们的后端目前用的是 sid (数字)，外部如果传的是字符串，这里可以简单解析
    public void setVoice(String voice) { this.voice = voice; }
    public void setSpeechRate(float speed) { this.speed = speed; }
    public void setText(String text) { this.text = text; }
    public void setServerUrl(String url) { this.serverUrl = url; } // 扩展方法

    // 这些方法我们后端可能暂时用不到，但为了兼容你的旧代码，留着空实现
    public void setAppKey(String appKey) {}
    public void setFormat(String format) {}
    public void setVolume(int volume) {}

    /**
     * 启动合成任务 (核心逻辑全在这里)
     */
    public void start() {
        if (text == null || text.trim().isEmpty()) {
            if (listener != null) listener.onFail("文本不能为空");
            return;
        }

        latch = new CountDownLatch(1);

        // 动态拼接后端的 URL 参数
        String url = String.format("%s?sid=%s&speed=%s&samplerate=%d&split=true",
                serverUrl, voice, speed, sampleRate);

        Request request = new Request.Builder().url(url).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                // 连接建立后，立刻发送文本
                webSocket.send(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                // 收到二进制流，触发 onMessage 回调
                if (listener != null) {
                    listener.onMessage(bytes.toByteArray());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String jsonText) {
                // 收到后端的 JSON 状态，判断是否完成
                if (jsonText.contains("\"finished\":true") || jsonText.contains("\"finished\": true")) {
                    if (listener != null) {
                        listener.onComplete();
                    }
                    if (latch != null) latch.countDown();
                    webSocket.close(1000, "done");
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String error = t != null ? t.getMessage() : "未知网络错误";
                if (listener != null) {
                    listener.onFail(error);
                }
                if (latch != null) latch.countDown();
            }
        });
    }

    /**
     * 阻塞当前线程，直到合成完毕 (非常实用，很多 SDK 都有这个功能)
     */
    public void waitForComplete() {
        try {
            if (latch != null) latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}