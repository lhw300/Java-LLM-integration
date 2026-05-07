package com.lcallai.handler;

import com.lcallai.ChatAnswer;
import com.lcallai.ChatSession;
import com.lcallai.intent.IntentHandler;
import com.lcallai.intent.IntentResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * QUERY 处理器
 *
 * 使用分类器补全后的 refinedQuery 直接送入 RAG 检索链路（ask3）。
 * 指代词已在 IntentClassifier 里消解，此处无需额外处理。
 */
public class QueryHandler implements IntentHandler {
    private static final Logger logger = LogManager.getLogger(QueryHandler.class);



    @Override

    public ChatAnswer handle(String rawText, IntentResult result, ChatSession session) {
        logger.debug("[QueryHandler] refinedQuery=" + result.refinedQuery);

        // In simple mode (no category needed), skip the category check
        if (!"simple".equalsIgnoreCase(session.getQueryMode())) {
            if (session.getCurrentCategory() == null) {
                return new ChatAnswer(0, "请问您是老师、学生还是管理员呢？", result);
            }
        }

        return session.askByQueryMode(result.refinedQuery, false);
    }
}
