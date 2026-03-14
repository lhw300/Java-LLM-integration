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
    // 🌟 新增的高度抽象接口：只认 Query 和 Document，直接返回 0~1 的打分
    /**
     * 语义相关度打分，返回 [0.0, 1.0]，1.0 最相关。
     * 默认实现：把 prompt 传给 generate() 让 LLM 打分（适用于 OllamaClient）。
     * DJLLocalClient 覆盖此方法，直接走 Cross-Encoder 推理，忽略 systemPrompt。
     */
    /**
     * 语义相关度打分，返回 [0.0, 1.0]，1.0 最相关。
     * 优化版：增强了抗干扰能力，防止大模型胡言乱语导致正则提取灾难。
     */
    default double rerank(String query, String document, String systemPrompt) throws Exception {
        String userPrompt = "【用户问题】：" + query + "\n【参考文档】：" + document;

        // 1. 获取 AI 原始回复
        String scoreRaw = generate(systemPrompt, userPrompt).trim();

        // 2. 增强版正则：只抓取独立存在的 0~1 之间的小数或 0、1，并且前后不能紧挨着其他数字
        // 解释：(?<!\\d) 前面不能是数字；(0\\.\\d+|1(?:\\.0+)?|0) 匹配 0.xxx, 1, 1.0, 0；(?!\\d) 后面不能是数字
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?<!\\d)(0\\.\\d+|1(?:\\.0+)?|0)(?!\\d)");
        java.util.regex.Matcher m = pattern.matcher(scoreRaw);

        double finalScore = 0.0; // 默认不相关
        boolean found = false;

        // 3. 核心策略：遍历到底，采取“最后匹配者赢” (Last Match Wins)
        // 应对场景："1. 理由是... 所以打分为 0.2"，会先匹配到 1，再匹配到 0.2，最终取 0.2
        while (m.find()) {
            finalScore = Double.parseDouble(m.group(1));
            found = true;
        }

        if (found) {
            return Math.min(finalScore, 1.0);
        }

        // 4. 兜底日志：如果大模型彻底胡言乱语没有合法数字，输出日志方便排查 Prompt
        System.out.println("⚠️ Rerank 无法从大模型回复中提取合法分数，原回复: " + scoreRaw);
        return 0.0;
    }
}