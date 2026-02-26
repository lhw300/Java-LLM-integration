
	package com;

	
	public class ChatExample {
	    public static void main(String[] args) throws Exception {
	        String clientId = "user_001";
            /* RAG 测试用例开始*/
            //场景 1：实体纠错与约束锁定 (Entity Correction)
            //测试目的：验证重写逻辑能否在用户中途修正信息时，准确剔除“脏数据”，锁定最新属性。
            String[] scenarioCorrect = {
                    "我想去西安办签证。",
                    "刚才说错了，我是想去北京。不过顺便问下，西安的办事处能办签证吗？", // 验证：能否区分“想去”和“办事处地点”
                   // "如果我是德国人，去中国免签吗？",    // 验证：是否命中“15天中国免签政策”
                  //  "那我要是待20天呢？"                 // 验证：是否能继承“德国人”+“中国”并对比“15天限制”
            };
            //场景 2：隐式实体继承 (Implicit Inheritance)
            //测试目的：验证当用户在后续对话中完全省略主语或国籍时，重写结果是否依然包含核心搜索约束。
            String[] scenarioInherit = {
                    "我是马来西亚的。",
                    "去中国旅游需要签证吗？", // 预期重写：马来西亚人去中国旅游需要签证吗
                    "如果是商务考察呢？",    // 预期重写：马来西亚人去中国商务考察免签政策
                    "那如果我从大连入境呢？" // 验证：在身份（马来西亚）基础上叠加地理口岸约束
            };
            //场景 3：指代消解与关系推导 (Anaphora Resolution)
            //测试目的：验证“他”、“这种”等代词的还原能力，以及对年龄等关键属性的提取。
            String[] scenarioRelation = {
                    "我是美国护照。",
                    "有这种144小时的政策吗？", // 验证：“这种”是否指向“144小时过境免签”
                    "我带个14岁的孩子一起。",
                    "他也适用吗？",           // 验证：“他”还原为“美国护照的孩子”
                    "从北京口岸走可以吗？"     // 验证：最终搜索词是否包含：美国、144小时、北京口岸
            };


            //场景 4：负向约束与边界测试 (Negative Constraints)
            //测试目的：验证系统在面对不在免签名单内的实体或超出政策范围的情况时，是否能正确触发 SIMILARITY_THRESHOLD = 0.5。
            String[] scenarioBoundary = {
                    "我是英国人。",
                    "我想在中国待一个月。",
                    "我免签吗？",      // 验证：是否会误命中“15天免签”（该政策不含英国）
                    "今天哈尔滨多少度？" // 验证：语义突变，是否能准确命中“紧急电话与哈尔滨天气”
            };
	        
            /* RAG 测试用例结束 */
	        
	        

	        
	        String[] userSteps=scenarioCorrect;
	        // 获取该用户的专属会话
	        //SessionManager.init("ollama");
	      // SessionManager.init("openai");
	       SessionManager.init("qwen-online");
	    // 这一步会同时打通对话接口和向量接口的网络链路
	       SessionManager.warmUp();
	        ChatSession session = SessionManager.getSession(clientId);
	        
	        System.out.println("🚀 开始 RAG 多轮对话引擎测试...\n");

	        // 使用 for 循环遍历每一个问题
	        for (int i = 0; i < userSteps.length; i++) {
	            String userQuery = userSteps[i];
	            
	            System.out.println("==================================================");
	            System.out.println("👤 轮次 [" + (i + 1) + "] 提问: " + userQuery);
	            System.out.println("⏳ 正在请求 AI 及其重写/检索链路...");
	            
	            // 核心调用：内部会经历 Rewrite -> Retrieve -> Generation
	            ChatAnswer answer = session.ask3(userQuery);
	            
	            System.out.println("💬 AI 回答 (状态码: " + answer.code + "): \n" + answer.answer);
	            System.out.println("==================================================\n");
	            
	            // 可选：为了防止连续调用过快触发 OpenAI 的并发限流 (Rate Limit)，可以稍微停顿一下
	            try {
	                Thread.sleep(1500); 
	            } catch (InterruptedException e) {
	                e.printStackTrace();
	            }
	        }
	        
	        System.out.println("✅ 测试执行完毕。");
	        
	        // 如果你需要在这个 main 方法跑完后立刻退出 Java 进程，
	        // 记得在这里调用之前我们在 SearchService 里加的关掉 HikariCP 连接池的方法。
	        SearchService.shutdown(); 
	    }
	}