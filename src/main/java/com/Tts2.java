package com;

 

	import okhttp3.*;
	import javax.sound.sampled.*;
	import java.io.*;

	/**
	 * 优化版 TTS - 直接请求 PCM 格式，减少转换步骤
	 * OpenAI 支持的格式: mp3, opus, aac, flac, wav, pcm
	 * 没有直接的 A-law 格式，所以我们：
	 * 1. 请求 pcm 格式（最快，无需解码）
	 * 2. 转换为 A-law
	 */
	public class Tts2 {
 
	    /* test 4 3 6*/
 		/* test8 */
 
 
	    private static final String API_KEY = System.getenv("OPENAI_API_KEY");
	    private static final String URL = "https://api.openai.com/v1/audio/speech";
	    private static final OkHttpClient client = new OkHttpClient();
	    
	    public static void main(String[] args) {
	    	Tts2 engine = new Tts2();
	        
	        // 直接生成 A-law WAV
	        engine.generateAlaw("你好世界，这是测试！", "output_alaw.wav", "nova");
	        
	        // 保持运行
	        try {
	            Thread.sleep(10000);
	        } catch (InterruptedException e) {
	            e.printStackTrace();
	        }
	    }
	    
	    /**
	     * 生成 A-law 音频（优化版：直接请求 PCM）
	     */
	    public void generateAlaw(String text, String outputPath, String voice) {
	        // 关键：直接请求 pcm 格式，无需 MP3 解码！
	        String json = """
	            {
	                "model": "tts-1",
	                "input": "%s",
	                "voice": "%s",
	                "response_format": "pcm"
	            }
	            """.formatted(text, voice);
	        
	        RequestBody body = RequestBody.create(json, MediaType.get("application/json"));
	        Request request = new Request.Builder()
	                .url(URL)
	                .header("Authorization", "Bearer " + API_KEY)
	                .post(body)
	                .build();
	        
	        client.newCall(request).enqueue(new Callback() {
	            @Override
	            public void onFailure(Call call, IOException e) {
	                System.err.println("❌ 请求失败: " + e.getMessage());
	                onError(e.getMessage());
	            }
	            
	            @Override
	            public void onResponse(Call call, Response response) throws IOException {
	                if (!response.isSuccessful()) {
	                    System.err.println("❌ 服务器错误: " + response.code());
	                    onError("HTTP " + response.code());
	                    return;
	                }
	                
	                System.out.println("🎵 开始接收 PCM 音频流...");
	                
	                try (InputStream inputStream = response.body().byteStream()) {
	                    // OpenAI 的 PCM 格式：24000 Hz, 16-bit, 单声道, 小端
	                    // 直接读取并转换为 A-law
	                	convertPcmToAlaw3(inputStream, outputPath);
	                    
	                    System.out.println("✅ A-law 文件保存完成: " + outputPath);
	                    onComplete(outputPath);
	                    
	                } catch (Exception e) {
	                    System.err.println("❌ 转换失败: " + e.getMessage());
	                    onError(e.getMessage());
	                }
	            }
	        });
	    }
	    
	    /**
	     * 将 OpenAI 的 PCM 流转换为 A-law WAV
	     * OpenAI PCM: 24000 Hz, 16-bit, mono
	     * 目标: 8000 Hz, A-law, mono
	     */
	    private void convertPcmToAlaw(InputStream pcmStream, String outputPath) throws Exception {
	        // OpenAI 的 PCM 格式
	        AudioFormat sourcePcmFormat = new AudioFormat(
	            AudioFormat.Encoding.PCM_SIGNED,
	            24000,  // 24kHz
	            16,     // 16-bit
	            1,      // 单声道
	            2,      // 帧大小 (16bit = 2 bytes)
	            24000,  // 帧率
	            false   // 小端
	        );
	        
	        // 读取所有 PCM 数据
	        ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
	        byte[] buffer = new byte[8192];
	        int bytesRead;
	        int chunkNumber = 0;
	        
	        while ((bytesRead = pcmStream.read(buffer)) != -1) {
	            pcmBuffer.write(buffer, 0, bytesRead);
	            onPcmChunk(buffer, bytesRead, ++chunkNumber);
	        }
	        
	        byte[] pcmData = pcmBuffer.toByteArray();
	        
	        // 创建 AudioInputStream
	        ByteArrayInputStream bais = new ByteArrayInputStream(pcmData);
	        AudioInputStream pcm24kStream = new AudioInputStream(
	            bais, sourcePcmFormat, pcmData.length / sourcePcmFormat.getFrameSize()
	        );
	        
	        // 降采样到 8000 Hz
	        AudioFormat pcm8kFormat = new AudioFormat(
	            AudioFormat.Encoding.PCM_SIGNED,
	            8000,   // 8kHz
	            16,     // 16-bit
	            1,      // 单声道
	            2,      // 帧大小
	            8000,   // 帧率
	            false   // 小端
	        );
	        
	        AudioInputStream pcm8kStream = AudioSystem.getAudioInputStream(pcm8kFormat, pcm24kStream);
	        
	        // 转换为 A-law
	        AudioFormat alawFormat = new AudioFormat(
	            AudioFormat.Encoding.ALAW,
	            8000,   // 8kHz
	            8,      // 8-bit
	            1,      // 单声道
	            1,      // 帧大小
	            8000,   // 帧率
	            false   // 小端
	        );
	        
	        AudioInputStream alawStream = AudioSystem.getAudioInputStream(alawFormat, pcm8kStream);
	        
	        // 读取 A-law 数据并触发回调
	        ByteArrayOutputStream alawBuffer = new ByteArrayOutputStream();
	        byte[] alawChunkBuffer = new byte[1024];
	        int alawBytesRead;
	        int alawChunkNumber = 0;
	        
	        while ((alawBytesRead = alawStream.read(alawChunkBuffer)) != -1) {
	            byte[] chunk = new byte[alawBytesRead];
	            System.arraycopy(alawChunkBuffer, 0, chunk, 0, alawBytesRead);
	            
	            onAlawChunk(chunk, ++alawChunkNumber);
	            alawBuffer.write(alawChunkBuffer, 0, alawBytesRead);
	        }
	        
	        // 保存 WAV 文件
	        byte[] alawData = alawBuffer.toByteArray();
	        try (ByteArrayInputStream alawBais = new ByteArrayInputStream(alawData);
	             AudioInputStream ais = new AudioInputStream(alawBais, alawFormat, alawData.length)) {
	            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, new File(outputPath));
	        }
	        
	        // 清理
	        alawStream.close();
	        pcm8kStream.close();
	        pcm24kStream.close();
	    }
	    
	    // ==================== 回调方法 ====================
	    
	    /**
	     * 当接收到 PCM 数据块时调用
	     */
	    private void onPcmChunk(byte[] data, int length, int chunkNumber) {
	        System.out.println("📦 收到 PCM 块 #" + chunkNumber + ": " + length + " 字节");
	    }
	    
	    /**
	     * 当接收到 A-law 音频块时调用
	     */
	    private void onAlawChunk(byte[] alawData, int chunkNumber) {
	        System.out.println("🎵 A-law 块 #" + chunkNumber + ": " + alawData.length + " 字节");
	        // 可以在这里：
	        // - 发送到电话系统
	        // - 实时播放
	        // - 发送到网络
	    }
	    
	    /**
	     * 当完成时调用
	     */
	    private void onComplete(String outputPath) {
	        System.out.println("🎉 处理完成: " + outputPath);
	    }
	    
	    /**
	     * 当发生错误时调用
	     */
	    private void onError(String error) {
	        System.err.println("💥 错误: " + error);
	    }
	    
	    /**
	     * 改进的 convertPcmToAlaw 函数
	     * 使用线性插值重采样，减少杂音
	     * 
	     * 直接替换 OptimizedStreamTtsEngine.java 中的 convertPcmToAlaw 方法
	     */

	    // ============ 方案 1: 线性插值重采样 ============

	    /**
	     * 改进版：使用线性插值降采样（减少杂音）
	     */
	    private void convertPcmToAlaw2(InputStream pcmStream, String outputPath) throws Exception {
	        // 1. 读取所有 PCM 数据
	        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
	        byte[] chunk = new byte[8192];
	        int bytesRead;
	        int chunkNumber = 0;
	        
	        while ((bytesRead = pcmStream.read(chunk)) != -1) {
	            buffer.write(chunk, 0, bytesRead);
	            onPcmChunk(chunk, bytesRead, ++chunkNumber);
	        }
	        
	        byte[] pcm24kData = buffer.toByteArray();
	        
	        // 2. 转换为 short 数组（16-bit samples）
	        short[] samples24k = new short[pcm24kData.length / 2];
	        for (int i = 0; i < samples24k.length; i++) {
	            int low = pcm24kData[i * 2] & 0xFF;
	            int high = pcm24kData[i * 2 + 1] & 0xFF;
	            samples24k[i] = (short) ((high << 8) | low);
	        }
	        
	        // 3. 线性插值降采样 24kHz -> 8kHz
	        double ratio = 24000.0 / 8000.0; // 3.0
	        int outputLength = (int) (samples24k.length / ratio);
	        short[] samples8k = new short[outputLength];
	        
	        for (int i = 0; i < outputLength; i++) {
	            double srcPos = i * ratio;
	            int srcIndex = (int) srcPos;
	            double fraction = srcPos - srcIndex;
	            
	            if (srcIndex + 1 < samples24k.length) {
	                // 线性插值
	                double sample = samples24k[srcIndex] * (1.0 - fraction) + 
	                               samples24k[srcIndex + 1] * fraction;
	                samples8k[i] = (short) Math.round(sample);
	            } else {
	                samples8k[i] = samples24k[srcIndex];
	            }
	        }
	        
	        // 4. 转换为 A-law
	        byte[] alawData = new byte[samples8k.length];
	        int alawChunkNumber = 0;
	        
	        for (int i = 0; i < samples8k.length; i++) {
	            alawData[i] = linearToAlaw(samples8k[i]);
	            
	            // 每 1024 字节触发一次回调
	            if ((i + 1) % 1024 == 0 || i == samples8k.length - 1) {
	                int chunkSize = Math.min(1024, samples8k.length - (alawChunkNumber * 1024));
	                byte[] alawChunk = new byte[chunkSize];
	                System.arraycopy(alawData, alawChunkNumber * 1024, alawChunk, 0, chunkSize);
	                onAlawChunk(alawChunk, ++alawChunkNumber);
	            }
	        }
	        
	        // 5. 保存 WAV
	        AudioFormat alawFormat = new AudioFormat(
	            AudioFormat.Encoding.ALAW, 8000, 8, 1, 1, 8000, false
	        );
	        
	        try (ByteArrayInputStream bais = new ByteArrayInputStream(alawData);
	             AudioInputStream ais = new AudioInputStream(bais, alawFormat, alawData.length)) {
	            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, new File(outputPath));
	        }
	    }

	    /**
	     * 16-bit PCM 转 A-law（优化的 G.711 算法）
	     */
	    private byte linearToAlaw(short pcm) {
	        int mask;
	        int seg;
	        
	        // 缩放到 13-bit
	        int pcmVal = pcm >> 3;
	        
	        if (pcmVal >= 0) {
	            mask = 0xD5;
	        } else {
	            mask = 0x55;
	            pcmVal = -pcmVal;
	            if (pcmVal > 0x1FFF) {
	                pcmVal = 0x1FFF;
	            }
	        }
	        
	        // 查找段号
	        seg = 7;
	        if (pcmVal < 256) {
	            for (seg = 0; seg < 8; seg++) {
	                if (pcmVal <= (32 << seg)) {
	                    break;
	                }
	            }
	        }
	        
	        // 构建 A-law 字节
	        int aval;
	        if (seg >= 8) {
	            aval = 0x7F ^ mask;
	        } else {
	            aval = seg << 4;
	            if (seg < 2) {
	                aval |= (pcmVal >> 1) & 0x0F;
	            } else {
	                aval |= (pcmVal >> seg) & 0x0F;
	            }
	            aval ^= mask;
	        }
	        
	        return (byte) aval;
	    }


	    // ============ 方案 2: 简单平均（如果线性插值还有杂音）============

	    /**
	     * 备选方案：使用简单平均降采样
	     * 每 3 个样本取平均（24kHz -> 8kHz）
	     */
	    private void convertPcmToAlaw_SimpleAverage(InputStream pcmStream, String outputPath) throws Exception {
	        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
	        byte[] chunk = new byte[8192];
	        int bytesRead;
	        int chunkNumber = 0;
	        
	        while ((bytesRead = pcmStream.read(chunk)) != -1) {
	            buffer.write(chunk, 0, bytesRead);
	            onPcmChunk(chunk, bytesRead, ++chunkNumber);
	        }
	        
	        byte[] pcm24kData = buffer.toByteArray();
	        
	        // 转换为 short 数组
	        short[] samples24k = new short[pcm24kData.length / 2];
	        for (int i = 0; i < samples24k.length; i++) {
	            int low = pcm24kData[i * 2] & 0xFF;
	            int high = pcm24kData[i * 2 + 1] & 0xFF;
	            samples24k[i] = (short) ((high << 8) | low);
	        }
	        
	        // 每 3 个样本取平均
	        int outputLength = samples24k.length / 3;
	        short[] samples8k = new short[outputLength];
	        
	        for (int i = 0; i < outputLength; i++) {
	            int sum = samples24k[i * 3] + samples24k[i * 3 + 1] + samples24k[i * 3 + 2];
	            samples8k[i] = (short) (sum / 3);
	        }
	        
	        // 转换为 A-law
	        byte[] alawData = new byte[samples8k.length];
	        int alawChunkNumber = 0;
	        
	        for (int i = 0; i < samples8k.length; i++) {
	            alawData[i] = linearToAlaw(samples8k[i]);
	            
	            if ((i + 1) % 1024 == 0 || i == samples8k.length - 1) {
	                int chunkSize = Math.min(1024, samples8k.length - (alawChunkNumber * 1024));
	                byte[] alawChunk = new byte[chunkSize];
	                System.arraycopy(alawData, alawChunkNumber * 1024, alawChunk, 0, chunkSize);
	                onAlawChunk(alawChunk, ++alawChunkNumber);
	            }
	        }
	        
	        // 保存 WAV
	        AudioFormat alawFormat = new AudioFormat(
	            AudioFormat.Encoding.ALAW, 8000, 8, 1, 1, 8000, false
	        );
	        
	        try (ByteArrayInputStream bais = new ByteArrayInputStream(alawData);
	             AudioInputStream ais = new AudioInputStream(bais, alawFormat, alawData.length)) {
	            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, new File(outputPath));
	        }
	    }


	    // ============ 方案 3: 使用低通滤波器（最高质量）============

	    /**
	     * 最高质量：带低通滤波器的降采样
	     * 消除混叠，减少杂音
	     */
	    private void convertPcmToAlaw_WithFilter(InputStream pcmStream, String outputPath) throws Exception {
	        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
	        byte[] chunk = new byte[8192];
	        int bytesRead;
	        int chunkNumber = 0;
	        
	        while ((bytesRead = pcmStream.read(chunk)) != -1) {
	            buffer.write(chunk, 0, bytesRead);
	            onPcmChunk(chunk, bytesRead, ++chunkNumber);
	        }
	        
	        byte[] pcm24kData = buffer.toByteArray();
	        
	        // 转换为 short 数组
	        short[] samples24k = new short[pcm24kData.length / 2];
	        for (int i = 0; i < samples24k.length; i++) {
	            int low = pcm24kData[i * 2] & 0xFF;
	            int high = pcm24kData[i * 2 + 1] & 0xFF;
	            samples24k[i] = (short) ((high << 8) | low);
	        }
	        
	        // 应用低通滤波器（截止频率 4kHz，避免混叠）
	        short[] filtered = applyLowPassFilter(samples24k, 24000, 4000);
	        
	        // 线性插值降采样
	        double ratio = 24000.0 / 8000.0;
	        int outputLength = (int) (filtered.length / ratio);
	        short[] samples8k = new short[outputLength];
	        
	        for (int i = 0; i < outputLength; i++) {
	            double srcPos = i * ratio;
	            int srcIndex = (int) srcPos;
	            double fraction = srcPos - srcIndex;
	            
	            if (srcIndex + 1 < filtered.length) {
	                double sample = filtered[srcIndex] * (1.0 - fraction) + 
	                               filtered[srcIndex + 1] * fraction;
	                samples8k[i] = (short) Math.round(sample);
	            } else {
	                samples8k[i] = filtered[srcIndex];
	            }
	        }
	        
	        // 转换为 A-law
	        byte[] alawData = new byte[samples8k.length];
	        int alawChunkNumber = 0;
	        
	        for (int i = 0; i < samples8k.length; i++) {
	            alawData[i] = linearToAlaw(samples8k[i]);
	            
	            if ((i + 1) % 1024 == 0 || i == samples8k.length - 1) {
	                int chunkSize = Math.min(1024, samples8k.length - (alawChunkNumber * 1024));
	                byte[] alawChunk = new byte[chunkSize];
	                System.arraycopy(alawData, alawChunkNumber * 1024, alawChunk, 0, chunkSize);
	                onAlawChunk(alawChunk, ++alawChunkNumber);
	            }
	        }
	        
	        // 保存 WAV
	        AudioFormat alawFormat = new AudioFormat(
	            AudioFormat.Encoding.ALAW, 8000, 8, 1, 1, 8000, false
	        );
	        
	        try (ByteArrayInputStream bais = new ByteArrayInputStream(alawData);
	             AudioInputStream ais = new AudioInputStream(bais, alawFormat, alawData.length)) {
	            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, new File(outputPath));
	        }
	    }

	    /**
	     * 简单的低通滤波器（滑动平均）
	     */
	    private short[] applyLowPassFilter(short[] input, int sampleRate, int cutoffFreq) {
	        // 计算窗口大小
	        int windowSize = sampleRate / cutoffFreq / 2;
	        if (windowSize < 3) windowSize = 3;
	        if (windowSize % 2 == 0) windowSize++; // 确保是奇数
	        
	        short[] output = new short[input.length];
	        int halfWindow = windowSize / 2;
	        
	        for (int i = 0; i < input.length; i++) {
	            long sum = 0;
	            int count = 0;
	            
	            for (int j = -halfWindow; j <= halfWindow; j++) {
	                int index = i + j;
	                if (index >= 0 && index < input.length) {
	                    sum += input[index];
	                    count++;
	                }
	            }
	            
	            output[i] = (short) (sum / count);
	        }
	        
	        return output;
	    }

	    
	    
	    /**
	     * 使用 AudioSystem 自动转换 PCM -> A-law
	     * 直接替换到你的代码中即可
	     * 
	     * 音质最好的方案！
	     */
	    private void convertPcmToAlaw_AudioSystem(InputStream pcmStream, String outputPath) throws Exception {
	        // 1. 读取所有 PCM 数据
	        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
	        byte[] chunk = new byte[8192];
	        int bytesRead;
	        
	        while ((bytesRead = pcmStream.read(chunk)) != -1) {
	            buffer.write(chunk, 0, bytesRead);
	        }
	        
	        byte[] pcmData = buffer.toByteArray();
	        
	        // 2. OpenAI PCM 格式 (24kHz, 16-bit, mono, 小端)
	        AudioFormat sourcePcmFormat = new AudioFormat(
	            AudioFormat.Encoding.PCM_SIGNED,
	            24000.0f,  // 采样率
	            16,        // 位深度
	            1,         // 单声道
	            2,         // 帧大小
	            24000.0f,  // 帧率
	            false      // 小端
	        );
	        
	        ByteArrayInputStream bais = new ByteArrayInputStream(pcmData);
	        AudioInputStream sourcePcmStream = new AudioInputStream(
	            bais, 
	            sourcePcmFormat, 
	            pcmData.length / sourcePcmFormat.getFrameSize()
	        );
	        
	        // 3. 目标 PCM 格式 (8kHz, 16-bit, mono)
	        AudioFormat targetPcmFormat = new AudioFormat(
	            AudioFormat.Encoding.PCM_SIGNED,
	            8000.0f,   // 降到 8kHz
	            16,
	            1,
	            2,
	            8000.0f,
	            false
	        );
	        
	        // 4. AudioSystem 自动重采样 (这里是关键!)
	        AudioInputStream pcm8kStream = AudioSystem.getAudioInputStream(
	            targetPcmFormat, 
	            sourcePcmStream
	        );
	        
	        // 5. A-law 格式
	        AudioFormat alawFormat = new AudioFormat(
	            AudioFormat.Encoding.ALAW,
	            8000.0f,
	            8,
	            1,
	            1,
	            8000.0f,
	            false
	        );
	        
	        // 6. AudioSystem 自动转换为 A-law
	        AudioInputStream alawStream = AudioSystem.getAudioInputStream(
	            alawFormat, 
	            pcm8kStream
	        );
	        
	        // 7. 读取 A-law 数据并触发回调
	        ByteArrayOutputStream alawBuffer = new ByteArrayOutputStream();
	        byte[] alawChunk = new byte[1024];
	        int alawBytesRead;
	        int chunkNumber = 0;
	        
	        while ((alawBytesRead = alawStream.read(alawChunk)) != -1) {
	            byte[] actualChunk = new byte[alawBytesRead];
	            System.arraycopy(alawChunk, 0, actualChunk, 0, alawBytesRead);
	            
	            // 触发回调 (可选)
	            onAlawChunk(actualChunk, ++chunkNumber);
	            
	            alawBuffer.write(alawChunk, 0, alawBytesRead);
	        }
	        
	        // 8. 保存 WAV 文件
	        byte[] alawData = alawBuffer.toByteArray();
	        ByteArrayInputStream alawBais = new ByteArrayInputStream(alawData);
	        AudioInputStream finalAlawStream = new AudioInputStream(
	            alawBais, 
	            alawFormat, 
	            alawData.length
	        );
	        
	        AudioSystem.write(finalAlawStream, AudioFileFormat.Type.WAVE, new File(outputPath));
	        
	        // 9. 清理资源
	        finalAlawStream.close();
	        alawStream.close();
	        pcm8kStream.close();
	        sourcePcmStream.close();
	    }

	    /**
	     * 转换 PCM 为 A-law，同时保存原始文件和转换后的文件
	     * 
	     * @param pcmStream 输入的 PCM 流
	     * @param outputPath A-law WAV 文件输出路径 (例如: "output_alaw.wav")
	     * @throws Exception
	     */
	    private void convertPcmToAlaw3(InputStream pcmStream, String outputPath) throws Exception {
	        // OpenAI 的 PCM 格式
	        AudioFormat sourcePcmFormat = new AudioFormat(
	            AudioFormat.Encoding.PCM_SIGNED,
	            24000,  // 24kHz
	            16,     // 16-bit
	            1,      // 单声道
	            2,      // 帧大小 (16bit = 2 bytes)
	            24000,  // 帧率
	            false   // 小端
	        );

	        // 读取所有 PCM 数据
	        ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
	        byte[] buffer = new byte[8192];
	        int bytesRead;
	        int chunkNumber = 0;

	        while ((bytesRead = pcmStream.read(buffer)) != -1) {
	            pcmBuffer.write(buffer, 0, bytesRead);
	            onPcmChunk(buffer, bytesRead, ++chunkNumber);
	        }

	        byte[] pcmData = pcmBuffer.toByteArray();

	        // ========== 保存原始 24kHz PCM WAV 文件 ==========
	        String originalPcmPath = outputPath.replace(".wav", "_24k_original.wav");
	        ByteArrayInputStream originalBais = new ByteArrayInputStream(pcmData);
	        AudioInputStream originalPcmStream = new AudioInputStream(
	            originalBais, 
	            sourcePcmFormat, 
	            pcmData.length / sourcePcmFormat.getFrameSize()
	        );
	        AudioSystem.write(originalPcmStream, AudioFileFormat.Type.WAVE, new File(originalPcmPath));
	        originalPcmStream.close();
	        System.out.println("✅ 原始 24kHz PCM 文件已保存: " + originalPcmPath);
	        // ================================================

	        // 创建 AudioInputStream 用于转换
	        ByteArrayInputStream bais = new ByteArrayInputStream(pcmData);
	        AudioInputStream pcm24kStream = new AudioInputStream(
	            bais, sourcePcmFormat, pcmData.length / sourcePcmFormat.getFrameSize()
	        );

	        // 降采样到 8000 Hz
	        AudioFormat pcm8kFormat = new AudioFormat(
	            AudioFormat.Encoding.PCM_SIGNED,
	            8000,   // 8kHz
	            16,     // 16-bit
	            1,      // 单声道
	            2,      // 帧大小
	            8000,   // 帧率
	            false   // 小端
	        );

	        AudioInputStream pcm8kStream = AudioSystem.getAudioInputStream(pcm8kFormat, pcm24kStream);

	        // ========== 可选：保存 8kHz PCM WAV 文件 ==========
	        // 如果你也想要 8kHz 的 PCM 版本，取消下面的注释
	        /*
	        ByteArrayOutputStream pcm8kBuffer = new ByteArrayOutputStream();
	        byte[] pcm8kChunk = new byte[8192];
	        int pcm8kBytesRead;
	        while ((pcm8kBytesRead = pcm8kStream.read(pcm8kChunk)) != -1) {
	            pcm8kBuffer.write(pcm8kChunk, 0, pcm8kBytesRead);
	        }
	        byte[] pcm8kData = pcm8kBuffer.toByteArray();
	        
	        String pcm8kPath = outputPath.replace(".wav", "_8k_pcm.wav");
	        ByteArrayInputStream pcm8kBais = new ByteArrayInputStream(pcm8kData);
	        AudioInputStream pcm8kSaveStream = new AudioInputStream(
	            pcm8kBais, pcm8kFormat, pcm8kData.length / pcm8kFormat.getFrameSize()
	        );
	        AudioSystem.write(pcm8kSaveStream, AudioFileFormat.Type.WAVE, new File(pcm8kPath));
	        pcm8kSaveStream.close();
	        System.out.println("✅ 8kHz PCM 文件已保存: " + pcm8kPath);
	        
	        // 重新创建 8kHz 流用于后续转换
	        bais = new ByteArrayInputStream(pcmData);
	        pcm24kStream = new AudioInputStream(bais, sourcePcmFormat, pcmData.length / sourcePcmFormat.getFrameSize());
	        pcm8kStream = AudioSystem.getAudioInputStream(pcm8kFormat, pcm24kStream);
	        */
	        // ================================================

	        // 转换为 A-law
	        AudioFormat alawFormat = new AudioFormat(
	            AudioFormat.Encoding.ALAW,
	            8000,   // 8kHz
	            8,      // 8-bit
	            1,      // 单声道
	            1,      // 帧大小
	            8000,   // 帧率
	            false   // 小端
	        );

	        AudioInputStream alawStream = AudioSystem.getAudioInputStream(alawFormat, pcm8kStream);

	        // 读取 A-law 数据并触发回调
	        ByteArrayOutputStream alawBuffer = new ByteArrayOutputStream();
	        byte[] alawChunkBuffer = new byte[1024];
	        int alawBytesRead;
	        int alawChunkNumber = 0;

	        while ((alawBytesRead = alawStream.read(alawChunkBuffer)) != -1) {
	            byte[] chunk = new byte[alawBytesRead];
	            System.arraycopy(alawChunkBuffer, 0, chunk, 0, alawBytesRead);

	            onAlawChunk(chunk, ++alawChunkNumber);
	            alawBuffer.write(alawChunkBuffer, 0, alawBytesRead);
	        }

	        // 保存 A-law WAV 文件
	        byte[] alawData = alawBuffer.toByteArray();
	        try (ByteArrayInputStream alawBais = new ByteArrayInputStream(alawData);
	             AudioInputStream ais = new AudioInputStream(alawBais, alawFormat, alawData.length)) {
	            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, new File(outputPath));
	        }
	        System.out.println("✅ A-law 文件已保存: " + outputPath);

	        // 清理
	        alawStream.close();
	        pcm8kStream.close();
	        pcm24kStream.close();
	    }
	}
