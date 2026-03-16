package com.asrtts;


import okhttp3.*;
	import okio.ByteString;
	import java.util.concurrent.TimeUnit;


import java.util.function.Consumer;
		public class AsyncTts {
	 
		    private WebSocket webSocket;
		    private final OkHttpClient client;
		    private Consumer<byte[]> pcmDataHandler; // 外部定义的音频处理逻辑

		    public AsyncTts() {
		        this.client = new OkHttpClient.Builder()
		                .readTimeout(0, TimeUnit.MILLISECONDS)
		                .build();
		    }

		    /**
		     * 第一步：启动并建立连接 (区分启动)
		     */
		    public void connect(Consumer<byte[]> pcmHandler) {
		        this.pcmDataHandler = pcmHandler;
		        
		        // 这里的参数必须对齐你之前的 8000Hz 和 0.95 语速
		        String url = "ws://localhost:8000/tts?sid=6&speed=0.95&samplerate=8000&split=true";
		        Request request = new Request.Builder().url(url).build();

		        webSocket = client.newWebSocket(request, new WebSocketListener() {
		            @Override
		            public void onOpen(WebSocket webSocket, Response response) {
		                System.out.println("TTS WebSocket 连接已建立");
		            }

		            @Override
		            public void onMessage(WebSocket webSocket, ByteString bytes) {
		                /**
		                 * 第三步：异步接受 (由内部回调，传递给外部处理器)
		                 */
		                if (pcmDataHandler != null) {
		                    pcmDataHandler.accept(bytes.toByteArray());
		                }
		            }

		            @Override
		            public void onMessage(WebSocket webSocket, String text) {
		                System.out.println("收到服务端统计/状态信息: " + text);
		            }

		            @Override
		            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
		                System.err.println("TTS 连接故障: " + t.getMessage());
		            }
		        });
		    }

		    /**
		     * 第二步：发送文字 (外围调用)
		     */
		    public void sendText(String text) {
		        if (webSocket != null) {
		            System.out.println("正在请求合成文字: " + text);
		            webSocket.send(text);
		        } else {
		            System.err.println("错误：WebSocket 未连接，请先执行 connect()");
		        }
		    }
		    public static void main(String[] args) {
		    	AsyncTts ttsService = new AsyncTts();

		        // 1. 启动连接，并定义“如何接受”音频
		        ttsService.connect(pcmData -> {
		            // 这里是异步接受的回调
		            System.out.println("异步回调：收到 8k PCM 数据，长度 " + pcmData.length);
		            // 对接播放器：AudioPlayer.play(pcmData);
		        });

		        // 2. 在业务需要时发送文字 (外围随处调用)
		        // 模拟宽带报障系统生成了一个播报任务
		        ttsService.sendText("通知：光猫拨号异常，请各单位派员核查。");
		    }
		}