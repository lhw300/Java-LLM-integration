 

	package com.asrtts;

	import okhttp3.*;
	import okio.ByteString;
	import java.io.FileInputStream;
	import java.io.IOException;
	import java.util.concurrent.TimeUnit;
    import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
	public class AsyncAsr {
    private static final Logger logger = LogManager.getLogger(AsyncAsr.class);

	    private WebSocket webSocket;
	    private final OkHttpClient client;
        public final CountDownLatch latch = new CountDownLatch(1);
	    public AsyncAsr() {
	        this.client = new OkHttpClient.Builder()
	                .readTimeout(0, TimeUnit.MILLISECONDS)
	                .build();
	    }
        private volatile long doneTime = 0;
        // ★ 新增字段
        private volatile long lastElapsed = 0;

        public long getLastElapsed() {
            return lastElapsed;
        }

	    /**
	     * 建立连接并开始流式发送
	     */
	    public void startAsr(String filePath) {
	        // ASR 接口地址
	       // String url = "ws://localhost:8000/asr?samplerate=8000";
	        String url = "ws://localhost:8000/asr?samplerate=16000";
	        
	        Request request = new Request.Builder().url(url).build();

	        webSocket = client.newWebSocket(request, new WebSocketListener() {
	            @Override
	            public void onOpen(WebSocket webSocket, Response response) {
	                logger.debug("ASR WebSocket 连接已建立，开始读取文件: " + filePath);
	                // 开启新线程读取并发送音频数据
	                new Thread(() -> streamFile8k(filePath)).start();
	            }
                // ★★★ 新增 doneTime 字段 ★★★

	            @Override
	            public void onMessage(WebSocket webSocket, String text) {
                    logger.debug("识别结果 > " + text);

                    // ★ 修改点 1：只要收到消息且已经发送了 done，就实时更新耗时
                    if (doneTime > 0) {
                        lastElapsed = System.currentTimeMillis() - doneTime;
                    }

                    // ★ 修改点 2：核心修复 - 监听后端返回的 "finished": true 信号
                    // 只有彻底识别完，才触发 latch.countDown() 让主线程进入下一轮
                    if (text.contains("\"finished\":true") || text.contains("\"finished\": true")) {
                        logger.debug(">>> 任务完成，本回合最终耗时: " + lastElapsed + "ms");
                        latch.countDown();
                    }
	            }

	            @Override
	            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
	            	// 增加空值保护
	            	// 使用 Object.toString() 或先进行非空判断
	                String msg = (t != null && t.getMessage() != null) ? t.getMessage() : "正常关闭或未知原因";
	                
	                // 只有在不是正常结束的情况下才打印错误
	                if (!"Socket closed".equals(msg)) {
	                    logger.error("ASR 连接状态: " + msg);
	                }
                    latch.countDown(); // 发生异常也释放，防止主线程死锁
	            }

	            @Override
	            public void onClosing(WebSocket webSocket, int code, String reason) {
	            	// 收到 1000 表示正常关闭，不需要报错
	                if (code == 1000) {
	                    logger.debug("ASR 识别任务已圆满完成。");
	                } else {
	                    logger.error("连接意外关闭: " + reason);
	                }
	                webSocket.close(1000, null);
	            }
	        });
	    }

	    private void streamFile2(String filePath) {
	        // 使用缓冲区流式读取 WAV 文件
	        try (FileInputStream fis = new FileInputStream(filePath)) {
	            // 跳过 44 字节的 WAV 头，直接读取原始 PCM 数据
	            fis.skip(44);

	            // 每次读取 3200 字节（约 100ms 的 16k 音频数据）发送给后端
	            byte[] buffer = new byte[3200];
	            int bytesRead;
	            while ((bytesRead = fis.read(buffer)) != -1) {
	                if (webSocket != null) {
	                    // 发送二进制 PCM 数据
	                    webSocket.send(ByteString.of(buffer, 0, bytesRead));
	                    
	                    // 模拟实时流，稍微延迟一下，避免瞬间发完导致后端处理不过来
	                    Thread.sleep(50); 
	                }
	            }
	         // 核心：告知后端音频已传输完毕
	            Thread.sleep(1500);
	            webSocket.send("done"); 
	            logger.debug("发送完毕，已投递结束信号。");
	            
	     
	        } catch (IOException | InterruptedException e) {
	            logger.error("读取文件出错: " + e.getMessage());
	        }
	    }
        /**
         * 将 8000Hz 16-bit PCM 字节数组重采样为 16000Hz (使用线性插值算法)
         * @param input 原始的 8k 音频字节数组 (小端序)
         * @param length 实际读取到的有效字节长度
         * @return 重采样后的 16k 音频字节数组 (长度将是 input length 的 2 倍)
         */
        private byte[] resample8kTo16k(byte[] input, int length) {
            // 16-bit PCM，2个字节代表一个采样点
            int numSamples = length / 2;
            short[] inputSamples = new short[numSamples];

            // 将 byte[] (小端序) 转换为 short[]
            for (int i = 0; i < numSamples; i++) {
                inputSamples[i] = (short) ((input[i * 2] & 0xFF) | (input[i * 2 + 1] << 8));
            }

            // 1:2 上采样 (目标采样点数量是原来的 2 倍)
            int outSamplesCount = numSamples * 2;
            short[] outputSamples = new short[outSamplesCount];

            // 线性插值算法
            for (int i = 0; i < numSamples; i++) {
                // 第一个点：完全保留原采样值
                outputSamples[2 * i] = inputSamples[i];

                // 第二个点：取当前点和下一个点的平均值（使得声音过渡更平滑）
                if (i < numSamples - 1) {
                    outputSamples[2 * i + 1] = (short) ((inputSamples[i] + inputSamples[i + 1]) / 2);
                } else {
                    // 最后一个点没有下一个点，直接复制
                    outputSamples[2 * i + 1] = inputSamples[i];
                }
            }

            // 将重采样后的 short[] 重新转换回 byte[] (小端序)
            byte[] output = new byte[outSamplesCount * 2];
            for (int i = 0; i < outSamplesCount; i++) {
                output[i * 2] = (byte) (outputSamples[i] & 0xFF);
                output[i * 2 + 1] = (byte) ((outputSamples[i] >> 8) & 0xFF);
            }

            return output;
        }
        private void streamFile8k(String filePath) {
            try (FileInputStream fis = new FileInputStream(filePath)) {
                // 1. 跳过 44 字节头
                fis.skip(44);

                // ★ 修改点 1：读取源头变为 8k，所以每 100ms 读取的数据量是 1600 字节
                // 8000Hz * 2byte(16-bit) * 0.1s = 1600 bytes
                byte[] buffer8k = new byte[1600];

                int bytesRead;

                logger.debug("正在模拟电话线路实时流 (8k 本地重采样 16k 发送)...");
                int size = 0;

                while ((bytesRead = fis.read(buffer8k)) != -1) {
                    if (webSocket != null) {
                        // ★ 修改点 2：调用重采样函数，将读到的 8k 转换成 16k
                        // 返回的 resampled16k 数组长度会翻倍变成 3200 字节 (如果是满包的话)
                        byte[] resampled16k = resample8kTo16k(buffer8k, bytesRead);

                        // 发送重采样后的 16k PCM 数据
                        webSocket.send(ByteString.of(resampled16k, 0, resampled16k.length));
                        size += resampled16k.length;

                        // 3. 严格同步：虽然发送了 3200 字节，但它代表的物理时间依然是 100ms
                        Thread.sleep(100);
                    }
                }

                webSocket.send("done"); // 发送结束信号
                doneTime = System.currentTimeMillis();
                logger.debug("电话录音发送完毕。累计发送 16k 数据量: " + size + " 字节");

            } catch (Exception e) {
                logger.error("读取文件或发送失败: " + e.getMessage());
                // 如果出错，记得释放 latch 防止死锁
                if (latch != null) {
                    latch.countDown();
                }
            }
        }
	    private void streamFile16k(String filePath) {
	        try (FileInputStream fis = new FileInputStream(filePath)) {
	            // 1. 必须跳过 44 字节头，否则开头的 RIFF 字符会产生爆音干扰识别
	            fis.skip(44);

	            // 2. 缓冲区：8k 采样率下，每 100ms 的数据量是 1600 字节 (8000 * 2byte * 0.1s)
	           // byte[] buffer = new byte[1600];
	         // 16000Hz * 2byte * 0.1s = 3200 字节
	            byte[] buffer = new byte[3200];
	            
	            int bytesRead;

	            logger.debug("正在模拟  电话线路实时流...");
	            int size=0;
	            while ((bytesRead = fis.read(buffer)) != -1) {
	                if (webSocket != null) {
	                    // 发送原始 PCM
	                    webSocket.send(ByteString.of(buffer, 0, bytesRead));
	                    size+=bytesRead;
	                    // 3. 严格同步：发送 100ms 的数据就必须睡足 100ms
	                    // 如果发得太快，后端 ASR 引擎会因为数据堆积导致识别逻辑失效
	                     Thread.sleep(100);
	                   // if(size%2000==0) logger.debug("电话录音发送  size="+size);
	                }
	            }
	            //Thread.sleep(1500);
	            webSocket.send("done"); // 发送结束信号
                // ★★★ 去掉 sleep(1500)，记录发送 done 的时间 ★★★
                doneTime = System.currentTimeMillis();
	            logger.debug("电话录音发送完毕。");
	        } catch (Exception e) {
	            logger.error("读取文件或发送失败: " + e.getMessage());
	        }
	    }

/*
        public static void main(String[] args) throws InterruptedException {
            int times = 10;
            long totalElapsed = 0;

            for (int i = 0; i < times; i++) {
                AsyncAsr asrClient = new AsyncAsr();
                asrClient.startAsr("C:\\11_16k.wav");
                asrClient.latch.await();  // ★ 替换 Thread.sleep(5000)
                Thread.sleep(5000);  // ★ 加这行
                totalElapsed += asrClient.getLastElapsed(); // ★ 需要新增这个方法
                logger.debug("第" + (i+1) + "次耗时: " + asrClient.getLastElapsed() + "ms");
            }

            logger.debug("平均耗时: " + totalElapsed / times + "ms");
        }

 */
public static void main(String[] args) throws InterruptedException {
    int concurrent = 1;  // 并发数
    int rounds = 3;       // 每个实例发送次数
    long totalElapsed = 0;
    int totalCount = 0;

    for (int r = 0; r < rounds; r++) {
        Thread.sleep(3000);  // ★ 加这行
        logger.debug("=== 第" + (r+1) + "轮 ===");
        AsyncAsr[] clients = new AsyncAsr[concurrent];

        // ★ 同时启动所有并发
        for (int i = 0; i < concurrent; i++) {
            clients[i] = new AsyncAsr();
            clients[i].startAsr("C:\\guoqing_16bit.wav");
        }

        // ★ 等所有完成
        for (int i = 0; i < concurrent; i++) {
            clients[i].latch.await();

            totalElapsed += clients[i].getLastElapsed();
            totalCount++;
            logger.debug("第" + (r+1) + "轮第" + (i+1) + "个耗时: " + clients[i].getLastElapsed() + "ms");
        }
    }

    logger.debug("总平均耗时: " + totalElapsed / totalCount + "ms");
}
	}