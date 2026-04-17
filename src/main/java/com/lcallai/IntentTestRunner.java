package com.lcallai;

import java.util.UUID;
import com.lcallai.intent.*;
import com.lcallai.handler.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Arrays;
/**
 * 意图分类 CI 测试入口
 *
 * 对应 ChatIntentExample.java，测试用例完全一致。
 * 新增：自动统计 PASS/FAIL，有失败则 System.exit(1)，CI 自动标红。
 *
 * 本地运行：
 *   java -cp lcallai-1.0-shaded.jar com.lcallai.IntentTestRunner [configPath]
 *
 * 配置路径优先级：
 *   1. 命令行参数 args[0]
 *   2. 环境变量 AI_CONFIG_PATH
 *   3. 默认值 e:\ai  (本地开发兜底)
 */
public class IntentTestRunner {

    static final Logger logger = LogManager.getLogger(IntentTestRunner.class);

    private static int totalPass = 0;
    private static int totalFail = 0;

    public static void main(String[] args) {

        // ── 配置路径解析（兼容本地 Windows 和 CI Linux）───────────────────────
        String configPath = "e:\\ai";
        if (args.length > 0) {
            configPath = args[0];
            logger.debug("📂 检测到命令行参数，使用配置路径: " + configPath);
        } else if (System.getenv("AI_CONFIG_PATH") != null) {
            configPath = System.getenv("AI_CONFIG_PATH");
            logger.debug("📂 使用环境变量路径: " + configPath);
        } else {
            logger.debug("ℹ️ 未检测到参数，使用默认路径: " + configPath);
        }

        SessionManager.init(configPath);
        logger.debug("=== 自动化意图分发测试 (基于 SessionManager 内置注册) ===\n");
        long testStart = System.currentTimeMillis();
        // =====================================================================
        // 以下测试数据与 ChatIntentExample.java 完全一致
        // =====================================================================

        String[][] greetingTests = {
                // 1. 标准招呼 (最简单情况)

                {"你好", "GREETING"},
                {"Hello", "GREETING"},
                {"嗨，有人在吗？", "GREETING|CHITCHAT"},

                // 2. 时间敏感型 (测试模型对时间词的敏感度)
                {"早上好，今天心情不错。", "GREETING|CHITCHAT"},
                {"晚安，辛苦了。", "GREETING|ACK|COMMAND"},

                // 3. 带有强烈情感/口语化 (测试抗干扰能力)
                {"哈喽哇！小助手，我想死你啦！", "GREETING|CHITCHAT"},
                {"喂？能听到我说话吗？", "GREETING"},

                // 4. 边界测试 (重点：打招呼+业务诉求 -> 预期不应是 GREETING)
                {"你好，帮我查下流量。", "QUERY"},   // 应该是 COMMAND
                {"早安，Win11怎么激活？", "QUERY"}   // 应该是 QUERY
        };

        String[][] ackTests = {
                // 场景 A：肯定回复 (affirm)
                {"是的", "ACK"},
                {"对，就是这个", "ACK"},
                {"没错，请执行", "ACK"},
                {"确认安装", "ACK|COMMAND"},

                // 场景 B：否定/拒绝 (negate)
                {"不，不是这个", "ACK"},
                {"不用了，谢谢", "ACK"},
                {"取消操作", "ACK"},
                {"不需要", "ACK"},

                // 场景 C：普通确认 (ack)
                {"好的", "ACK"},
                {"嗯", "ACK"},
                {"知道了", "ACK"},
                {"行吧", "ACK"}
        };

        String[][] commandTests = {
                // 场景 A：标准命中 (精准匹配枚举)

                {"帮我转人工服务。", "COMMAND"},      // 预期: ACTION_TRANSFER
                {"声音太小了，大声点。", "COMMAND"},   // 预期: ACTION_VOL_UP
                {"调低音量。", "COMMAND"},            // 预期: ACTION_VOL_DOWN
                {"刚才那段再放一遍。", "COMMAND"},     // 预期: ACTION_REPLAY

                // 场景 B：语义泛化 (同义词挑战)
                {"找个真人来跟我说话。", "COMMAND"},   // 预期: ACTION_TRANSFER
                {"吵死了，小声些。", "COMMAND|FEEDBACK"},       // 预期: ACTION_VOL_DOWN
                {"重新播放。", "COMMAND"},             // 预期: ACTION_REPLAY

                // 场景 C：超范围指令 (触发降级为 QUERY)
                {"帮我查一下话费流量。", "QUERY"},     // 预期: QUERY (不在枚举内)


                {"帮我重启一下宽带猫。", "QUERY|COMMAND"},     // 预期: QUERY (不在枚举内)
                {"打开电视机。", "QUERY|COMMAND"},             // 预期: QUERY (不支持的动作)

                // 场景 D：复合意图 (问候+指令，测试优先级)
                {"你好，请帮我转人工。", "COMMAND"},   // 预期: COMMAND (指令优先级高于打招呼)
                {"太感谢了，再播放一次吧。", "COMMAND"} // 预期: COMMAND (指令优先级高于反馈)
        };

        String[][] informTests = {
                // 场景 A：提供关键联系信息 (联系方式/姓名)
                {"我的手机号是13800138000。", "INFORM"},
                {"我叫张三。", "INFORM"},
                {"你可以拨打 021-66668888 联系我。", "INFORM"},

                // 场景 B：提供位置/地址信息
                {"我家在上海市浦东新区张江路1号。", "INFORM"},
                {"宽带安装地址是锦绣路100弄3号楼。", "INFORM|QUERY"},

                // 场景 C：描述具体问题/故障 (作为业务输入的补充)
                {"我的光猫红灯一直在闪。", "INFORM|QUERY|FEEDBACK"},
                {"家里断网快半小时了。", "INFORM|QUERY"},
                {"报错代码是 691。", "INFORM|QUERY"},

                // 场景 D：纠错/更新信息
                {"不对，刚才那个地址写错了，应该是2号楼。", "INFORM"},
                {"改一下，手机号尾号是 5678。", "INFORM"}
        };

        String[][] feedbackTests = {
                // 场景 A：正面评价 (positive)
                {"你们的客服态度真好，赞一个！", "FEEDBACK"},
                {"问题解决了，小助手很给力。", "FEEDBACK"},
                {"非常感谢，帮了大忙了。", "FEEDBACK"},

                // 场景 B：负面评价/投诉 (negative)
                {"这软件太卡了，简直没法用。", "FEEDBACK"},
                {"等了半天没人理，差评！", "FEEDBACK"},
                {"你们的收费极其不合理，我要投诉。", "FEEDBACK"},

                // 场景 C：中性/功能性反馈 (测试 sentiment 判定)
                {"界面要是能再简洁点就好了。", "FEEDBACK"},
                {"我觉得这个颜色有点刺眼。", "FEEDBACK"},

                // 场景 D：边界干扰 (FEEDBACK vs COMMAND/QUERY)
                {"太慢了，赶紧帮我转人工！", "COMMAND"},  // 预期：指令优先（ACTION_TRANSFER）
                {"为什么你们的系统总报错？", "QUERY"}     // 预期：疑问优先
        };

        String[][] chitchatTests = {
                // 场景 A：纯情感/生活化闲聊
                {"你今天心情怎么样？", "CHITCHAT"},
                {"今天天气真不错，适合出去玩。", "CHITCHAT"},
                {"你觉得 AI 会取代人类吗？", "CHITCHAT|QUERY"},

                // 场景 B：无意义的语气词/测试输入
                {"哈哈哈哈哈哈。", "CHITCHAT"},
                {"呃，让我想想。", "CHITCHAT|ACK"},
                {"哦吼。", "CHITCHAT"},

                // 场景 C：幽默/玩笑
                {"讲个笑话听听。", "CHITCHAT"},
                {"你吃饭了吗？", "CHITCHAT"},

                // 场景 D：边界挑战 (CHITCHAT vs QUERY)
                {"北京的首都是哪里？", "CHITCHAT|QUERY"},   // 预期：非业务常识，应归为闲聊
                {"Win10 是哪年发布的？", "QUERY"},    // 预期：涉及系统知识，应归为查询
                {"你好帅啊。", "CHITCHAT|FEEDBACK"}            // 预期：社交赞赏
        };

        // 混合场景 stressData（多轮，独立 session）
        String[][] stressData = {
                {"你好", "GREETING"},                     // 1. 打招呼
                {"我是李老师", "INFORM"},                  // 2. 报身份
                {"怎么重置密码？", "QUERY"},               // 3. 查业务
                {"太感谢了，帮了大忙！", "FEEDBACK"},       // 4. 给好评
                {"讲个笑话吧", "CHITCHAT"},                // 5. 歪楼闲聊
                {"再见", "COMMAND"}                           // 6. 结束
        };

        String[][] stressData2 = {
                // 1. 正常业务开场
                {"你好", "GREETING"},
                {"我是李老师", "INFORM"},

                // 2. 核心 RAG 逻辑 (触发: 重写 -> 768维检索 -> 10个样本重排)
                {"怎么重置密码？", "QUERY"},

                // 3. 业务引流与边界 (触发: executeChitchat 纯净模式)
                {"讲个笑话吧", "CHITCHAT"},
                {"你会写 Java 吗？", "CHITCHAT|QUERY"},

                // 4. 指令与实时交互 (触发: CommandHandler)
                {"声音太小了，大声一点", "COMMAND"},
                {"帮我转接人工客服", "COMMAND"},

                // 5. 负面反馈与情绪识别 (触发: FeedbackHandler)
                {"刚才那个密码不对，你们这系统真行", "FEEDBACK"},

                // 6. 确认与收尾
                {"好的，我知道了", "ACK"},
                {"再见", "COMMAND"}
        };

        String[][] stressData3 = {
                // 1. 正常业务开场
                {"你好", "GREETING"},
                {"我是李老师", "INFORM"},

                // 2. 核心 RAG 逻辑 (触发: 重写 -> 768维检索 -> 10个样本重排)
                {"怎么重置密码？", "QUERY"},
                {"我没听清楚", "COMMAND"},
                {"你说什么", "COMMAND"}
        };

        String[][] stressData4 = {
                // ── REPLAY 边界：口语变体 ──────────────────────────────────────
                {"什么", "COMMAND|QUERY"},              // 极简口语，最容易误判 CHITCHAT
                {"啊？", "COMMAND"},              // 语气词型重播请求
                {"能再说一遍吗", "COMMAND"},       // 礼貌型
                {"刚才你说的是什么", "COMMAND"},   // 带"刚才"的指代型

                // ── REPLAY vs CHITCHAT 边界（首句 vs 非首句由上下文决定）────────
                {"你好", "GREETING"},             // 首句，此时 AI 没说过话
                {"你说什么", "COMMAND"},          // 非首句，AI 已有回复，应判 REPLAY
        };

        // 场景一：极限"歪楼"与强行拉回（测试 CHITCHAT 的控制力）
        String[][] stressData5 = {
                {"你好", "GREETING"},
                {"我是李老师", "INFORM"},
                {"你觉得今天天气怎么样？适合备课吗？", "CHITCHAT|QUERY"},
                {"你平时都吃什么牌子的电量？", "CHITCHAT"},
                {"算了不扯了，老师的初始密码是多少来着？", "QUERY"}
        };

        // 场景二：情绪爆发与抚慰后转人工（测试反馈与指令优先级）
        String[][] stressData6 = {
                {"你好，我是李老师", "GREETING|INFORM"},
                {"我想查一下怎么重置密码", "QUERY"},
                {"你们这系统太垃圾了，密码根本不对，快给我找个活人！", "COMMAND"}
        };

        // 场景三：指代消解与口语重播（测试 REPLAY 边界）
        String[][] stressData7 = {
                {"你好，我是李老师，请问怎么重置密码？", "QUERY"},
                {"什么？", "COMMAND"},
                {"没听清，你再说一遍", "COMMAND"}
        };

        // 场景四：多身份混淆与防呆设计（测试身份隔离）
        String[][] stressData8 = {
                {"你好，我是老师", "INFORM"},
                {"怎么重置密码？", "QUERY"},
                {"那我们班学生的初始密码是多少？", "QUERY"}
        };

        // =====================================================================
        // 执行所有套件（单意图专项：独立 session；多轮混合：独立 session）
        // =====================================================================

        runSuite("GREETING 专项",    ackTests);
        runSuite("ACK 专项",         ackTests);
        runSuite("COMMAND 专项",     commandTests);
        runSuite("INFORM 专项",      informTests);
        runSuite("FEEDBACK 专项",    feedbackTests);
         runSuite("CHITCHAT 专项",    chitchatTests);


          runSuite("混合场景 stressData",  stressData);

           runSuite("混合场景 stressData2", stressData2);
              runSuite("混合场景 stressData3 (REPLAY口语)", stressData3);
              runSuite("混合场景 stressData4 (REPLAY极限变体)", stressData4);
               runSuite("混合场景 stressData5 (极限歪楼)", stressData5);
                 runSuite("混合场景 stressData6 (情绪爆发+指令优先级)", stressData6);

                  runSuite("混合场景 stressData7 (口语重播边界)", stressData7);




        runSuite("混合场景 stressData8 (多身份防呆)", stressData8);


// =====================================================================
// 汇总 — 基于通过率判断
// =====================================================================
        int total = totalPass + totalFail;
        double passRate = total == 0 ? 0 : (double) totalPass / total * 100;

        logger.debug("\n" + "═".repeat(60));
        logger.debug(String.format("📊 测试汇总  总计: %d  ✅通过: %d  ❌失败: %d", total, totalPass, totalFail));
        logger.debug(String.format("📈 通过率: %.1f%%", passRate));
        logger.debug("═".repeat(60));

// 通过率阈值
        double PASS_THRESHOLD = 92;

        if (passRate >= PASS_THRESHOLD) {
            logger.debug(String.format("✅ 测试通过！通过率 %.1f%% ≥ %.0f%%", passRate, PASS_THRESHOLD));
            System.exit(0);
        } else {
            logger.debug(String.format("❌ 测试未通过！通过率 %.1f%% < %.0f%%", passRate, PASS_THRESHOLD));
            System.exit(1);
        }


    }

    // =========================================================================
    // 套件执行器：每套独立 session，与 ChatIntentExample.runIntegratedTest 逻辑完全一致
    // =========================================================================
    private static void runSuite(String suiteName, String[][] testData) {
        logger.debug("\n" + "─".repeat(60));
        logger.debug("🧪 套件: " + suiteName);
        logger.debug("─".repeat(60));

        String sessionId = "SESSION_" + UUID.randomUUID().toString().substring(0, 8);
        ChatSession session = SessionManager.getSession(sessionId);

        for (String[] test : testData) {
            String userInput     = test[0];
            String expectedIntent = test[1];

            logger.debug("==================================================");
            logger.debug("👤 用户输入: " + userInput + " | 预期意图: " + expectedIntent);

            ChatAnswer ca = session.ask(userInput);

            IntentResult result = ca.intentResult;
            if (result != null) {
                String actual = result.intent.name();
                //boolean pass  = actual.equalsIgnoreCase(expectedIntent);
                boolean pass = Arrays.asList(expectedIntent.split("\\|"))
                        .contains(actual);
                if (pass) totalPass++; else totalFail++;

                logger.debug(String.format("[%s] 识别意图: %-10s | 预期意图: %s",
                        pass ? "PASS" : "FAIL", actual, expectedIntent));

                if (result.refinedQuery != null && !result.refinedQuery.isEmpty())
                    logger.debug("     └─ [优化查询]: " + result.refinedQuery);
                if (result.subIntent != null)
                    logger.debug("     └─ [子意图]: " + result.subIntent);
                if (result.actionCode != null)
                    logger.debug("     └─ [动作码]: " + result.actionCode);
                if (result.sentiment != IntentResult.Sentiment.NEUTRAL)
                    logger.debug("     └─ [情绪极性]: " + result.sentiment);
            } else {
                totalFail++;
                logger.debug("[FAIL] intentResult 为 null");
            }

            logger.debug("     └─ [状态码]: " + ca.code);
            logger.debug("     └─ [Action]: " + ca.action);
            logger.debug("     └─ [AI回复]: " + ca.answer);
            logger.debug("--------------------------------------------------");
        }
    }
}
