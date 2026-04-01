package com.lcallai.handler;

import com.lcallai.ChatAnswer;
import com.lcallai.ChatSession;
import com.lcallai.intent.IntentHandler;
import com.lcallai.intent.IntentResult;

/**
 * QUERY 处理器
 *
 * 使用分类器补全后的 refinedQuery 直接送入 RAG 检索链路（ask3）。
 * 指代词已在 IntentClassifier 里消解，此处无需额外处理。
 */
public class QueryHandler implements IntentHandler {



    @Override
    public ChatAnswer handle(String rawText, IntentResult result, ChatSession session) {
        System.out.println("[QueryHandler] refinedQuery=" + result.refinedQuery);


        // searchQuery 送检索，refinedQuery 记历史，ask3 内部跳过 performQueryRewrite
        return session.ask3(result.refinedQuery ,false);
    }
}
