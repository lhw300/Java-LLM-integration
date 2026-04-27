package com.lcallai;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * RAG 端到端 CI 测试入口
 *
 * 对应 ChatPublishExample.java，场景和问题顺序完全一致。
 *
 * 验证逻辑（双重校验）：
 *   1. rewriteKeywords：重写后的 query 必须包含所有关键词（验证指代消解/实体纠错）
 *   2. answerKeywords ：最终答案必须包含所有关键词（验证 RAG 检索+回答正确性）
 *   两项都通过 → PASS，任意一项失败 → FAIL
 *
 * 填写说明：
 *   - rewriteKeywords 填 null 或 new String[]{} → 跳过 rewrite 校验
 *   - answerKeywords  填 null 或 new String[]{} → 跳过 answer 校验
 *   - 关键词大小写不敏感
 *
 * 本地运行：
 *   java -cp lcallai-1.0-shaded.jar com.lcallai.RagTestRunner [configPath]
 */
public class RagTestRunner {

    private static final Logger logger = LogManager.getLogger(RagTestRunner.class);

    private static int totalPass = 0;
    private static int totalFail = 0;

    // =========================================================================
    // 测试用例数据结构
    // =========================================================================
    static class RagCase {
        String question;
        String[] rewriteKeywords; // 重写后 query 应包含的关键词，填 null 则跳过
        String[] answerKeywords;  // 最终答案应包含的关键词，填 null 则跳过

        RagCase(String question, String[] rewriteKeywords, String[] answerKeywords) {
            this.question = question;
            this.rewriteKeywords = rewriteKeywords;
            this.answerKeywords = answerKeywords;
        }
    }

    // =========================================================================
    // 场景定义（问题顺序与 ChatPublishExample 完全一致，填入你的预期关键词）
    // =========================================================================

    /** 场景 1：实体纠错、定义理解与环境约束 */
    static RagCase[] scenarioCorrect = {

            new RagCase(
                    "我是老师，请问那个教师云盘是什么东西？",
                    new String[]{  },
                    new String[]{ "教材","课件","小测" }
            ),
            new RagCase(
                    "那个备课云盘里面都能放些什么资源？",
                    new String[]{ "云教案","资源" },
                    new String[]{ "课件","数字教材" }
            ),
            new RagCase(
                    "那在 Windows 10 电脑上能装吗？",
                    new String[]{ "Windows" },
                    new String[]{ "可以|支持|能装|是的"}
            ),
    };

    /** 场景 2：身份缺失反问与培训业务流程 */
    static RagCase[] scenarioInherit = {
            new RagCase(
                    "我想要参加那个省市级的培训，具体怎么操作？",
                    new String[]{ /* TODO */ },
                    new String[]{ "学生","老师" }
            ),

            new RagCase(
                    "我是管理员。",
                    new String[]{   },
                    new String[]{ "登录粤教翔云数字教材","教研天地","教育部门" }
            ),
            new RagCase(
                    "那是在哪点击参加？",
                    new String[]{   },
                    new String[]{ "客户端","教研天地" }
            ),
            new RagCase(
                    "顺便查下我的初始密码是多少？",
                    new String[]{  "管理员","初始密码" },
                    new String[]{ "下发"}
            ),
            new RagCase(
                    "如果这个密码忘了该找谁找回？",
                    new String[]{ "管理员","忘记密码" },
                    new String[]{ "手机验证码" }
            ),


    };

    /** 场景 3：指代消解与学生找回逻辑 */
    static RagCase[] scenarioRelation = {
            new RagCase(
                    "我小孩想登录平台，但不知道账号。",
                    new String[]{ /* TODO */ },
                    new String[]{ "身份证号"}
            ),
            new RagCase(
                    "我是家长",
                    null,
                            new String[]{"" }
            ),
            new RagCase(
                    "他的初始密码是什么？",
                    new String[]{ /* TODO: "他" 应被还原为学生，如 "学生", "初始密码" */ },
                    new String[]{ "A202101" }
            ),
            new RagCase(
                    "他如果没绑手机号，密码忘了能自助找回吗？",
                    new String[]{ /* TODO */ },
                    new String[]{ "班主任|老师|管理员"}
            ),
            new RagCase(
                    "这种情况要找谁处理？",
                    new String[]{ /* TODO */ },
                    new String[]{ "班主任|老师|管理员"}
            ),
    };

    /** 场景 4：负向约束与权限边界测试 */
    static RagCase[] scenarioBoundary = {
            new RagCase(
                    "我是学生，我也想参加那个市级培训。",
                    new String[]{ /* TODO */ },
                    new String[]{"没有|抱歉" }
            ),
            new RagCase(
                    "苹果手机系统版本低了会报错吗？",
                    new String[]{ /* TODO */ },
                    new String[]{ "13" }
            ),
            new RagCase(
                    "平台支持在 Mac 电脑上用吗？",
                    new String[]{ /* TODO */ },
                    new String[]{ /* TODO: 预期拦截，如 "不支持" 或 "未提及" */ }
            ),
            new RagCase(
                    "安卓手机版本太低会有影响吗？",
                    new String[]{ /* TODO */ },
                    new String[]{ "6" }
            ),
            new RagCase(
                    "老师参加培训呢?",
                    new String[]{ /* TODO */ },
                    new String[]{ /* TODO */ }
            ),

    };

    // =========================================================================
    // main
    // =========================================================================
    public static void main(String[] args) throws Exception {

        String clientId = "user_001";

        // ── 配置路径解析 ──────────────────────────────────────────────────────
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

        logger.info("Rerank 模块加载成功！");
        logger.debug("🔥 进程内验证 QWEN_API_KEY: [" + System.getenv("QWEN_API_KEY") + "]");

        SessionManager.init(configPath);
        SessionManager.warmUp();

        // ── 汇总所有场景（顺序与 ChatPublishExample 一致）────────────────────
        RagCase[][] allScenarios = {
                scenarioCorrect,
                scenarioInherit,
                scenarioRelation,
                scenarioBoundary
        };
        String[] scenarioNames = {
                "Scenario 1: Entity Correction",
                "Scenario 2: Implicit Inheritance",
                "Scenario 3: Anaphora Resolution",
                "Scenario 4: Negative Boundary"
        };
        RagCase[][] allScenarios2 = {
                scenarioCorrect,

        };
        // ── 执行 ──────────────────────────────────────────────────────────────
        for (int s = 0; s < allScenarios2.length; s++) {
            runScenario(clientId + "_scenario_" + (s + 1), scenarioNames[s], allScenarios[s]);
        }

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
    // 场景执行器
    // =========================================================================
    private static void runScenario(String sessionId, String scenarioName, RagCase[] cases) {
        logger.debug("##################################################");
        logger.debug("🚩 正在执行：" + scenarioName);
        logger.debug("##################################################");

        ChatSession session = SessionManager.getSession(sessionId);

        for (int i = 0; i < cases.length; i++) {
            RagCase rc = cases[i];
            logger.debug("==================================================");
            logger.debug("👤 轮次 [" + (i + 1) + "] 提问: " + rc.question);
            logger.debug("⏳ 正在请求 AI 及其重写/检索链路...");

            long start = System.currentTimeMillis();
            ChatAnswer answer = session.ask(rc.question);
            long elapsed = System.currentTimeMillis() - start;

            // ── 取 rewrite 后的 query ─────────────────────────────────────────
            String rewrittenQuery = "";
            if (answer != null && answer.intentResult != null
                    && answer.intentResult.refinedQuery != null) {
                rewrittenQuery = answer.intentResult.refinedQuery;
            }
            String finalAnswer = (answer != null && answer.answer != null) ? answer.answer : "";

            // ── 双重校验 ─────────────────────────────────────────────────────
            boolean rewritePass = checkKeywords("rewrite", rc.question, rewrittenQuery, rc.rewriteKeywords);
            boolean answerPass  = checkKeywords("answer",  rc.question, finalAnswer,    rc.answerKeywords);
            boolean pass = rewritePass && answerPass;

            if (pass) totalPass++; else totalFail++;

            logger.debug(String.format("[%s] 轮次 %d | 耗时: %d ms",
                    pass ? "PASS" : "FAIL", i + 1, elapsed));
            logger.debug("     └─ [重写query]: " + rewrittenQuery);
            logger.debug("     └─ [AI回答]:   " + finalAnswer);
            logger.debug("==================================================\n");

            // 防限流（与 ChatPublishExample 一致）
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        }

        logger.debug("✅ 场景执行完毕：" + scenarioName);
    }

    // =========================================================================
    // 关键词校验（大小写不敏感，null/空数组直接跳过）
    // =========================================================================
    private static boolean checkKeywords(String label, String question,
                                         String target, String[] keywords) {
        if (keywords == null || keywords.length == 0) return true;

        String lowerTarget = target.toLowerCase();
        boolean allMatch = true;

        for (String kw : keywords) {
            if (kw == null || kw.isEmpty()) continue;
            // 支持 | 分隔的 OR 逻辑，如 "可以|支持|能"
            String[] orOptions = kw.split("\\|");
            boolean anyHit = false;
            for (String option : orOptions) {
                if (lowerTarget.contains(option.trim().toLowerCase())) {
                    anyHit = true;
                    break;
                }
            }
            if (!anyHit) {
                logger.debug(String.format("     ❌ [%s校验失败] 问题: \"%s\" | 缺失关键词: \"%s\"",
                        label, question, kw));
                allMatch = false;
            }
        }
        return allMatch;
    }
}
