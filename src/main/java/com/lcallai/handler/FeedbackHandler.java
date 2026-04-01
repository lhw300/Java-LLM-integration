package com.lcallai.handler;

import com.lcallai.ChatAnswer;
import com.lcallai.ChatSession;
import com.lcallai.intent.IntentHandler;
import com.lcallai.intent.IntentResult;

import java.util.concurrent.ConcurrentHashMap;

/**
 * FEEDBACK 处理器
 *
 * 负责情绪安抚，并维护负面情绪计数器：
 * - 连续 2 次 negative → 强制触发转人工信号（返回 __TRANSFER__ 让调用方处理）
 * - positive → 正向感谢回复
 *
 * 注意：负面计数器用 ConcurrentHashMap<sessionId, count> 存储，
 * 避免了原代码中把状态放在 ChatAnswer 成员变量上的并发隐患。
 */
public class FeedbackHandler implements IntentHandler {

    /** key = sessionId，value = 连续负面次数 */
    private final ConcurrentHashMap<String, Integer> negCountMap = new ConcurrentHashMap<>();

    /** 连续负面多少次触发转人工 */
    private final int transferThreshold;

    public FeedbackHandler() {
        this(2);
    }

    public FeedbackHandler(int transferThreshold) {
        this.transferThreshold = transferThreshold;
    }


/*    public ChatAnswer handle2(String rawText, IntentResult result, ChatSession session) {
        if (result.sentiment == IntentResult.Sentiment.NEGATIVE) {
            String sid = session.getSessionId();
            int count = negCountMap.merge(sid, 1, Integer::sum);
            System.out.println("[FeedbackHandler] session=" + sid + " 负面次数=" + count);

            String reply = "非常抱歉给您带来不便，我已记录这个问题。";
            if (count >= transferThreshold) {
                negCountMap.remove(sid); // 转人工后重置计数，防止下次再立即触发
                reply += " 现在为您转接人工客服，请稍候。";
                // __TRANSFER__ 是约定的信号码，调用方（如电话中间件）拦截后执行转接动作
                return new ChatAnswer(0, "__TRANSFER__:" + reply);
            }
            return new ChatAnswer(0, reply);
        }

        // 正面情绪：重置负面计数
        String sid = session.getSessionId();
        negCountMap.remove(sid);
        return new ChatAnswer(0, "感谢您的认可，我会继续努力！");
    }*/
    @Override
    public ChatAnswer handle(String rawText, IntentResult result, ChatSession session) {
        String sid = session.getSessionId();

        // 负面情绪处理
        if (result.sentiment == IntentResult.Sentiment.NEGATIVE) {
            int count = negCountMap.merge(sid, 1, Integer::sum);

            if (count >= transferThreshold) {
                negCountMap.remove(sid);
                // 保持与 CommandHandler 类似的信号格式，方便中间件拦截
                return new ChatAnswer(0, "__TRANSFER__ 抱歉给您带来极差体验，正在为您转接高级专家。");
            }
            return new ChatAnswer(0, "非常抱歉让您产生困扰，您的反馈已记录，我会努力改进。");
        }

        // 非负面情绪（Positive 或 Neutral）：只要用户心情好转，就重置计数
        negCountMap.remove(sid);

        if (result.sentiment == IntentResult.Sentiment.POSITIVE) {
            return new ChatAnswer(0, "能帮到您真是太好了！我会继续加油的。");
        }

        // 默认中性反馈回复
        return new ChatAnswer(0, "收到您的反馈，感谢您对粤教翔云3.0的支持。");
    }
    /** 手动重置某个 session 的负面计数（如用户主动问问题后可调用） */
    public void resetNegCount(String sessionId) {
        negCountMap.remove(sessionId);
    }
}
