package com.lcallai.intent;

// ═══════════════════════════════════════════════════════════════════════════════
// ChatSession.java 改造说明
// 本文件展示需要在 ChatSession 中增加/修改的部分，不是完整的 ChatSession 类。
// ═══════════════════════════════════════════════════════════════════════════════

/*
 * ── 第 1 步：增加两个字段（放在现有字段区域）─────────────────────────────────
 *
 *   private IntentClassifier intentClassifier;
 *   private IntentDispatcher intentDispatcher;
 *
 *
 * ── 第 2 步：增加 sessionId 字段和 getter（供 FeedbackHandler 使用）──────────
 *
 *   private final String sessionId;
 *
 *   // 构造函数里增加 sessionId 参数，或在现有构造函数里赋值：
 *   // this.sessionId = tableName + "_" + System.currentTimeMillis();
 *
 *   public String getSessionId() {
 *       return sessionId;
 *   }
 *
 *
 * ── 第 3 步：暴露 queryHistory 的 getter（供 InformHandler 存背景信息）────────
 *
 *   public QueryHistory getQueryHistory() {
 *       return queryHistory;
 *   }
 *
 *
 * ── 第 4 步：增加 setIntentPipeline() 注入方法 ───────────────────────────────
 *
 *   public void setIntentPipeline(IntentClassifier classifier, IntentDispatcher dispatcher) {
 *       this.intentClassifier = classifier;
 *       this.intentDispatcher = dispatcher;
 *   }
 *
 *
 * ── 第 5 步：替换 ask() 方法（这是唯一需要修改的核心方法）────────────────────
 */

/**
 * 替换 ChatSession 中原有的 ask() 方法：
 *
 *   public ChatAnswer ask(String text) {
 *       if (text == null || text.trim().isEmpty()) {
 *           return new ChatAnswer(-1, "输入为空");
 *       }
 *
 *       // 意图分类（调用轻量 LLM，通常 < 500ms）
 *       IntentResult result = intentClassifier.classify(text, queryHistory);
 *       System.out.println("[ask] " + result);
 *
 *       // 按 intent 派发到对应 Handler
 *       return intentDispatcher.dispatch(text, result, this);
 *   }
 *
 *
 * ── ask3() 和 askFullContext() 完全不动 ─────────────────────────────────────
 * 这两个方法继续作为 QueryHandler 和 ChitchatHandler 的内部实现被调用，
 * 外部不再直接使用它们。
 */

// ═══════════════════════════════════════════════════════════════════════════════
// SessionManager.java 改造说明
// ═══════════════════════════════════════════════════════════════════════════════

/*
 * 在 init() 方法末尾、返回之前，追加以下装配代码：
 *
 *
 *   // ── 意图分类管道装配 ────────────────────────────────────────────────
 *   // 分类器使用轻量 turbo 模型，降低延迟和成本
 *   IntentClassifier intentClassifier = new IntentClassifier(ACTIVE_ROUTER.rewriter());
 *
 *   IntentDispatcher intentDispatcher = new IntentDispatcher()
 *       .register(IntentResult.Intent.QUERY,    new QueryHandler())
 *       .register(IntentResult.Intent.FEEDBACK, new FeedbackHandler())
 *       .register(IntentResult.Intent.COMMAND,  new CommandHandler())
 *       .register(IntentResult.Intent.ACK,      new AckHandler())
 *       .register(IntentResult.Intent.INFORM,   new InformHandler())
 *       .register(IntentResult.Intent.GREETING, new GreetingHandler())
 *       .register(IntentResult.Intent.CHITCHAT, new ChitchatHandler());
 *
 *   // 将分类管道存为静态成员，getSession() 时注入到每个新建的 ChatSession
 *   ACTIVE_INTENT_CLASSIFIER = intentClassifier;
 *   ACTIVE_INTENT_DISPATCHER = intentDispatcher;
 *
 *
 * 在 SessionManager 类里增加两个静态字段：
 *
 *   private static IntentClassifier ACTIVE_INTENT_CLASSIFIER;
 *   private static IntentDispatcher ACTIVE_INTENT_DISPATCHER;
 *
 *
 * 在 getSession() 里，新建 ChatSession 后追加注入：
 *
 *   ChatSession session = new ChatSession(ACTIVE_ROUTER, ACTIVE_EMBED, ACTIVE_TABLE);
 *   // ... 现有的 setThresholds、setPrompt 等注入 ...
 *   session.setIntentPipeline(ACTIVE_INTENT_CLASSIFIER, ACTIVE_INTENT_DISPATCHER);
 *   sessions.put(clientId, session);
 *   return session;
 */

// ═══════════════════════════════════════════════════════════════════════════════
// 完整的 import 列表（在 ChatSession.java 顶部增加）
// ═══════════════════════════════════════════════════════════════════════════════

/*
 *   import com.lcallai.intent.IntentClassifier;
 *   import com.lcallai.intent.IntentDispatcher;
 *   import com.lcallai.intent.IntentResult;
 */
public class ChatSessionPatch {
    // 此类仅作说明用途，不需要编译
}
