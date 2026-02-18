package com;
 

	import java.io.*;
	import java.nio.charset.StandardCharsets;

	import java.io.*;
	import java.nio.charset.StandardCharsets;

	public class TestReadWav {

	    // ---------- 数据结构 ----------
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

	    // ---------- main：只测试 WAV 读取 ----------
	    public static void main(String[] args) throws Exception {
	        String wavPath = "C:\\busy_simple.wav"; // TODO 改成你的 wav

	        WavG711 wav = readG711Wav(new File(wavPath));

	        System.out.println("====== WAV INFO ======");
	        System.out.println("sampleRate     = " + wav.sampleRate);
	        System.out.println("channels       = " + wav.channels);
	        System.out.println("bitsPerSample  = " + wav.bitsPerSample);
	        System.out.println("audioFormat    = " + wav.audioFormat
	                + (wav.audioFormat == 6 ? " (A-law)" :
	                   wav.audioFormat == 7 ? " (μ-law)" : " (UNKNOWN)"));
	        System.out.println("data bytes     = " + wav.data.length);
	        System.out.println("======================");

	        // 简单 sanity check：前 16 个字节
	        System.out.print("first 16 data bytes: ");
	        for (int i = 0; i < Math.min(16, wav.data.length); i++) {
	            System.out.printf("%02X ", wav.data[i]);
	        }
	        System.out.println();
	    }

	    // ---------- WAV 读取实现（Java 8 安全版） ----------

	    static WavG711 readG711Wav(File f) throws IOException {
	        try (DataInputStream in = new DataInputStream(
	                new BufferedInputStream(new FileInputStream(f)))) {

	            // RIFF
	            String riff = readAscii(in, 4);
	            if (!"RIFF".equals(riff)) throw new IOException("Not RIFF: " + riff);
	            readLE32u(in); // file size

	            String wave = readAscii(in, 4);
	            if (!"WAVE".equals(wave)) throw new IOException("Not WAVE: " + wave);

	            int sampleRate = -1, channels = -1, bitsPerSample = -1, audioFormat = -1;
	            byte[] data = null;

	            // 逐 chunk 读取
	            while (true) {
	                String chunkId;
	                long chunkSize;

	                try {
	                    chunkId = readAscii(in, 4);
	                } catch (EOFException eof) {
	                    break;
	                }

	                chunkSize = readLE32u(in);

	                System.out.println("chunk: " + chunkId + " size=" + chunkSize);

	                if ("fmt ".equals(chunkId)) {
	                    audioFormat = readLE16(in); // 6=A-law, 7=μ-law
	                    channels = readLE16(in);
	                    sampleRate = (int) readLE32u(in);
	                    readLE32u(in); // byteRate
	                    readLE16(in);  // blockAlign
	                    bitsPerSample = readLE16(in);

	                    long remain = chunkSize - 16;
	                    if (remain > 0) skipFully(in, remain);

	                } else if ("data".equals(chunkId)) {
	                	// 忽略 chunkSize，直接读到 EOF
	                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
	                    byte[] buf = new byte[4096];
	                    int n;
	                    while ((n = in.read(buf)) != -1) {
	                        baos.write(buf, 0, n);
	                    }
	                    data = baos.toByteArray();

	                } else {
	                    skipFully(in, chunkSize);
	                }

	                // word 对齐
	                if ((chunkSize & 1) == 1) skipFully(in, 1);

	                if (data != null && audioFormat != -1) {
	                    return new WavG711(sampleRate, channels, bitsPerSample, audioFormat, data);
	                }
	            }

	            throw new IOException("Missing fmt or data chunk");
	        }
	    }

	    // ---------- 工具方法（Java 8 兼容） ----------

	    static byte[] readFully(DataInputStream in, int len) throws IOException {
	        byte[] buf = new byte[len];
	        in.readFully(buf);
	        return buf;
	    }

	    static void skipFully(DataInputStream in, long n) throws IOException {
	        long left = n;
	        while (left > 0) {
	            long s = in.skip(left);
	            if (s <= 0) {
	                if (in.read() == -1) throw new EOFException("EOF while skipping");
	                s = 1;
	            }
	            left -= s;
	        }
	    }

	    static int readLE16(DataInputStream in) throws IOException {
	        int b0 = in.readUnsignedByte();
	        int b1 = in.readUnsignedByte();
	        return (b1 << 8) | b0;
	    }

	    static long readLE32u(DataInputStream in) throws IOException {
	        long b0 = in.readUnsignedByte();
	        long b1 = in.readUnsignedByte();
	        long b2 = in.readUnsignedByte();
	        long b3 = in.readUnsignedByte();
	        return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
	    }

	    static String readAscii(DataInputStream in, int len) throws IOException {
	        byte[] b = readFully(in, len);
	        return new String(b, StandardCharsets.US_ASCII);
	    }
	}
