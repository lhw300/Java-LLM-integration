package com.lcallai.handler;

import com.lcallai.ChatAnswer;
import com.lcallai.ChatSession;
import com.lcallai.intent.IntentHandler;
import com.lcallai.intent.IntentResult;

/**
 * ACK 处理器（含 GREETING）
 *
 * 根据 sub_intent 细分三种场景：
 *   affirm  → 用户肯定了某个信息（"对，就是这个"）
 *   negate  → 用户否定或拒绝（"不用了，谢谢"）
 *   ack     → 普通确认（"好的"、"嗯"、"知道了"）
 *
 * 快速闭环，不走 RAG，不消耗 Token。
 */
public class AckHandler implements IntentHandler {

    @Override
    public ChatAnswer handle(String rawText, IntentResult result, ChatSession session) {
        String sub = result.subIntent;

        if ("affirm".equalsIgnoreCase(sub)) {
            return new ChatAnswer(0, "好的，已确认。请问还有什么可以帮您？");
        }
        if ("negate".equalsIgnoreCase(sub)) {
            return new ChatAnswer(0, "好的，没问题。如有需要随时告诉我。");
        }
        // 普通 ack 或 GREETING 都走这里
        return new ChatAnswer(0, "好的！请问有什么可以帮您？");
    }
}
