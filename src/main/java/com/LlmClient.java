package com;

import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * 统一的大模型调用接口
 */
public interface LlmClient {
    
    /**
     * 多轮对话生成
     * @param messages 历史消息数组 (对应 OpenAI 的 messages 结构)
     * @return 大模型的文本回复
     */
    String chat(ArrayNode messages) throws Exception;

    /**
     * 单次指令调用 (方便做重写引擎)
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户输入
     * @return 大模型的文本回复
     */
    String generate(String systemPrompt, String userPrompt) throws Exception;

}