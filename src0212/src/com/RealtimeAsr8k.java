package com;
import okhttp3.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

public class RealtimeAsr8k {

  public static void main(String[] args) throws Exception {
    String apiKey = System.getenv("OPENAI_API_KEY");
    if (apiKey == null || apiKey.trim().isEmpty()) {
      throw new IllegalStateException("Please set OPENAI_API_KEY");
    }

    // 你的 8k A-law wav
    String wavPath = "C:\\busy_simple.wav"; // TODO 改这里
    WavG711 wav = readG711Wav(new File(wavPath));

    // A-law 常见 audioFormat=6
    if (wav.audioFormat != 6) {
      throw new IllegalArgumentException("Not A-law WAV. audioFormat=" + wav.audioFormat + " (expected 6)");
    }
    if (wav.sampleRate != 8000) throw new IllegalArgumentException("WAV sampleRate must be 8000.");
    if (wav.channels != 1) throw new IllegalArgumentException("WAV must be mono (1 channel).");

    OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS 长连接
        .build();

    Request request = new Request.Builder()
        .url("wss://api.openai.com/v1/realtime?intent=transcription")
        .addHeader("Authorization", "Bearer " + apiKey)
        .build();

    WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
      @Override
      public void onOpen(WebSocket webSocket, Response response) {
        System.out.println("WS opened");

        // 注意：模型名必须是 gpt-4o-transcribe（不要拼错）
        String sessionUpdate =
        	    "{"
        	        + "\"type\":\"session.update\","
        	        + "\"session\":{"
        	        +   "\"type\":\"transcription\","
        	        +   "\"audio\":{"
        	        +     "\"input\":{"
        	        +       "\"format\":{\"type\":\"audio/pcma\"},"
        	        +       "\"transcription\":{\"model\":\"gpt-4o-transcribe\"},"
        	        +       "\"turn_detection\":{"
        	        +         "\"type\":\"server_vad\","
        	        +         "\"threshold\":0.5,"
        	        +         "\"prefix_padding_ms\":300,"
        	        +         "\"silence_duration_ms\":200"
        	        +       "}"
        	        +     "}"
        	        +   "}"
        	        + "}"
        	    + "}";


        webSocket.send(sessionUpdate);

        // 开一个线程持续“RTP-like”发帧
        new Thread(() -> streamAlawFrames(webSocket, wav.data)).start();
      }

      @Override
      public void onMessage(WebSocket webSocket, String text) {
        // 这里会收到各种 server events（JSON）
        // 重点看：
        // - conversation.item.input_audio_transcription.delta
        // - conversation.item.input_audio_transcription.completed
        System.out.println(text);
      }

      @Override
      public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        System.err.println("WS failure: " + t.getMessage());
        t.printStackTrace();
        if (response != null) {
          System.err.println("HTTP " + response.code() + " " + response.message());
        }
      }

      @Override
      public void onClosing(WebSocket webSocket, int code, String reason) {
        System.out.println("WS closing: " + code + " " + reason);
        webSocket.close(code, reason);
      }

      @Override
      public void onClosed(WebSocket webSocket, int code, String reason) {
        System.out.println("WS closed: " + code + " " + reason);
      }
    });

    // 防止 main 退出（你也可以改成更优雅的 latch）
    Thread.sleep(60_000);
    ws.close(1000, "bye");
    client.dispatcher().executorService().shutdown();
  }

  // RTP-like：20ms 一帧
  // 8kHz * 20ms = 160 samples；G.711 每 sample 1 byte => 160 bytes/帧
  static void streamAlawFrames(WebSocket ws, byte[] alawData) {
    final int frameBytes = 160;
    final int sleepMs = 20;
System.out.println(" length "+ alawData.length);
    int offset = 0;
    while (offset + frameBytes <= alawData.length) {
      byte[] frame = new byte[frameBytes];
      System.arraycopy(alawData, offset, frame, 0, frameBytes);

      String b64 = Base64.getEncoder().encodeToString(frame);
      String append = "{\"type\":\"input_audio_buffer.append\",\"audio\":\"" + b64 + "\"}";
      ws.send(append);

      offset += frameBytes;

      try {
        Thread.sleep(sleepMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    // 如果你想模拟 Push-to-Talk：一段结束时 commit
    // ws.send("{\"type\":\"input_audio_buffer.commit\"}");

    System.out.println("Audio stream finished.");
  }

  // ---------------- WAV reader: G.711 A-law / μ-law ----------------

  static class WavG711 {
    final int sampleRate;
    final int channels;
    final int bitsPerSample;
    final int audioFormat; // 6=A-law, 7=μ-law
    final byte[] data;

    WavG711(int sampleRate, int channels, int bitsPerSample, int audioFormat, byte[] data) {
      this.sampleRate = sampleRate;
      this.channels = channels;
      this.bitsPerSample = bitsPerSample;
      this.audioFormat = audioFormat;
      this.data = data;
    }
  }
  static byte[] readFully(DataInputStream in, int len) throws IOException {
	    byte[] buf = new byte[len];
	    in.readFully(buf);   // Java 8 有
	    return buf;
	}

  static WavG711 readG711Wav(File f) throws IOException {
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
      //byte[] riff = in.readNBytes(4);
      byte[] riff=readFully(in, 4);
      if (!"RIFF".equals(new String(riff, StandardCharsets.US_ASCII))) throw new IOException("Not RIFF");
      readLE32(in); // file size
      byte[] wave = readFully(in, 4);
      if (!"WAVE".equals(new String(wave, StandardCharsets.US_ASCII))) throw new IOException("Not WAVE");

      int sampleRate = -1, channels = -1, bitsPerSample = -1, audioFormat = -1;
      byte[] data = null;

      while (true) {
        byte[] chunkIdBytes = readFully(in, 4);
        if (chunkIdBytes.length < 4) break;
        String chunkId = new String(chunkIdBytes, StandardCharsets.US_ASCII);
        int chunkSize = readLE32(in);

        if ("fmt ".equals(chunkId)) {
          audioFormat = readLE16(in); // 6=A-law, 7=μ-law
          channels = readLE16(in);
          sampleRate = readLE32(in);
          readLE32(in); // byteRate
          readLE16(in); // blockAlign
          bitsPerSample = readLE16(in);
          int remaining = chunkSize - 16;
          if (remaining > 0) in.skipBytes(remaining);
        } else if ("data".equals(chunkId)) {
        	 ByteArrayOutputStream baos = new ByteArrayOutputStream();
        	    byte[] buf = new byte[4096];
        	    int n;
        	    while ((n = in.read(buf)) != -1) {
        	        baos.write(buf, 0, n);
        	    }
        	    data = baos.toByteArray();
        	    break;
           
        } else {
          in.skipBytes(chunkSize);
        }

        // word alignment padding
        if ((chunkSize & 1) == 1) in.skipBytes(1);
      }

      if (data == null) throw new IOException("No data chunk found");
      return new WavG711(sampleRate, channels, bitsPerSample, audioFormat, data);
    }
  }

  static int readLE16(DataInputStream in) throws IOException {
    int b0 = in.readUnsignedByte();
    int b1 = in.readUnsignedByte();
    return (b1 << 8) | b0;
  }

  static int readLE32(DataInputStream in) throws IOException {
    int b0 = in.readUnsignedByte();
    int b1 = in.readUnsignedByte();
    int b2 = in.readUnsignedByte();
    int b3 = in.readUnsignedByte();
    return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
  }
}
