package com.lcallai.intent;

import com.lcallai.ChatAnswer;
import com.lcallai.ChatSession;

/**
 * 意图处理器接口
 *
 * 每种 Intent 对应一个实现类，由 IntentDispatcher 统一注册和调用。
 * 新增意图时只需：
 *   1. 新建 XxxHandler 实现此接口
 *   2. 在 SessionManager 里注册一行
 * 无需修改 ChatSession 或任何现有 Handler。
 */
public interface IntentHandler {

    /**
     * 处理用户输入并返回应答
     *
     * @param rawText  用户原始输入（未经修改）
     * @param result   意图分类结果（含 refinedQuery、actionCode 等）
     * @param session  当前会话上下文（提供 RAG、history 等能力）
     * @return 应答对象（code=0 表示成功，负数表示错误）
     */
    ChatAnswer handle(String rawText, IntentResult result, ChatSession session);
}
