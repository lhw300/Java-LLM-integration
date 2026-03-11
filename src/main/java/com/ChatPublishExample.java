
	package com;

	
	public class ChatPublishExample {
	    public static void main(String[] args) throws Exception {
	        String clientId = "user_001";

            /* RAG 测试用例开始*/
            /* *************************************************************************
             * RAG 多轮对话引擎 - 核心测试套件 (Scenario-Based Test Suite)
             * ************************************************************************* */
            /* 场景 1：实体纠错、定义理解与环境约束 (Entity & Definition)
             * ----------------------------------------------------------------------
             * 测试目的：验证系统对业务名词（云教案）的解释能力，以及口误纠错与硬性环境拦截。
             * 1. 业务定义提取 (Definition)：准确回答“云教案”是什么。
             * 2. 实体纠错 (Entity Correction)：自动将口误的“2.0”修正为“3.0”。
             * 3. 负向约束比对 (Constraint Comparison)：识别 iOS 12 不符合 iOS 13 的硬性要求。
             */
            String[] scenarioCorrect = {
                    "我是老师，请问那个云教案是什么东西？",              // 验证：业务定义，回答“以教案为单位...的备课功能”
                    "我想在苹果手机上装‘翔云 2.0’，iOS 12 够用吗？", // 验证：版本纠错为 3.0，并根据知识库拦截 iOS 12
                    "那在 Windows 10 电脑上能装吗？"                 // 验证：环境切换，匹配“Windows 7 或以上”要求
            };

            /* 场景 2：身份缺失反问与培训业务流程 (Clarification & Training)
             * ----------------------------------------------------------------------
             * 测试目的：验证系统在多角色共有业务（培训）中，对缺失实体的反问能力及其后续继承。
             * 1. 角色缺失反问 (Active Clarification)：【核心点】识别“查密码/参加培训”的歧义，触发主动询问。
             * 2. 业务流程引导 (Process Guidance)：根据角色引导至“教研天地”入口。
             * 3. 身份记忆 (Identity Persistence)：用户回答身份后，后续问题均需基于该角色上下文。
             */
            String[] scenarioInherit = {
                    "我想要参加那个省市级的培训，具体怎么操作？",        // 验证：【反问触发点】AI 询问身份（老师还是管理员）
                    "我是管理员。",                                  // 预期：锁定身份为 [学校管理员]
                    "那是在哪点击参加？",                            // 验证：继承身份，回答“登录3.0客户端，点击教研天地”
                    "顺便查下我的初始密码是多少？",                   // 验证：继承身份，回答“十位数学校标识码+教育局下发密码”
                    "如果这个密码忘了该找谁找回？"                    // 验证：继承身份，回答“通过手机验证码找回”
            };

            /* 场景 3：指代消解与学生找回逻辑 (Anaphora & Student Logic)
             * ----------------------------------------------------------------------
             * 测试目的：验证多轮对话中代词的精准还原，以及在“学生”角色下的特定异常处理。
             * 1. 指代还原 (Anaphora Resolution)：将“他”还原为前文明确提到的“学生”。
             * 2. 状态机跳转 (Logic Jump)：基于 [学生] + [没绑手机] 组合条件，跳转到 [联系老师/管理员] 路径。
             */
            String[] scenarioRelation = {
                    "我小孩想登录平台，但不知道账号。",
                    "我是家长",
                    "他的初始密码是什么？",                          // 验证：“他”还原为学生，回答“A202101b”
                    "他如果没绑手机号，密码忘了能自助找回吗？",         // 验证：逻辑比对，识别学生没绑手机不能自助
                    "这种情况要找谁处理？"                            // 验证：命中知识库“联系本班教师或学校管理员”
            };

            /* 场景 4：负向约束与权限边界测试 (Negative Boundary & Permission)
             * ----------------------------------------------------------------------
             * 测试目的：验证系统对“权限外”功能及“知识外”请求的严谨拒止。
             * 1. 权限拦截 (Permission Shield)：明确学生身份后，拦截“云教案”或“培训参与”等权限。
             * 2. 知识边界拦截 (Explicit Rejection)：对 Mac 系统、实时气温等空白区触发标准拦截。
             * 3. 语义恢复测试：在拦截无关话题后，测试能否通过业务词（如“安卓版本”）拉回正轨。
             */
            String[] scenarioBoundary = {
                    "我是学生，我也想参加那个市级培训。",               // 验证：权限拦截（知识库中培训仅限教师/管理员）
                    "苹果手机系统版本低了会报错吗？",                   // 验证：语义恢复，匹配 iOS 13 约束说明
                    "平台支持在 Mac 电脑上用吗？",                    // 验证：边界拦截，知识库未提及 Mac
                    "安卓手机版本太低会有影响吗？"                     // 验证：语义恢复，匹配“安卓 6.0+”说明
            };
            /* *************************************************************************
             * RAG 测试用例结束
             * ************************************************************************* */
	        
	        

	        

	       SessionManager.init("qwen-online");
	    // 这一步会同时打通对话接口和向量接口的网络链路
	       SessionManager.warmUp();

            // 汇总所有场景进行回归测试
            String[][] allScenarios = {scenarioCorrect, scenarioInherit, scenarioRelation, scenarioBoundary};
             //allScenarios = new String[][] { scenarioBoundary };


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
                    ChatAnswer answer = session.ask3(userQuery);
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