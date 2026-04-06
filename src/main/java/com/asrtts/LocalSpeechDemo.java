package com.asrtts;



import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LocalSpeechDemo {
    private static final Logger logger = LogManager.getLogger(LocalSpeechDemo.class);
    public static void main(String[] args) {

        // 1. 实现监听器 (完全贴合你的业务逻辑)
        LocalSpeechSynthesizerListener listener = new LocalSpeechSynthesizerListener() {
            @Override
            public void onMessage(byte[] message) {
                // 这里对接你的电话交换机 SipCallManager，或者存文件
                logger.debug("拿到语音数据块，长度: " + message.length);
            }

            @Override
            public void onComplete() {
                logger.debug("整句播报合成完毕！");
            }

            @Override
            public void onFail(String errorMessage) {
                logger.error("合成失败，原因: " + errorMessage);
            }
        };

        // 2. 初始化客户端 (复用 OkHttp 可以提高性能)
        OkHttpClient client = new OkHttpClient();

        // 3. 创建引擎实例 (和阿里云写法一模一样)
        LocalSpeechSynthesizer synthesizer = new LocalSpeechSynthesizer(client, listener);

        // 4. 设置各种参数
        synthesizer.setVoice("6");             // 我们 Python 后端的 sid 6
        synthesizer.setSampleRate(8000);       // 电信级 8k 采样率
        synthesizer.setSpeechRate(0.95f);      // 语速 0.95
        synthesizer.setText("光猫状态异常，请派单上门维修。");

        // 5. 开始合成！
        logger.debug("请求发起...");
        synthesizer.start();

        // 6. 阻塞等待任务完成 (防止 main 线程提前退出)
        synthesizer.waitForComplete();
        logger.debug("任务结束。");

        // 测试完毕退出
        System.exit(0);
    }
}