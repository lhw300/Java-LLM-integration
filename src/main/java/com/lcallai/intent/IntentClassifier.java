package com.lcallai.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcallai.LlmClient;
import com.lcallai.QueryHistory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 意图分类器
 *
 * 职责：调用轻量 LLM（建议 turbo 级），将用户输入解析为标准化 IntentResult。
 * 设计原则：
 *   - 解析失败时始终降级为 QUERY，确保主流程不中断
 *   - 历史上下文只取最近 3 轮（6 条），降低 Token 成本
 *   - JSON 解析容错：兼容带 ```json``` 包装的模型输出
 */
public class IntentClassifier {
    private static final Logger logger = LogManager.getLogger(IntentClassifier.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 只使用最近 N 条历史（3 轮 = 6 条：3 User + 3 Context）
    private static final int HISTORY_WINDOW = 16;



    private final LlmClient llmClient;
    // 从 static final 常量 → 实例字段，由外部注入
    private final String systemPrompt;


    // 原来的单参数构造保留作兜底（使用硬编码默认值）
    public IntentClassifier(LlmClient llmClient) {
        this(llmClient, null);
    }

    // 新增：支持外部注入 prompt
    public IntentClassifier(LlmClient llmClient, String systemPrompt) {
        this.llmClient = llmClient;
        this.systemPrompt = (systemPrompt != null && !systemPrompt.isBlank())
                ? systemPrompt
                : SYSTEM_PROMPT; // 兜底，防止文件加载失败时系统崩溃
    }

    /**
     * 对用户输入进行意图分类
     *
     * @param userText 用户当前输入
     * @param history  当前会话的查询历史（用于指代词消解）
     * @return 标准化的 IntentResult，解析失败时降级为 QUERY
     */
    // classify 里把 SYSTEM_PROMPT 换成 this.systemPrompt
    public IntentResult classify(String userText, QueryHistory history) {
        try {
            String historyCtx = history.getMessageWindowSize(HISTORY_WINDOW);
            String userPrompt = historyCtx == null || historyCtx.isBlank()
                    ? "用户输入：" + userText
                    : "对话历史：\n" + historyCtx + "\n\n用户最新输入：" + userText;
            logger.debug("[IntentClassifier] 原始输入: " + userPrompt);
            String raw = llmClient.generate(this.systemPrompt, userPrompt); // 改这里
            logger.debug("[IntentClassifier] 原始输出: " + raw);
            return parse(raw, userText);
        } catch (Exception e) {
            logger.error("[IntentClassifier] 分类异常，降级为 QUERY: " + e.getMessage());
            return fallback(userText);
        }
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private IntentResult parse(String raw, String fallbackText) {
        try {
            // 容错：去掉可能的 ```json ... ``` 包装
            String json = raw.replaceAll("(?s)```json|```", "").trim();
            JsonNode node = MAPPER.readTree(json);

            // 解析 intent
            String intentStr = node.path("intent").asText("QUERY").toUpperCase();
            IntentResult.Intent intent;
            try {
                intent = IntentResult.Intent.valueOf(intentStr);
            } catch (IllegalArgumentException ex) {
                logger.error("[IntentClassifier] 未知 intent 值: " + intentStr + "，降级 QUERY");
                return fallback(fallbackText);
            }

            // 解析 sentiment
            IntentResult.Sentiment sentiment = IntentResult.Sentiment.NEUTRAL;
            String sentimentStr = node.path("sentiment").asText("").toLowerCase();
            if ("positive".equals(sentimentStr)) sentiment = IntentResult.Sentiment.POSITIVE;
            else if ("negative".equals(sentimentStr)) sentiment = IntentResult.Sentiment.NEGATIVE;

            // refined_query 不能为空
            String refinedQuery = node.path("refined_query").asText(fallbackText);
            if (refinedQuery.isBlank()) refinedQuery = fallbackText;

            return IntentResult.builder(intent)
                    .subIntent(nullIfBlank(node.path("sub_intent").asText(null)))
                    .sentiment(sentiment)
                    .refinedQuery(refinedQuery)
                    .actionCode(nullIfBlank(node.path("action_code").asText(null)))
                    .build();

        } catch (Exception e) {
            logger.error("[IntentClassifier] JSON 解析失败，降级 QUERY。原始内容: " + raw);
            return fallback(fallbackText);
        }
    }

    /** 兜底：分类失败时降级为 QUERY */
    private IntentResult fallback(String text) {
        return IntentResult.builder(IntentResult.Intent.QUERY)
                .refinedQuery(text)
                .build();
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s;
    }


    private static final String SYSTEM_PROMPT = """
        你是一个对话意图分类器，只输出合法 JSON，禁止输出任何额外文字或 Markdown 标记。
        
        intent 枚举值（必须且只能是以下之一）：
        [QUERY, COMMAND, INFORM, FEEDBACK, ACK, GREETING, CHITCHAT]
        
        输出字段规则：
        1. intent      (string)      必填，枚举值之一,注意：仅当动作完全匹配下述 action_code 枚举时才准许判定为 COMMAND。
        2. sub_intent  (string|null) 仅 ACK 时必填：affirm（肯定）/ negate（否定）/ ack（普通确认）
        3. sentiment   (string|null) 仅 FEEDBACK 时必填：positive / negative
        4. refined_query (string)    必填：
           - QUERY：同时完成以下两件事后输出一个最终问句：
             第一步【指代补全】结合对话历史，将"他""那个""这个"等代词还原为具体实体。
             第二步【检索优化】去掉礼貌语气词，突出核心实体和动词，使问句更适合向量检索。
             例："他的密码忘了怎么办"（上文说的是学生）
               → "学生忘记登录密码如何找回？"  ✓
               → "请问一下他的密码忘了该怎么处理呢" ✗（没补全也没优化）
             例："Win10能装吗"（上文在讨论翔云3.0软件）
               → "翔云3.0支持在Windows10系统上安装吗？"  ✓
           - 其他 intent：直接返回用户原始输入，不做任何修改
        5. action_code (string|null) 仅 COMMAND 时必填，枚举值之一：
           ACTION_REPLAY / ACTION_TRANSFER / ACTION_VOL_UP / ACTION_VOL_DOWN / ACTION_HANGUP
           【重要约束】：只有当用户明确要求“重放/重听 (REPLAY)”、“转人工 (TRANSFER)”或“调节音量 (VOL)”或 再见(HANGUP)时，才允许返回对应的 action_code。若用户意图是执行某个动作，但该动作不在上述枚举范围内，必须将 intent 判定为 QUERY，并将 action_code 设为 null。
               - ACTION_REPLAY：仅在对话历史中已有 AI 回复且用户要求重复/重听上一句话时触发。
                 触发词示例：重放、重听、再说一遍、你刚才说什么、你说什么、没听清、听不清、再说一次、什么、啊？、能再说一遍吗
                 【判断依据】：这些表达的核心含义是"请重复上一句"，与业务知识无关，不需要检索知识库。
                 【反例】：若用户说"你说什么意思"、"你说的什么产品"，含业务实体，判 QUERY。
               - ACTION_TRANSFER：用户明确要求转人工时触发。触发词示例：转人工、找真人、我要投诉、帮我转接
               - ACTION_VOL_UP / ACTION_VOL_DOWN：用户要求调节音量时触发。
               - ACTION_HANGUP：用户明确表示结束通话时触发。 
                   
        6. FEEDBACK 定义： 凡是包含用户对服务、产品或解决结果的主观态度、情绪评价、感谢表扬或抱怨建议的内容，必须判定为 FEEDBACK，并强制标注 sentiment 极性。
        7. 若输入内容无法解析为具体业务意图且字数极少或语义混乱，请统一归类为 CHITCHAT

     
        判定示例：
        输入"好的"     → {"intent":"ACK","sub_intent":"ack","sentiment":null,"refined_query":"好的","action_code":null}
        输入"对就是这个" → {"intent":"ACK","sub_intent":"affirm","sentiment":null,"refined_query":"对就是这个","action_code":null}
        输入"不用了"    → {"intent":"ACK","sub_intent":"negate","sentiment":null,"refined_query":"不用了","action_code":null}
        输入"这软件太卡" → {"intent":"FEEDBACK","sub_intent":null,"sentiment":"negative","refined_query":"这软件太卡","action_code":null}
        输入"再见、拜拜、挂了、不聊了" → {"intent":"COMMAND","sub_intent":null,"sentiment":null,"refined_query":"再见","action_code":"ACTION_HANGUP"}
        输入"Win10能装吗"（上下文在讨论翔云3.0）→ {"intent":"QUERY","sub_intent":null,"sentiment":null,"refined_query":"翔云3.0支持在Windows10系统上安装吗？","action_code":null}
        输入"他的密码忘了"（上文说的是学生）→ {"intent":"QUERY","sub_intent":null,"sentiment":null,"refined_query":"学生忘记登录密码如何找回？","action_code":null}
               
        """;
}
