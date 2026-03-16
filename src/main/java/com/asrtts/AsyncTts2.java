package com.asrtts;

import okhttp3.*;
import okio.ByteString;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 集成了并发压测逻辑的 TTS 客户端
 */
public class AsyncTts2 {

    private WebSocket webSocket;
    private final OkHttpClient client;
    private Consumer<byte[]> pcmDataHandler;
    private CountDownLatch latch; // 用于控制并发等待

    private FileOutputStream fos;
    private int totalDataSize = 0;

    public AsyncTts2() {
        // 设置超时时间为 0，代表 WebSocket 长连接永不超时
        this.client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 核心连接方法
     * @param filePath 音频保存路径
     * @param pcmHandler 处理音频数据的回调
     * @param latch 用于同步并发任务的计数器
     */
    public void connect(String filePath, Consumer<byte[]> pcmHandler, CountDownLatch latch) {
        this.pcmDataHandler = pcmHandler;
        this.latch = latch;
        this.totalDataSize = 0;

        try {
            this.fos = new FileOutputStream(filePath);
            this.fos.write(new byte[44]); // 预留 WAV 头
        } catch (IOException e) {
            System.err.println("文件初始化失败: " + e.getMessage());
        }

        // 这里的 sid=6, samplerate=8000 必须和你的 Python 后端配置一致
        String url = "ws://localhost:8000/tts?sid=6&speed=0.95&samplerate=8000&split=true";
        Request request = new Request.Builder().url(url).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                // 连接成功
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                byte[] data = bytes.toByteArray();
                if (fos != null) {
                    try {
                        fos.write(data);
                        totalDataSize += data.length;
                    } catch (IOException ignored) {}
                }
                if (pcmDataHandler != null) pcmDataHandler.accept(data);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                // 打印收到的原始信号
                System.out.println("<<< 收到服务端信号: " + text);

                // ★ 修改点：根据你提供的日志，后端使用 progress:1.0 来表示任务合成结束
                if (text.contains("\"progress\":1.0") || text.contains("\"progress\": 1.0")) {
                    completeTask("合成完毕 (匹配到 progress:1.0)");
                }
                // 备用方案：如果收到包含统计信息的消息也视为结束
                else if (text.contains("\"duration\"") && text.contains("\"elapsed\"")) {
                    completeTask("合成完毕 (匹配到统计信息)");
                }
                else if (text.contains("\"error\"")) {
                    completeTask("服务端报错: " + text);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("!!! 连接异常: " + t.getMessage());
                completeTask("连接失败");
            }

            // 内部统一收尾逻辑
            private void completeTask(String reason) {
                try {
                    if (fos != null) {
                        writeWavHeader(fos, totalDataSize);
                        fos.close();
                        fos = null;
                        System.out.println(">>> 任务状态: " + reason + " | 文件保存成功");
                    }
                } catch (IOException ignored) {}

                // ★ 释放 Latch，让 main 线程继续执行
                if (latch != null) {
                    latch.countDown();
                }
            }
        });
    }

    public void sendText(String text) {
        if (webSocket != null) {
            webSocket.send(text);
        }
    }

    /**
     * WAV 文件头补齐逻辑
     */
    private void writeWavHeader(FileOutputStream out, int pcmLen) throws IOException {
        long totalDataLen = pcmLen + 36;
        long sampleRate = 8000;
        int channels = 1;
        long byteRate = sampleRate * 2;

        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = 2; header[33] = 0;
        header[34] = 16; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmLen & 0xff);
        header[41] = (byte) ((pcmLen >> 8) & 0xff);
        header[42] = (byte) ((pcmLen >> 16) & 0xff);
        header[43] = (byte) ((pcmLen >> 24) & 0xff);

        FileChannel channel = out.getChannel();
        channel.position(0);
        out.write(header);
    }

    // ==========================================
    // 压测主逻辑
    // ==========================================
    public static void main(String[] args) throws InterruptedException {
        // ★ 这里设置你想要的并发数，比如 5
        final int TASK_COUNT = 5;
        final String TEXT = "中国在大模型领域的快速发展已使其成为全球发布大模型数量最多的国家。";

        CountDownLatch latch = new CountDownLatch(TASK_COUNT);
        long start = System.currentTimeMillis();

        System.out.println("--- 压测启动，并发数: " + TASK_COUNT + " ---");

        for (int i = 0; i < TASK_COUNT; i++) {
            final int index = i;
            AsyncTts2 tts = new AsyncTts2();

            // 为每个任务分配不同的保存文件名
            String path = "C:\\tts_res_" + index + ".wav";

            tts.connect(path, pcm -> {
                // 此处可以监控音频流到达情况
            }, latch);

            // 预留握手时间，避免请求重叠
            Thread.sleep(300);

            System.out.println(">>> 发送任务 [" + index + "]");
            tts.sendText(TEXT);
        }

        // 最长等待 60 秒，如果 60 秒还没跑完，说明肯定出 bug 了
        boolean finished = latch.await(60, TimeUnit.SECONDS);
        long end = System.currentTimeMillis();

        if (finished) {
            System.out.println("\nSUCCESS: 所有任务已成功返回信号！");
            System.out.println("总耗时: " + (end - start) + " ms");
            System.out.println("平均响应: " + ((end - start) / TASK_COUNT) + " ms/路");
        } else {
            System.err.println("\nERROR: 任务超时！请根据控制台打印的 '收到服务端信号' 排查原因。");
        }

        System.exit(0);
    }
}