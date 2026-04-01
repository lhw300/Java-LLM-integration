package com.lcallai.handler;

import com.lcallai.ChatAnswer;
import com.lcallai.ChatSession;
import com.lcallai.intent.IntentHandler;
import com.lcallai.intent.IntentResult;

/**
 * INFORM 处理器
 *
 * 用户在陈述背景信息（"我用的是 Mac"、"我是管理员"）而不是提问。
 * 处理策略：
 *   1. 将背景信息写入 queryHistory，供下一轮重写时使用
 *   2. 返回简短确认，引导用户继续提问
 *   3. 不走 RAG，不消耗额外 Token
 */
public class InformHandler implements IntentHandler {

    @Override
    public ChatAnswer handle(String rawText, IntentResult result, ChatSession session) {
        // 存入 queryHistory，标记为用户背景，下一轮 rewrite 时可被 LLM 看到
        session.getQueryHistory().addMessage("Context", "[用户背景] " + rawText);
        System.out.println("[InformHandler] 已记录用户背景信息: " + rawText);
        return new ChatAnswer(0, "好的，我已记录您的信息。请问有什么具体问题需要帮您解答吗？");
    }
}
