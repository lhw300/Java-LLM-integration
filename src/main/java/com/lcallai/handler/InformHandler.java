package com.lcallai.handler;
import java.util.Random;
import com.lcallai.ChatAnswer;
import com.lcallai.ChatSession;
import com.lcallai.intent.IntentHandler;
import com.lcallai.intent.IntentResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private static final Logger logger = LogManager.getLogger(InformHandler.class);

    private static final String[] RESPONSES = {
        "好的，我已经记下了。请问还有什么我可以帮您的吗？",
        "明白了，感谢您告诉我。请问有什么需要进一步了解的吗？",
        "好的，我知道了。您还有其他问题吗？",
        "嗯，我记住了。请问接下来有什么需要帮您处理的？",
        "收到，谢谢您的说明。请问还有什么我能帮到您的？"
    };

    @Override

    public ChatAnswer handle(String rawText, IntentResult result, ChatSession session) {
        logger.debug("[InformHandler] 已记录用户背景信息: " + rawText);

        // 1. 更新 currentCategory（用 classify 已标准化的 category，不用 rawText）
        if (result.category != null && !result.category.isBlank()) {
            session.setCurrentCategory(result.category);
        }

        // 2. 检查是否有挂起的问题（classify 续答规则失败时的 fallback）
        String pending = session.getPendingQuery();
        if (pending != null) {
            session.clearPendingQuery();
            return session.askByQueryMode(pending, false);
        }

        // 3. 没有挂起问题，正常确认
        String reply = RESPONSES[new Random().nextInt(RESPONSES.length)];
        return new ChatAnswer(0, reply, result);
    }
}