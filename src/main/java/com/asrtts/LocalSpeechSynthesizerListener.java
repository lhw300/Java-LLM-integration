package com.asrtts;



/**
 * 模仿阿里云 SpeechSynthesizerListener 接口
 */
public interface LocalSpeechSynthesizerListener {
    // 收到语音片段二进制流时触发
    void onMessage(byte[] message);

    // 后端返回彻底合成完毕信号时触发
    void onComplete();

    // 发生网络错误或异常时触发
    void onFail(String errorMessage);
}