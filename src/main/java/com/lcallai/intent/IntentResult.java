package com.lcallai.intent;

/**
 * 意图分类结果数据载体（不可变对象）
 * 使用 Builder 模式构造，字段职责严格分离：
 *   - refinedQuery : 仅承载自然语言（QUERY 时补全，其余为原文）
 *   - actionCode   : 仅承载标准化动作码（COMMAND 时有效）
 *   - subIntent    : ACK 细分（affirm / negate / ack）
 *   - sentiment    : 情感极性（FEEDBACK 时有效）
 */
public class IntentResult {

    // ── 枚举定义 ─────────────────────────────────────────────────────────

    public enum Intent {
        /** 寻求答案，触发 RAG 检索链路 */
        QUERY,
        /** 系统指令，触发 action_code 执行 */
        COMMAND,
        /** 陈述背景信息，存入上下文 */
        INFORM,
        /** 情感评价（好评/吐槽），触发情绪处理 */
        FEEDBACK,
        /** 确认/接收（含肯定/否定子类型） */
        ACK,
        /** 问候或告别 */
        GREETING,
        /** 闲聊，触发生成式自由回复 */
        CHITCHAT
    }

    public enum Sentiment {
        POSITIVE,
        NEGATIVE,
        NEUTRAL
    }

    // ── 字段 ─────────────────────────────────────────────────────────────

    /** 主意图枚举 */
    public final Intent    intent;

    /**
     * ACK 子类型：affirm（肯定）/ negate（否定）/ ack（普通确认）
     * 非 ACK 时为 null
     */
    public final String    subIntent;

    /**
     * 情感极性，仅 FEEDBACK 时有效
     */
    public final Sentiment sentiment;

    /**
     * QUERY 时：经过指代词补全的完整疑问句（供 RAG 直接使用）
     * 其他意图：返回用户原始输入，不做修改
     */
    public final String    refinedQuery;

    /**
     * COMMAND 时：标准化动作码
     * 可选值：ACTION_REPLAY / ACTION_TRANSFER / ACTION_VOL_UP / ACTION_VOL_DOWN
     * 非 COMMAND 时为 null
     */
    public final String    actionCode;

    // ── 构造 ─────────────────────────────────────────────────────────────

    private IntentResult(Builder b) {
        this.intent       = b.intent;
        this.subIntent    = b.subIntent;
        this.sentiment    = b.sentiment;
        this.refinedQuery = b.refinedQuery;
        this.actionCode   = b.actionCode;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    /** 是否需要走 RAG 知识库检索 */
    public boolean isQuestion() {
        return intent == Intent.QUERY;
    }

    @Override
    public String toString() {
        return "IntentResult{" +
                "intent=" + intent +
                ", subIntent='" + subIntent + '\'' +
                ", sentiment=" + sentiment +
                ", refinedQuery='" + refinedQuery + '\'' +
                ", actionCode='" + actionCode + '\'' +
                '}';
    }

    // ── Builder ───────────────────────────────────────────────────────────

    public static Builder builder(Intent intent) {
        return new Builder(intent);
    }

    public static class Builder {
        private final Intent intent;
        private String    subIntent;
        private Sentiment sentiment    = Sentiment.NEUTRAL;
        private String    refinedQuery = "";
        private String    actionCode;

        Builder(Intent intent) {
            this.intent = intent;
        }

        public Builder subIntent(String v)    { this.subIntent    = v; return this; }
        public Builder sentiment(Sentiment v) { this.sentiment    = v; return this; }
        public Builder refinedQuery(String v) { this.refinedQuery = v; return this; }
        public Builder actionCode(String v)   { this.actionCode   = v; return this; }

        public IntentResult build() {
            return new IntentResult(this);
        }
    }
}
