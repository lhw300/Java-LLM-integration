package com.lcallai.intent;

import com.lcallai.ChatAnswer;
import com.lcallai.ChatSession;

import java.util.EnumMap;
import java.util.Map;

/**
 * 意图派发器（注册表模式）
 *
 * 用 EnumMap 维护 Intent -> IntentHandler 的映射，
 * 替代原来 ChatSession.ask() 里的 if-else 链。
 *
 * 用法示例（SessionManager 里装配）：
 * <pre>
 *   IntentDispatcher dispatcher = new IntentDispatcher()
 *       .register(IntentResult.Intent.QUERY,    new QueryHandler())
 *       .register(IntentResult.Intent.FEEDBACK, new FeedbackHandler())
 *       .register(IntentResult.Intent.COMMAND,  new CommandHandler())
 *       .register(IntentResult.Intent.ACK,      new AckHandler())
 *       .register(IntentResult.Intent.INFORM,   new InformHandler())
 *       .register(IntentResult.Intent.GREETING, new GreetingHandler())
 *       .register(IntentResult.Intent.CHITCHAT, new ChitchatHandler());
 * </pre>
 */
public class IntentDispatcher {

    private final Map<IntentResult.Intent, IntentHandler> handlers =
            new EnumMap<>(IntentResult.Intent.class);

    /**
     * 注册处理器（支持覆盖，方便测试时注入 mock）
     *
     * @param intent  意图枚举值
     * @param handler 对应的处理器实例
     * @return this，支持链式调用
     */
    public IntentDispatcher register(IntentResult.Intent intent, IntentHandler handler) {
        handlers.put(intent, handler);
        return this;
    }

    /**
     * 根据分类结果派发到对应 Handler
     *
     * @param rawText 用户原始输入
     * @param result  意图分类结果
     * @param session 当前会话
     * @return Handler 的返回值
     */
    public ChatAnswer dispatch(String rawText, IntentResult result, ChatSession session) {
        IntentHandler handler = handlers.get(result.intent);

        if (handler == null) {
            // 未注册的 intent 降级为 QUERY，防止系统报错
            System.err.println("[IntentDispatcher] 未注册的 intent: " + result.intent + "，降级为 QUERY");
            handler = handlers.get(IntentResult.Intent.QUERY);
        }

        if (handler == null) {
            // QUERY 也未注册（配置错误），返回兜底错误
            return new ChatAnswer(-1, "系统配置错误：未找到任何可用的意图处理器");
        }

        return handler.handle(rawText, result, session);
    }

    /** 判断某个 intent 是否已注册（测试/诊断用） */
    public boolean isRegistered(IntentResult.Intent intent) {
        return handlers.containsKey(intent);
    }
}
