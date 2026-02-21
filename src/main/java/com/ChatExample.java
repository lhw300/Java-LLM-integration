
	package com;

	
	public class ChatExample {
	    public static void main(String[] args) throws Exception {
	        String clientId = "user_001";
	        
	        // 提前设置好的问题集合，模拟用户的真实多轮追问过程
	        String[] userSteps1 = {
	            "中国免签政策",
	            "我是法国人。", 
	            "我免签吗？", 
	            "那我的孩子呢？他14岁。" // 任务5 加上后距离 0.48 降低到0.42 
	            //属性继承与代词消解：将“他”、“这种”、“我”等代词还原为具体名词。同时，必须将历史对话中用户声明过的自身固有属性（如：国籍、职业、地理位置等，例如“法国人”）
	            //继承并拼接到当前重写的问题中，确保搜索词的约束条件绝对完整。
	        };
	        String[] scenario1 = {
	        	    "我想了解德国的入境政策。",
	        	    "哦不对，我记错了，我是法国人。", 
	        	    "那我免签中国吗？", // 验证：搜索词是否能剔除德国，锁定法国
	        	    "如果我还要带一只狗呢？" // 验证：在身份（法国）基础上叠加新实体（宠物）
	        	};
	        String[] scenario3 = {
	        	    "法国普通护照。", 
	        	    "我是这种。", 
	        	    "去中国免签吗？", 
	        	    "我孩子14岁。",
	        	    "他呢？", // 故意不说“孩子”，看 history 里的“14岁”或者之前的对话能否对齐
	        	     
	        	};
	        
	        //以下没做成功，护照映射到了第一句
	        /*请仔细看第 3 轮和第 4 轮发生的事情：

第 3 轮原话："那如果我是美国人呢？"
这里带有强烈的【第一人称代入感】。

存入历史的重写话："美国普通护照持有者是否可以免签进入中国？"
【致命丢失】：大模型把这句话重写成了一句客观的、第三人称的通用问句。用户“代入美国人”的这个动作被抹杀了！

第 4 轮原话："我有这种护照。"
大模型此时在历史里扫描：第一轮有一个明晃晃的声明 法国普通护照，第三轮只是随便问了一句关于 美国普通护照 的客观政策。既然你说“我有”这种护照，那你肯定是指第一轮那个真正属于你的法国护照！

大模型不是没听懂规则，而是它在逻辑上自洽了：你只是问了问美国，但你实际拥有的是法国
*/
	        String[] reversalScenario = {
	        	    "法国普通护照。",
	        	    "我免签中国吗？",
	        	    "那如果我是美国人呢？", // 重点：测试 AI 能否意识到“法国”已经失效，“美国”才是最新重要信息
	        	    "我有这种护照。"
	        	};
	        
	        String[] scenario2 = {
	        	    "我是法国人，准备去北京旅游。",
	        	    "北京现在的天气怎么样？", // 干扰项：非知识库业务
	        	    "我需要办签证吗？", // 验证：能否跳过天气，把“法国人”和“签证”重新关联
	        	    "15天够玩吗？" // 验证：对“15天”这个数字（来自之前AI给出的免签天数）的语境理解
	        	};
	        
	        
	        //这个测试失败，需要个性化修改prompt
	        String[] policyScenario = {
	        	    "我是美国人。",
	        	    "我打算去上海玩 3 天，然后飞日本。", // 关键点：3天 < 144小时
	        	    "我需要提前办签证吗？"              // 预期搜索词：美国护照 上海 144小时过境免签
	        	};
	        String[] policyScenario2 = {
	 
	        	    "法国人去中国需要提前办签证吗？" ,             // 预期搜索词：美国护照 上海 144小时过境免签
	        		 "我需要提前办签证吗？"   ,
	        		 "简要回答上个问题，给我答案，yes还是no就行" 
	        	};
	        
	        
	        String[] userSteps=policyScenario2;
	        // 获取该用户的专属会话
	        ChatSession session = SessionManager.getSession(clientId);
	        
	        System.out.println("🚀 开始 RAG 多轮对话引擎测试...\n");

	        // 使用 for 循环遍历每一个问题
	        for (int i = 0; i < userSteps.length; i++) {
	            String userQuery = userSteps[i];
	            
	            System.out.println("==================================================");
	            System.out.println("👤 轮次 [" + (i + 1) + "] 提问: " + userQuery);
	            System.out.println("⏳ 正在请求 AI 及其重写/检索链路...");
	            
	            // 核心调用：内部会经历 Rewrite -> Retrieve -> Generation
	            ChatAnswer answer = session.ask(userQuery);
	            
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