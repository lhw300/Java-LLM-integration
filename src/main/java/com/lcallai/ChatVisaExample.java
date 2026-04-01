
	package com.lcallai;


    import com.lcallai.ChatAnswer;
    import com.lcallai.ChatSession;
    import com.lcallai.SearchService;
    import com.lcallai.SessionManager;

    public class ChatVisaExample {
	    public static void main(String[] args) throws Exception {
	        String clientId = "user_001";

            /* RAG 测试用例开始*/
            /* *************************************************************************
             * RAG 多轮对话引擎 - 核心测试套件 (Scenario-Based Test Suite)
             * ************************************************************************* */

            /* 场景 1：实体纠错与约束锁定 (Entity Correction)
             * ----------------------------------------------------------------------
             * 测试目的：验证 Rewrite 逻辑在用户中途修正信息时，能否准确剔除“脏数据”，锁定最新属性。
             * 1. 负向约束 (Negative Constraints)：拦截外部常识，未对“西安”做盲目假设或补全。
             * 2. 动态知识补全 (Dynamic Knowledge Grounding)：0.6 阈值确保了对地点属性的精准解释。
             * 3. 实体纠错 (Entity Correction)：成功滤掉被用户否定的“北京”，锁定“西安办事处”。
             * 4. 数值推理 (Logical Reasoning)：完成“德国人”+“20天”与“15天限制”的逻辑比对。
             */
            String[] scenarioCorrect = {
                    "我想去西安办签证。", // 预期：背景知识未提及，触发严谨性拦截
                    "刚才说错了，我是想去北京。不过顺便问下，西安的办事处能办签证吗？", // 验证：锁定最新核心目标
                    "如果我是德国人，去中国免签吗？",    // 验证：匹配“15天单方面免签”
                    "那我要是待20天呢？"                 // 验证：逻辑推理，识别 20 > 15 需办签证
            };

            /* 场景 2：隐式实体继承 (Implicit Inheritance)
             * ----------------------------------------------------------------------
             * 测试目的：验证后续对话完全省略主语/国籍时，重写结果是否依然包含核心搜索约束。
             * 1. 身份存档 (Identity Archiving)：准确提取并存储“马来西亚”国籍信息。
             * 2. 意图重构 (Intent Rewriting)：将“去中国旅游”重写为带身份约束的完整搜索词。
             * 3. 跨轮次约束继承 (Cross-turn Inheritance)：商务考察场景依然保持“马来西亚”身份锁定。
             * 4. 常识拒止 (Rejection of Common Sense)：严禁 AI 根据常识推断“大连”是口岸。
             */
            String[] scenarioInherit = {
                    "我是马来西亚的。",
                    "去中国旅游需要签证吗？",
                    "如果是商务考察呢？",
                    "那如果我从大连入境呢？" // 验证：在身份基础上叠加地理约束，无原文支持则回答“无法确认”
            };

            /* 场景 3：指代消解与关系推导 (Anaphora Resolution)
             * ----------------------------------------------------------------------
             * 测试目的：验证“他”、“这种”等代词的还原能力，以及对年龄等关键属性的提取。
             * 1. 显式实体链接 (Explicit Entity Linking)：识别“14岁”年龄约束，基于国籍一致性推断适用性。
             * 2. 隐式指代还原 (Anaphora Resolution)：通用重写指令将“他”还原为“持美国护照的14岁儿童”。
             * 3. 复杂约束叠加 (Constraint Layering)：搜索词完美整合 [美国护照] + [144小时] + [北京口岸]。
             */
            String[] scenarioRelation = {
                    "我是美国护照。",
                    "有这种144小时的政策吗？", // 验证：“这种”指向前序政策
                    "我带个14岁的孩子一起。",
                    "他也适用吗？",           // 验证：“他”还原为“美国护照的孩子”
                    "从北京口岸走可以吗？"
            };

            /* 场景 4：负向约束与边界测试 (Negative Constraints & Boundary Testing)
             * ----------------------------------------------------------------------
             * 测试目的：验证面对非名单内实体或超出政策范围时，能否正确触发阈值拦截 (0.6)。
             * 1. 政策边界测试 (Policy Boundary)：准确识别英国人的双重限制（时长限制 + 名单排除）。
             * 2. 时效性/精确性拦截 (Temporal Shield)：识别出知识库无法支撑“今日实时气温”而果断拦截。
             * 3. 语义匹配恢复 (Semantic Recovery)：当意图回归“冬季极寒”等通识文档时，分值回升，输出准确。
             */
            String[] scenarioBoundary = {
                    "我是英国人。",
                    "我想在中国待一个月。",
                    "我免签吗？",      // 验证：不因过境免签而混淆单方面免签（名单不含英国）
                    "今天哈尔滨多少度？", // 验证：语义突变，拦截缺乏数值支撑的查询
                    "哈尔滨冬天冷吗"    // 验证：命中“冬季极寒”知识点
            };

            /* *************************************************************************
             * RAG 测试用例结束
             * ************************************************************************* */
	        
	        

	        

	       SessionManager.init("qwen-online","e:\\ai");
	    // 这一步会同时打通对话接口和向量接口的网络链路
	       SessionManager.warmUp();

            // 汇总所有场景进行回归测试
            String[][] allScenarios = {scenarioCorrect, scenarioInherit, scenarioRelation, scenarioBoundary};
            String[] scenarioNamescn = {"场景 1：实体纠错", "场景 2：隐式继承", "场景 3：指代消解", "场景 4：负项边界"};
// 使用英文定义的场景名称
            String[] scenarioNames = {
                    "Scenario 1: Entity Correction",
                    "Scenario 2: Implicit Inheritance",
                    "Scenario 3: Anaphora Resolution",
                    "Scenario 4: Negative Boundary"
            };



            for (int s = 0; s < allScenarios.length; s++) {
                String[] userSteps = allScenarios[s];
                String currentScenarioName = scenarioNames[s];

                // 【核心改动】为每个场景生成独立的 ID，确保 Session 隔离
                String independentId = clientId + "_scenario_" + (s + 1);
                ChatSession session = SessionManager.getSession(independentId);

                System.out.println("##################################################");
                System.out.println("🚩 正在执行：" + scenarioNames[s]);
                System.out.println("##################################################                ");

                // 使用 for 循环遍历每一个问题
                for (int i = 0; i < userSteps.length; i++) {
                    String userQuery = userSteps[i];

                    System.out.println("==================================================");
                    System.out.println("👤 轮次 [" + (i + 1) + "] 提问: " + userQuery);
                    System.out.println("⏳ 正在请求 AI 及其重写/检索链路...");
                    // 1. 记录开始时间
                    long questionStart = System.currentTimeMillis();
                    // 核心调用：内部会经历 Rewrite -> Retrieve -> Generation
                    ChatAnswer answer = session.ask_old(userQuery);
                    long totalDuration = System.currentTimeMillis() - questionStart;
                    System.out.println("💬 AI 回答 (状态码: " + answer.code + "): \n" + answer.answer);
                    System.out.println("⏱️ 该轮次总响应耗时: " + totalDuration + " ms");
                    System.out.println("==================================================\n");

                    // 可选：为了防止连续调用过快触发 OpenAI 的并发限流 (Rate Limit)，可以稍微停顿一下
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                System.out.println("✅ 测试执行完毕。");
            }
	        // 如果你需要在这个 main 方法跑完后立刻退出 Java 进程，
	        // 记得在这里调用之前我们在 SearchService 里加的关掉 HikariCP 连接池的方法。
	        SearchService.shutdown();
	    }
	}