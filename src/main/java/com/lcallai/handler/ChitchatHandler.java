package com.lcallai.handler;

import com.lcallai.ChatAnswer;
import com.lcallai.ChatSession;
import com.lcallai.intent.IntentHandler;
import com.lcallai.intent.IntentResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CHITCHAT 处理器
 *
 * 处理与业务无关的闲聊（"今天天气真好"、"你是谁"）。
 * 策略：走 askFullContext（全量知识库模式），让 LLM 自由生成回复，
 * 同时知识库上下文在 System Prompt 中存在，避免模型出现业务幻觉。
 *
 * 如果你的场景不需要知识库约束，也可以直接调用裸 LLM 生成，
 * 把 askFullContext 换成 router.finalLlm().generate(chitchatPrompt, rawText) 即可。
 */

    public class ChitchatHandler implements IntentHandler {
    private static final Logger logger = LogManager.getLogger(ChitchatHandler.class);
    private final String chitchatPrompt;

    public ChitchatHandler(String chitchatPrompt) {
        this.chitchatPrompt = chitchatPrompt;
    }
        @Override
        public ChatAnswer handle(String rawText, IntentResult result, ChatSession session) {
            logger.debug("[ChitchatHandler] 切换至纯净对话模式，跳过所有知识库检索");

            try {
                // 1. 获取重写后的查询（可选，建议保留以维持多轮对话连贯性）
                // 注意：这里传入 false，因为闲聊不需要结合知识库重写，只需指代消解
                String optimizedQuery = result.refinedQuery != null ? result.refinedQuery : rawText;


                // 3. 执行对话
                // executeFinalChat 会自动处理 history 的更新和 systemMessage 的构建
                String ans= session.executeChitchat(chitchatPrompt, optimizedQuery);
                return new ChatAnswer(0, ans, result);


            } catch (Exception e) {
                logger.error("", e);
                return new ChatAnswer(-1, "闲聊系统暂时休息了: ", result);
            }
        }
    }
