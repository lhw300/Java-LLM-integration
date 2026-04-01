package com.lcallai;

import com.lcallai.intent.IntentResult;

/**
 * AI 处理结果，透传给 VOIP 应用层的统一响应结构。
 *
 * 设计原则：
 *   - 直接持有 IntentResult，不做任何字段映射，IntentResult 改了这里无需跟着改
 *   - Action 枚举取代原来的 __TRANSFER__ 魔法字符串，VOIP 层按字段判断即可
 *   - 兼容旧代码：保留 code / answer 字段，原有赋值代码不用改
 *
 * VOIP 层典型用法：
 * <pre>
 *   ChatAnswer resp = session.ask("帮我转人工");
 *   tts.speak(resp.answer);
 *   if (resp.action == ChatAnswer.Action.TRANSFER) { sipGateway.transfer(); }
 *   if (resp.shouldTerminate())                    { call.hangup(); }
 * </pre>
 */
public class ChatAnswer {

    // =========================================================================
    // VOIP 动作枚举（取代 __TRANSFER__ 等魔法字符串）
    // =========================================================================

    /**
     * VOIP 层需要执行的通话动作。
     * NONE     → 无特殊动作，正常 TTS 播报 answer 即可
     * TRANSFER → 转接人工客服
     * REPLAY   → 重播上一条 TTS 音频（无需重新合成）
     * VOL_UP   → 调高音量
     * VOL_DOWN → 调低音量
     * HANGUP   → 主动挂断
     */
    public enum Action {
        NONE,
        TRANSFER,
        REPLAY,
        VOL_UP,
        VOL_DOWN,
        HANGUP
    }

    // =========================================================================
    // 状态码常量
    // =========================================================================

    public static final int CODE_OK             =  0;
    public static final int CODE_ERROR          = -1;
    public static final int CODE_NO_KNOWLEDGE   = -100;
    public static final int CODE_LOW_SIMILARITY = -101;

    // =========================================================================
    // 字段
    // =========================================================================

    /** 状态码，0=成功，负数=错误 */
    public int code;

    /** TTS 播报文本，VOIP 层直接送 TTS 引擎 */
    public String answer;

    /**
     * VOIP 动作指令，默认 NONE。
     * TRANSFER / HANGUP 时上层执行完动作后再决定是否播 answer。
     */
    public Action action = Action.NONE;

    /**
     * 本轮完整的意图分类结果。
     * VOIP 层可按需取用：
     *   intentResult.intent      → 意图大类（QUERY/COMMAND/FEEDBACK...）
     *   intentResult.sentiment   → 情感极性（POSITIVE/NEGATIVE/NEUTRAL）
     *   intentResult.subIntent   → ACK 子类型（affirm/negate/ack）
     *   intentResult.actionCode  → 原始动作码字符串（ACTION_TRANSFER 等）
     *   intentResult.refinedQuery → 指代消解后的优化查询句（质检/日志用）
     * 若在意图分类之前就出错（如输入为空），此字段为 null。
     */
    public IntentResult intentResult;

    // =========================================================================
    // 构造函数
    // =========================================================================

    /** 兼容旧代码的基础构造 */
    public ChatAnswer(int code, String answer) {
        this.code   = code;
        this.answer = answer;
    }

    /** 携带意图结果的构造 */
    public ChatAnswer(int code, String answer, IntentResult intentResult) {
        this.code         = code;
        this.answer       = answer;
        this.intentResult = intentResult;
    }

    /** 携带意图结果 + 动作指令的构造 */
    public ChatAnswer(int code, String answer, IntentResult intentResult, Action action) {
        this.code         = code;
        this.answer       = answer;
        this.intentResult = intentResult;
        this.action       = action;
    }

    // =========================================================================
    // 工厂方法（Handler 内部推荐使用，语义清晰）
    // =========================================================================

    /** 正常问答响应 */
    public static ChatAnswer ofAnswer(IntentResult ir, String answerText) {
        return new ChatAnswer(CODE_OK, answerText, ir, Action.NONE);
    }

    /** 动作指令响应（CommandHandler 用） */
    public static ChatAnswer ofAction(IntentResult ir, Action action, String tipsText) {
        return new ChatAnswer(CODE_OK, tipsText != null ? tipsText : "", ir, action);
    }

    /** 错误/兜底响应 */
    public static ChatAnswer ofError(int code, String fallbackText) {
        return new ChatAnswer(code, fallbackText);
    }

    /** 知识库未命中 */
    public static ChatAnswer ofNoKnowledge() {
        return ofError(CODE_NO_KNOWLEDGE,
                "抱歉，我在知识库中未找到相关信息，您可以换一种方式描述，或转人工为您服务。");
    }

    // =========================================================================
    // 语义化便捷方法（VOIP 层用）
    // =========================================================================

    /** 是否需要结束本侧通话（转人工或主动挂断） */
    public boolean shouldTerminate() {
        return action == Action.TRANSFER || action == Action.HANGUP;
    }

    /** 是否需要 TTS 合成（REPLAY 直接重放缓冲区，不需要重新合成） */
    public boolean needsTts() {
        return action != Action.REPLAY;
    }

    /** 快捷判断情感是否为负面（FeedbackHandler 触发转人工时上层可用） */
    public boolean isNegativeFeedback() {
        return intentResult != null
                && intentResult.sentiment == IntentResult.Sentiment.NEGATIVE;
    }

    // =========================================================================
    // toString（调试用）
    // =========================================================================

    @Override
    public String toString() {
        String intent = intentResult != null ? intentResult.intent.name() : "null";
        return "ChatAnswer{code=" + code
                + ", action=" + action
                + ", intent=" + intent
                + ", answer='" + answer + "'}";
    }
}
