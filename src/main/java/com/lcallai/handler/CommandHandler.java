package com.lcallai.handler;

import com.lcallai.ChatAnswer;
import com.lcallai.ChatSession;
import com.lcallai.intent.IntentHandler;
import com.lcallai.intent.IntentResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * COMMAND 处理器
 *
 * 将 action_code 转换为系统信号码返回给调用方。
 * 约定：ChatAnswer.answer 以 "__" 开头的字符串为内部信号，由上层中间件（电话系统/前端）拦截执行。
 *
 * 支持的动作码：
 *   ACTION_REPLAY    → 重播上一条回复
 *   ACTION_TRANSFER  → 转接人工客服
 *   ACTION_VOL_UP    → 调高音量
 *   ACTION_VOL_DOWN  → 调低音量
 *
 * 扩展方式：在 switch 里增加新的 case，无需修改其他类。
 */
public class CommandHandler implements IntentHandler {
    private static final Logger logger = LogManager.getLogger(CommandHandler.class);

    @Override
    public ChatAnswer handle(String rawText, IntentResult result, ChatSession session) {
        String code = result.actionCode;

        // 1. 防御：如果模型判断为 COMMAND 但没给 code（比如“帮我安装”、“我要查话费”）
        if (code == null || code.isBlank() || "null".equalsIgnoreCase(code)) {
            // 既然是出版社业务，这里可以做一层业务引导
            logger.error("[CommandHandler] action_code 为空，原始输入: " + rawText);
            return new ChatAnswer(0, "收到您的指令，但我目前只能帮您转人工 或者重复说上一次 或您可以直接描述您遇到的问题。");
        }


        logger.debug("[CommandHandler] 执行动作: " + code);

        // 传统写法：直接对 code 进行判断，并在每个 case 中显式 return
        switch (code) {
            case "ACTION_REPLAY":
                String lastAnswer = session.getLastAnswer();
                if (lastAnswer == null || lastAnswer.isBlank()) {
                    return new ChatAnswer(ChatAnswer.CODE_OK, "暂时没有可重播的内容。", result);
                }
                // VOIP 层收到 Action.REPLAY 直接重播这个文本
                return ChatAnswer.ofAction(result, ChatAnswer.Action.REPLAY, lastAnswer);

            case "ACTION_TRANSFER":
                return ChatAnswer.ofAction(result, ChatAnswer.Action.TRANSFER, "正在为您转接人工客服，请稍候。");

            case "ACTION_VOL_UP":
                return ChatAnswer.ofAction(result, ChatAnswer.Action.VOL_UP, "好的，大声点");

            case "ACTION_VOL_DOWN":
                return ChatAnswer.ofAction(result, ChatAnswer.Action.VOL_DOWN, "好的，小声点");

            case "ACTION_HANGUP":
                return ChatAnswer.ofAction(result, ChatAnswer.Action.HANGUP, "好的，再见！");

            default:
                logger.error("[CommandHandler] 未知动作码: " + code);
                return new ChatAnswer(-1, "暂不支持该指令", result);
        }
    }
}
