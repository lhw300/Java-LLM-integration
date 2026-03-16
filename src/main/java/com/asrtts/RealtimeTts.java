package com.asrtts;


	import okhttp3.*;

	import java.io.*;
	import java.nio.charset.StandardCharsets;
	import java.util.Base64;
	import java.util.concurrent.CountDownLatch;
	import java.util.concurrent.TimeUnit;
	public class RealtimeTts {

	    public static void main(String[] args) throws Exception {
	        String apiKey = System.getenv("OPENAI_API_KEY");
	        if (apiKey == null || apiKey.trim().isEmpty()) {
	            throw new IllegalStateException("Please set OPENAI_API_KEY");
	        }

	        final String model = "gpt-realtime"; // 或 gpt-realtime-mini
	       // final String       text="您好，我暂时没有听到您的声音。请直接说出您的问题";
	        final String  text="Welcome! I'm your virtual assistant. How may I assist you?";

	        OkHttpClient client = new OkHttpClient.Builder()
	                .readTimeout(0, TimeUnit.MILLISECONDS)
	                .build();

	        Request request = new Request.Builder()
	                .url("wss://api.openai.com/v1/realtime?model=" + model)
	                .addHeader("Authorization", "Bearer " + apiKey)
	                .build();

	        CountDownLatch done = new CountDownLatch(1);

	        client.newWebSocket(request, new WebSocketListener() {

	            // 收集输出音频（这里收的是 8k A-law 原始字节）
	            final ByteArrayOutputStream pcmaBytes = new ByteArrayOutputStream();

	            @Override
	            public void onOpen(WebSocket ws, Response resp) {
	                System.out.println("WS opened");

	                // 1) session.update：声明这是 realtime 会话，并设置输出音频格式(PCMA) + voice
	                // session.type = "realtime" 是必须字段之一（你之前报错 Missing session.type 就是这个）:contentReference[oaicite:3]{index=3}
	                String sessionUpdate =
	                	    "{"
	                	  + "\"type\":\"session.update\","
	                	  + "\"session\":{"
	                	  +   "\"type\":\"realtime\","
	                	  +   "\"output_modalities\":[\"audio\"],"
	                	  +  "\"instructions\":\"You are a text-to-speech engine. Output audio ONLY. Speak ONLY the exact input text verbatim. No preface, no acknowledgements, no extra words.\","
	                	  +   "\"audio\":{"
	                	  +     "\"output\":{"
	                	  +       "\"format\":{\"type\":\"audio/pcma\"},"
	                	  +       "\"voice\":\"marin\""
	                	  +     "}"
	                	  +   "}"
	                	  + "}"
	                	  + "}";
	                	ws.send(sessionUpdate);



	                ws.send(sessionUpdate);

	                // 2) conversation.item.create：把要说的话作为用户输入塞进会话上下文
	                String itemCreate =
	                        "{"
	                                + "\"type\":\"conversation.item.create\","
	                                + "\"item\":{"
	                                +   "\"type\":\"message\","
	                                +   "\"role\":\"user\","
	                                +   "\"content\":[{"
	                                +     "\"type\":\"input_text\","
	                                +     "\"text\":\"" + escapeJson(text) + "\""
	                                +   "}]"
	                                + "}"
	                                + "}";

	              //  ws.send(itemCreate);

	                // 3) response.create：触发模型生成“音频输出”
	                // 这里也可以在 response 级别再指定 audio.output.format / voice 覆盖 session。:contentReference[oaicite:4]{index=4}
	                String responseCreate =
	                	    "{"
	                	  + "\"type\":\"response.create\","
	                	  + "\"response\":{"
	                	  +   "\"conversation\":\"none\","
	                	  +   "\"output_modalities\":[\"audio\"],"
	                	  +   "\"instructions\":\"You are a TTS engine,Just Read the input text verbatim. No preface, no additions.\","
	                	  +   "\"input\":[{"
	                	  +     "\"type\":\"message\","
	                	  +     "\"role\":\"user\","
	                	  +     "\"content\":[{"
	                	  +       "\"type\":\"input_text\","
	                	  +       "\"text\":\"" + escapeJson(text) + "\""
	                	  +     "}]"
	                	  +   "}]"
	                	  + "}"
	                	  + "}";




	                ws.send(responseCreate);
	            }

	            @Override
	            public void onMessage(WebSocket ws, String textMsg) {
	                // 关键事件：
	                // - response.output_audio.delta: base64 音频分片:contentReference[oaicite:5]{index=5}
	                // - response.output_audio.done: 音频结束:contentReference[oaicite:6]{index=6}

	                String type = extractJsonString(textMsg, "type");
	                System.out.println("type=" + type + " full=" + textMsg);
	                if (type == null) {
	                    // 不是我们能简单解析的 JSON，就直接打印
	                    System.out.println(textMsg);
	                    return;
	                }

	                if ("response.output_audio.delta".equals(type)) {
	                    String deltaB64 = extractJsonString(textMsg, "delta");
	                    if (deltaB64 != null) {
	                        byte[] chunk = Base64.getDecoder().decode(deltaB64);
	                        pcmaBytes.write(chunk, 0, chunk.length);
	                    }
	                } else if ("response.output_audio.done".equals(type) ) {
	                    try {
	                        byte[] pcma = pcmaBytes.toByteArray();

	                        // 保存：8k A-law wav（format=6）
	                        File out = new File("realtime_tts_8k_pcma.wav");
	                        writeWavG711Alaw(out, pcma, 8000, 1);

	                        System.out.println("Saved: " + out.getAbsolutePath() + "  bytes=" + pcma.length);
	                    } catch (Exception e) {
	                        e.printStackTrace();
	                    } finally {
	                        done.countDown();
	                        ws.close(1000, "bye");
	                    }
	                } else if ("error".equals(type)) {
	                    System.err.println("Server error: " + textMsg);
	                    done.countDown();
	                    ws.close(1000, "bye");
	                } else {
	                    // 你也可以把其它事件打印出来观察
	                    // System.out.println(textMsg);
	                }
	            }

	            @Override
	            public void onFailure(WebSocket ws, Throwable t, Response response) {
	                System.err.println("WS failure: " + t.getMessage());
	                t.printStackTrace();
	                done.countDown();
	            }

	            @Override
	            public void onClosed(WebSocket ws, int code, String reason) {
	                System.out.println("WS closed: " + code + " " + reason);
	            }
	        });

	        done.await(30, TimeUnit.SECONDS);
	        client.dispatcher().executorService().shutdown();
	        System.out.println("Done.");
	    }

	    // ----------------- 超轻量 JSON 字段提取（无额外依赖，够 demo 用） -----------------
	    // 只支持提取形如 "key":"value" 的字符串字段（不处理转义很复杂的情况）
	    static String extractJsonString(String json, String key) {
	        String pattern = "\"" + key + "\"";
	        int k = json.indexOf(pattern);
	        if (k < 0) return null;
	        int colon = json.indexOf(':', k + pattern.length());
	        if (colon < 0) return null;
	        int q1 = json.indexOf('"', colon + 1);
	        if (q1 < 0) return null;
	        int q2 = json.indexOf('"', q1 + 1);
	        if (q2 < 0) return null;
	        return json.substring(q1 + 1, q2);
	    }

	    static String escapeJson(String s) {
	        return s.replace("\\", "\\\\")
	                .replace("\"", "\\\"")
	                .replace("\r", "\\r")
	                .replace("\n", "\\n");
	    }

	    // ----------------- 写 8k A-law WAV（format tag=6） -----------------
	    static void writeWavG711Alaw(File outWav, byte[] alawData, int sampleRate, int channels) throws IOException {
	        int bitsPerSample = 8;         // G.711 每采样 8bit
	        int byteRate = sampleRate * channels * bitsPerSample / 8;
	        int blockAlign = channels * bitsPerSample / 8;
	        int dataLen = alawData.length;
	        int riffChunkSize = 36 + dataLen;

	        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outWav))) {
	            os.write("RIFF".getBytes(StandardCharsets.US_ASCII));
	            writeLE32(os, riffChunkSize);
	            os.write("WAVE".getBytes(StandardCharsets.US_ASCII));

	            os.write("fmt ".getBytes(StandardCharsets.US_ASCII));
	            writeLE32(os, 16);          // PCM-like fmt chunk size
	            writeLE16(os, 6);           // 6 = A-law
	            writeLE16(os, channels);
	            writeLE32(os, sampleRate);
	            writeLE32(os, byteRate);
	            writeLE16(os, blockAlign);
	            writeLE16(os, bitsPerSample);

	            os.write("data".getBytes(StandardCharsets.US_ASCII));
	            writeLE32(os, dataLen);
	            os.write(alawData);
	        }
	    }

	    static void writeLE16(OutputStream os, int v) throws IOException {
	        os.write(v & 0xFF);
	        os.write((v >>> 8) & 0xFF);
	    }

	    static void writeLE32(OutputStream os, int v) throws IOException {
	        os.write(v & 0xFF);
	        os.write((v >>> 8) & 0xFF);
	        os.write((v >>> 16) & 0xFF);
	        os.write((v >>> 24) & 0xFF);
	    }
	}
