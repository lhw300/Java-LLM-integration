package com.lcallai.handler;
import java.util.Random;
import com.lcallai.ChatAnswer;
import com.lcallai.ChatSession;
import com.lcallai.intent.IntentHandler;
import com.lcallai.intent.IntentResult;

/**
 * GREETING 处理器
 *
 * 处理纯粹的打招呼和告别语（"你好"、"再见"、"拜拜"）。
 * 快速闭环，不走 RAG。
 */
public class GreetingHandler implements IntentHandler {
    private static final String[] POOL = {
            "您好！我是粤教翔云3.0助手。我可以帮您找回密码或指导安装。请问您是老师还是学生？",
            "哈喽！找初始密码吗？试试 A202101b 吧！其他问题请直接提问。",
            "见到您真高兴！安装3.0请确保电脑是 Win7 或以上。您遇到什么安装报错了吗？",
            "嗨！我是您的数字教材管家。虽然我不能替您备课，但我能秒回业务难题。想聊点什么？",
            "您好，粤教翔云3.0专家为您服务。请问您是老师、学生还是管理员？"
    };

    @Override
    public ChatAnswer handle(String userInput, IntentResult intentRes, ChatSession session) {


        if (userInput.matches(".*(再见|拜拜|下次聊|退出了|不聊了).*")) {
            return new ChatAnswer(0, "好的，祝您工作顺利，", intentRes);
        }

        // 5选1随机输出
        String text = POOL[new Random().nextInt(POOL.length)];
        return new ChatAnswer(200, text, intentRes);
    }
}