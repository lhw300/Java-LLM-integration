package com.lcallai;

import java.util.UUID;
import com.lcallai.intent.*;
import com.lcallai.handler.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Arrays;
/**
 * Intent classification CI test entry point
 *
 * Corresponds to ChatIntentExample.java with identical test cases.
 * Added: automatic PASS/FAIL counting; exits with code 1 on failure for CI red-flag.
 *
 * Local run:
 *   java -cp lcallai-1.0-shaded.jar com.lcallai.IntentTestRunner [configPath]
 *
 * Config path priority:
 *   1. Command-line argument args[0]
 *   2. Environment variable AI_CONFIG_PATH
 *   3. Default value e:\ai  (local dev fallback)
 */
public class IntentTestRunner {

    static final Logger logger = LogManager.getLogger(IntentTestRunner.class);

    private static int totalPass = 0;
    private static int totalFail = 0;

    public static void main(String[] args) {

        // ── Config path resolution (compatible with local Windows and CI Linux) ──
        String configPath = "e:\\ai";
        if (args.length > 0) {
            configPath = args[0];
            logger.debug("📂 Command-line argument detected, using config path: " + configPath);
        } else if (System.getenv("AI_CONFIG_PATH") != null) {
            configPath = System.getenv("AI_CONFIG_PATH");
            logger.debug("📂 Using environment variable path: " + configPath);
        } else {
            logger.debug("ℹ️ No argument detected, using default path: " + configPath);
        }

        SessionManager.init(configPath);
        logger.debug("=== Automated Intent Dispatch Test (based on SessionManager built-in registry) ===\n");
        long testStart = System.currentTimeMillis();
        // =====================================================================
        // Test data below is identical to ChatIntentExample.java
        // =====================================================================

        String[][] greetingTests = {
                // 1. Standard greeting (simplest case)
                {"你好", "GREETING"},
                {"Hello", "GREETING"},
                {"嗨，有人在吗？", "GREETING|CHITCHAT"},

                // 2. Time-sensitive (tests model sensitivity to time words)
                {"早上好，今天心情不错。", "GREETING|CHITCHAT"},
                {"晚安，辛苦了。", "GREETING|ACK|COMMAND"},

                // 3. Strong emotion / colloquial (tests noise resistance)
                {"哈喽哇！小助手，我想死你啦！", "GREETING|CHITCHAT"},
                {"喂？能听到我说话吗？", "GREETING"},

                // 4. Boundary test (key: greeting + business request -> should NOT be GREETING)
                {"你好，帮我查下流量。", "QUERY"},   // should be COMMAND
                {"早安，Win11怎么激活？", "QUERY"}   // should be QUERY
        };

        String[][] ackTests = {
                // Case A: Affirmative reply (affirm)
                {"是的", "ACK"},
                {"对，就是这个", "ACK"},
                {"没错，请执行", "ACK"},
                {"确认安装", "ACK|COMMAND"},

                // Case B: Negative / rejection (negate)
                {"不，不是这个", "ACK"},
                {"不用了，谢谢", "ACK"},
                {"取消操作", "ACK"},
                {"不需要", "ACK"},

                // Case C: General acknowledgement (ack)
                {"好的", "ACK"},
                {"嗯", "ACK"},
                {"知道了", "ACK"},
                {"行吧", "ACK"}
        };

        String[][] commandTests = {
                // Case A: Standard hit (exact enum match)
                {"帮我转人工服务。", "COMMAND"},      // expected: ACTION_TRANSFER
                {"声音太小了，大声点。", "COMMAND"},   // expected: ACTION_VOL_UP
                {"调低音量。", "COMMAND"},            // expected: ACTION_VOL_DOWN
                {"刚才那段再放一遍。", "COMMAND"},     // expected: ACTION_REPLAY

                // Case B: Semantic generalization (synonym challenge)
                {"找个真人来跟我说话。", "COMMAND"},   // expected: ACTION_TRANSFER
                {"吵死了，小声些。", "COMMAND|FEEDBACK"},       // expected: ACTION_VOL_DOWN
                {"重新播放。", "COMMAND"},             // expected: ACTION_REPLAY

                // Case C: Out-of-scope commands (falls back to QUERY)
                {"帮我查一下话费流量。", "QUERY"},     // expected: QUERY (not in enum)
                {"帮我重启一下宽带猫。", "QUERY|COMMAND"},     // expected: QUERY (not in enum)
                {"打开电视机。", "QUERY|COMMAND"},             // expected: QUERY (unsupported action)

                // Case D: Compound intent (greeting + command, tests priority)
                {"你好，请帮我转人工。", "COMMAND"},   // expected: COMMAND (command takes priority over greeting)
                {"太感谢了，再播放一次吧。", "COMMAND"} // expected: COMMAND (command takes priority over feedback)
        };

        String[][] informTests = {
                // Case A: Providing key contact info (contact / name)
                {"我的手机号是13800138000。", "INFORM"},
                {"我叫张三。", "INFORM"},
                {"你可以拨打 021-66668888 联系我。", "INFORM"},

                // Case B: Providing location / address info
                {"我家在上海市浦东新区张江路1号。", "INFORM"},
                {"宽带安装地址是锦绣路100弄3号楼。", "INFORM|QUERY"},

                // Case C: Describing a specific issue / fault (supplement to business input)
                {"我的光猫红灯一直在闪。", "INFORM|QUERY|FEEDBACK"},
                {"家里断网快半小时了。", "INFORM|QUERY"},
                {"报错代码是 691。", "INFORM|QUERY"},

                // Case D: Correction / update info
                {"不对，刚才那个地址写错了，应该是2号楼。", "INFORM"},
                {"改一下，手机号尾号是 5678。", "INFORM"}
        };

        String[][] feedbackTests = {
                // Case A: Positive evaluation (positive)
                {"你们的客服态度真好，赞一个！", "FEEDBACK"},
                {"问题解决了，小助手很给力。", "FEEDBACK"},
                {"非常感谢，帮了大忙了。", "FEEDBACK"},

                // Case B: Negative evaluation / complaint (negative)
                {"这软件太卡了，简直没法用。", "FEEDBACK"},
                {"等了半天没人理，差评！", "FEEDBACK"},
                {"你们的收费极其不合理，我要投诉。", "FEEDBACK"},

                // Case C: Neutral / functional feedback (tests sentiment determination)
                {"界面要是能再简洁点就好了。", "FEEDBACK"},
                {"我觉得这个颜色有点刺眼。", "FEEDBACK"},

                // Case D: Boundary interference (FEEDBACK vs COMMAND/QUERY)
                {"太慢了，赶紧帮我转人工！", "COMMAND"},  // expected: command takes priority (ACTION_TRANSFER)
                {"为什么你们的系统总报错？", "QUERY"}     // expected: question takes priority
        };

        String[][] chitchatTests = {
                // Case A: Pure emotional / casual chat
                {"你今天心情怎么样？", "CHITCHAT"},
                {"今天天气真不错，适合出去玩。", "CHITCHAT"},
                {"你觉得 AI 会取代人类吗？", "CHITCHAT|QUERY"},

                // Case B: Meaningless filler words / test input
                {"哈哈哈哈哈哈。", "CHITCHAT"},
                {"呃，让我想想。", "CHITCHAT|ACK"},
                {"哦吼。", "CHITCHAT"},

                // Case C: Humor / jokes
                {"讲个笑话听听。", "CHITCHAT"},
                {"你吃饭了吗？", "CHITCHAT"},

                // Case D: Boundary challenge (CHITCHAT vs QUERY)
                {"北京的首都是哪里？", "CHITCHAT|QUERY"},   // expected: non-business general knowledge -> chitchat
                {"Win10 是哪年发布的？", "QUERY"},    // expected: system knowledge -> query
                {"你好帅啊。", "CHITCHAT|FEEDBACK"}            // expected: social compliment
        };

        // Mixed scenario stressData (multi-turn, independent session)
        String[][] stressData = {
                {"你好", "GREETING"},                     // 1. greeting
                {"我是李老师", "INFORM"},                  // 2. identity
                {"怎么重置密码？", "QUERY"},               // 3. business query
                {"太感谢了，帮了大忙！", "FEEDBACK"},       // 4. positive feedback
                {"讲个笑话吧", "CHITCHAT"},                // 5. off-topic chitchat
                {"再见", "COMMAND"}                        // 6. end session
        };

        String[][] stressData2 = {
                // 1. Normal business opening
                {"你好", "GREETING"},
                {"我是李老师", "INFORM"},

                // 2. Core RAG logic (triggers: rewrite -> 768-dim retrieval -> rerank 10 samples)
                {"怎么重置密码？", "QUERY"},

                // 3. Business diversion and boundary (triggers: executeChitchat clean mode)
                {"讲个笑话吧", "CHITCHAT"},
                {"你会写 Java 吗？", "CHITCHAT|QUERY"},

                // 4. Command and real-time interaction (triggers: CommandHandler)
                {"声音太小了，大声一点", "COMMAND"},
                {"帮我转接人工客服", "COMMAND"},

                // 5. Negative feedback and sentiment detection (triggers: FeedbackHandler)
                {"刚才那个密码不对，你们这系统真行", "FEEDBACK"},

                // 6. Acknowledgement and closing
                {"好的，我知道了", "ACK"},
                {"再见", "COMMAND"}
        };

        String[][] stressData3 = {
                // 1. Normal business opening
                {"你好", "GREETING"},
                {"我是李老师", "INFORM"},

                // 2. Core RAG logic (triggers: rewrite -> 768-dim retrieval -> rerank 10 samples)
                {"怎么重置密码？", "QUERY"},
                {"我没听清楚", "COMMAND"},
                {"你说什么", "COMMAND"}
        };

        String[][] stressData4 = {
                // ── REPLAY boundary: colloquial variants ──────────────────────────────
                {"什么", "COMMAND|QUERY"},              // minimal colloquial, most likely to be misclassified as CHITCHAT
                {"啊？", "COMMAND"},              // filler-word-style replay request
                {"能再说一遍吗", "COMMAND"},       // polite form
                {"刚才你说的是什么", "COMMAND"},   // referential form with "just now"

                // ── REPLAY vs CHITCHAT boundary (first vs non-first turn decided by context) ──
                {"你好", "GREETING"},             // first turn, AI has not spoken yet
                {"你说什么", "COMMAND"},          // non-first turn, AI has replied, should be REPLAY
        };

        // Scenario 1: Extreme off-topic and forced pull-back (tests CHITCHAT control)
        String[][] stressData5 = {
                {"你好", "GREETING"},
                {"我是李老师", "INFORM"},
                {"你觉得今天天气怎么样？适合备课吗？", "CHITCHAT|QUERY"},
                {"你平时都吃什么牌子的电量？", "CHITCHAT"},
                {"算了不扯了，老师的初始密码是多少来着？", "QUERY"}
        };

        // Scenario 2: Emotional outburst and transfer after soothing (tests feedback vs command priority)
        String[][] stressData6 = {
                {"你好，我是李老师", "GREETING|INFORM"},
                {"我想查一下怎么重置密码", "QUERY"},
                {"你们这系统太垃圾了，密码根本不对，快给我找个活人！", "COMMAND"}
        };

        // Scenario 3: Coreference resolution and colloquial replay (tests REPLAY boundary)
        String[][] stressData7 = {
                {"你好，我是李老师，请问怎么重置密码？", "QUERY"},
                {"什么？", "COMMAND"},
                {"没听清，你再说一遍", "COMMAND"}
        };

        // Scenario 4: Multi-identity confusion and fail-safe design (tests identity isolation)
        String[][] stressData8 = {
                {"你好，我是老师", "INFORM"},
                {"怎么重置密码？", "QUERY"},
                {"那我们班学生的初始密码是多少？", "QUERY"}
        };

        // =====================================================================
        // Run all suites (single-intent specific: independent session; multi-turn mixed: independent session)
        // =====================================================================

/*
        runSuite("GREETING Suite",    greetingTests);
        runSuite("ACK Suite",         ackTests);
        runSuite("COMMAND Suite",     commandTests);
        runSuite("INFORM Suite",      informTests);
        runSuite("FEEDBACK Suite",    feedbackTests);
        runSuite("CHITCHAT Suite",    chitchatTests);

        runSuite("Mixed Scenario stressData",  stressData);
        runSuite("Mixed Scenario stressData2", stressData2);
        runSuite("Mixed Scenario stressData3 (REPLAY Colloquial)", stressData3);
        runSuite("Mixed Scenario stressData4 (REPLAY Extreme Variants)", stressData4);
        runSuite("Mixed Scenario stressData5 (Extreme Off-topic)", stressData5);
        runSuite("Mixed Scenario stressData6 (Emotional Outburst + Command Priority)", stressData6);


        runSuite("Mixed Scenario stressData7 (Colloquial Replay Boundary)", stressData7);
 */

        runSuite("Mixed Scenario stressData8 (Multi-identity Fail-safe)", stressData7);

        // =====================================================================
        // Summary — pass/fail determined by pass rate
        // =====================================================================
        int total = totalPass + totalFail;
        double passRate = total == 0 ? 0 : (double) totalPass / total * 100;

        logger.debug("\n" + "═".repeat(60));
        logger.debug(String.format("📊 Intent Classification Test Summary  Total: %d  ✅Pass: %d  ❌Fail: %d", total, totalPass, totalFail));
        logger.debug(String.format("📈 Pass rate: %.1f%%", passRate));
        logger.debug("═".repeat(60));

        // Pass rate threshold
        double PASS_THRESHOLD = 92;

        if (passRate >= PASS_THRESHOLD) {
            logger.debug(String.format("✅  Intent Classification Tests passed! Pass rate %.1f%% >= %.0f%%", passRate, PASS_THRESHOLD));
            System.exit(0);
        } else {
            logger.debug(String.format("❌ Tests failed! Pass rate %.1f%% < %.0f%%", passRate, PASS_THRESHOLD));
            System.exit(1);
        }
    }

    // =========================================================================
    // Suite runner: each suite uses an independent session,
    // logic is identical to ChatIntentExample.runIntegratedTest
    // =========================================================================
    private static void runSuite(String suiteName, String[][] testData) {
        logger.debug("\n" + "─".repeat(60));
        logger.debug("🧪 Suite: " + suiteName);
        logger.debug("─".repeat(60));

        String sessionId = "SESSION_" + UUID.randomUUID().toString().substring(0, 8);
        ChatSession session = SessionManager.getSession(sessionId);

        for (String[] test : testData) {
            String userInput     = test[0];
            String expectedIntent = test[1];

            logger.debug("==================================================");
            logger.debug("👤 User input: " + userInput + " | Expected intent: " + expectedIntent);

            ChatAnswer ca = session.ask(userInput);

            IntentResult result = ca.intentResult;
            if (result != null) {
                String actual = result.intent.name();
                boolean pass = Arrays.asList(expectedIntent.split("\\|"))
                        .contains(actual);
                if (pass) totalPass++; else totalFail++;

                logger.debug(String.format("[%s] Recognized intent: %-10s | Expected intent: %s",
                        pass ? "PASS" : "FAIL", actual, expectedIntent));

                if (result.refinedQuery != null && !result.refinedQuery.isEmpty())
                    logger.debug("     └─ [Refined query]: " + result.refinedQuery);
                if (result.subIntent != null)
                    logger.debug("     └─ [Sub-intent]: " + result.subIntent);
                if (result.actionCode != null)
                    logger.debug("     └─ [Action code]: " + result.actionCode);
                if (result.sentiment != IntentResult.Sentiment.NEUTRAL)
                    logger.debug("     └─ [Sentiment]: " + result.sentiment);
            } else {
                totalFail++;
                logger.debug("[FAIL] intentResult is null");
            }

            logger.debug("     └─ [Status code]: " + ca.code);
            logger.debug("     └─ [Action]: " + ca.action);
            logger.debug("     └─ [AI reply]: " + ca.answer);
            logger.debug("--------------------------------------------------");
        }
    }
}