package com.lcallai;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import com.lcallai.intent.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.lcallai.handler.*;
/**
 * AI Agent 意图识别全场景装配测试
 import java.util.UUID;



 * 精简版测试用例：直接利用 SessionManager 内部已注册的逻辑
 */
public class ChatIntentExample {
      static final Logger logger = LogManager.getLogger(ChatIntentExample.class);

    public static void main(String[] args) {
        // 1. 初始化 SessionManager
        // 此时内部 init() 已经自动 registerHandler 了所有业务逻辑 (Chitchat, Command, Ack 等)
        SessionManager sessionManager = new SessionManager();

        // 2. 准备全场景意图协议测试数组
        // 涵盖了：GREETING, INFORM, COMMAND, QUERY, ACK, FEEDBACK, CHITCHAT

        String[][] greetingTests = {
                // 1. 标准招呼 (最简单情况)
                {"你好", "GREETING"},
                {"Hello", "GREETING"},
                {"嗨，有人在吗？", "GREETING"},

                // 2. 时间敏感型 (测试模型对时间词的敏感度)
                {"早上好，今天心情不错。", "GREETING"},
                {"晚安，辛苦了。", "GREETING"},

                // 3. 带有强烈情感/口语化 (测试抗干扰能力)
                {"哈喽哇！小助手，我想死你啦！", "GREETING"},
                {"喂？能听到我说话吗？", "GREETING"},


                // 4. 边界测试 (重点：打招呼+业务诉求 -> 预期不应是 GREETING)
                {"你好，帮我查下流量。", "QUERY"},   // 应该是 COMMAND
                {"早安，Win11怎么激活？", "QUERY"}     // 应该是 QUERY
        };
        String[][] ackTests = {
                // 场景 A：肯定回复 (affirm)
                {"是的", "ACK"},
                {"对，就是这个", "ACK"},
                {"没错，请执行", "ACK"},
                {"确认安装", "ACK"},

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
                {"调低音量。", "COMMAND"},           // 预期: ACTION_VOL_DOWN
                {"刚才那段再放一遍。", "COMMAND"},    // 预期: ACTION_REPLAY

                // 场景 B：语义泛化 (同义词挑战)
                {"找个真人来跟我说话。", "COMMAND"},   // 预期: ACTION_TRANSFER
                {"吵死了，小声些。", "COMMAND"},      // 预期: ACTION_VOL_DOWN
                {"重新播放。", "COMMAND"},           // 预期: ACTION_REPLAY

                // 场景 C：超范围指令 (触发降级为 QUERY)
                {"帮我查一下话费流量。", "QUERY"},     // 预期: QUERY (不在枚举内)
                {"帮我重启一下宽带猫。", "QUERY"},     // 预期: QUERY (不在枚举内)
                {"打开电视机。", "QUERY"},            // 预期: QUERY (不支持的动作)

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
                {"宽带安装地址是锦绣路100弄3号楼。", "INFORM"},

                // 场景 C：描述具体问题/故障 (作为业务输入的补充)
                {"我的光猫红灯一直在闪。", "INFORM"},
                {"家里断网快半小时了。", "INFORM"},
                {"报错代码是 691。", "INFORM"},

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
                {"你觉得 AI 会取代人类吗？", "CHITCHAT"},

                // 场景 B：无意义的语气词/测试输入
                {"哈哈哈哈哈哈。", "CHITCHAT"},
                {"呃，让我想想。", "CHITCHAT"},
                {"哦吼。", "CHITCHAT"},

                // 场景 C：幽默/玩笑
                {"讲个笑话听听。", "CHITCHAT"},
                {"你吃饭了吗？", "CHITCHAT"},

                // 场景 D：边界挑战 (CHITCHAT vs QUERY)
                {"北京的首都是哪里？", "CHITCHAT"},   // 预期：非业务常识，应归为闲聊
                {"Win10 是哪年发布的？", "QUERY"},    // 预期：涉及系统知识，应归为查询
                {"你好帅啊。", "CHITCHAT"}            // 预期：社交赞赏
        };
        // 建议增加一个混合测试方法

            String[][] stressData = {
                    {"你好", "GREETING"},                     // 1. 打招呼
                    {"我是李老师", "INFORM"},                  // 2. 报身份
                    {"怎么重置密码？", "QUERY"},               // 3. 查业务
                    {"太感谢了，帮了大忙！", "FEEDBACK"},       // 4. 给好评
                    {"讲个笑话吧", "CHITCHAT"},                // 5. 歪楼闲聊
                    {"再见", "ACK"}                           // 6. 结束
            };
        String[][] stressData2 = {
                // 1. 正常业务开场
                {"你好", "GREETING"},
                {"我是李老师", "INFORM"},

                // 2. 核心 RAG 逻辑 (触发: 重写 -> 768维检索 -> 10个样本重排)
                {"怎么重置密码？", "QUERY"},

                // 3. 业务引流与边界 (触发: executeChitchat 纯净模式)
                {"讲个笑话吧", "CHITCHAT"},
                {"你会写 Java 吗？", "CHITCHAT"},

                // 4. 指令与实时交互 (触发: CommandHandler)
                {"声音太小了，大声一点", "COMMAND"},
                {"帮我转接人工客服", "COMMAND"},

                // 5. 负面反馈与情绪识别 (触发: FeedbackHandler)
                {"刚才那个密码不对，你们这系统真行", "FEEDBACK"},

                // 6. 确认与收尾 (触发: 修正后的结束语拦截)
                {"好的，我知道了", "ACK"},
                {"再见", "ACK"}
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
                {"什么", "COMMAND"},              // 极简口语，最容易误判 CHITCHAT
                {"啊？", "COMMAND"},              // 语气词型重播请求
                {"能再说一遍吗", "COMMAND"},       // 礼貌型
                {"刚才你说的是什么", "COMMAND"},   // 带"刚才"的指代型

                // ── REPLAY vs CHITCHAT 边界（首句 vs 非首句由上下文决定）────────
                // 注意：以下两条需放在同一个 session 里跑才有意义
                {"你好", "GREETING"},             // 首句，此时 AI 没说过话
                {"你说什么", "COMMAND"},          // 非首句，AI 已有回复，应判 REPLAY
        };

        //场景一：极限“歪楼”与强行拉回（测试 CHITCHAT 的控制力）
        //设计目的：测试用户在问业务的中途，突然开始疯狂闲聊，测试系统能否在不遗忘上下文（李老师）的前提下，既幽默应对，又把话题强行拽回粤教翔云的业务上
        String[][] stressData5 = {
                // 1. 开场与身份
                {"你好", "GREETING"},
                {"我是李老师", "INFORM"},

                // 2. 突然开始闲聊 (测试 ChitchatHandler 的边界)
                {"你觉得今天天气怎么样？适合备课吗？", "CHITCHAT"},

                // 3. 极度歪楼
                {"你平时都吃什么牌子的电量？", "CHITCHAT"},

                // 4. 强行拉回业务
                {"算了不扯了，老师的初始密码是多少来着？", "QUERY"}
                // 预期：QUERY。系统应重写为“教师初始密码是多少”，并结合上下文“李老师”给出 A202101b 的答案。
        };
        //场景二：情绪爆发与抚慰后转人工（测试反馈与指令优先级）
//设计目的：测试用户在使用过程中遇到困难，产生极大的负面情绪（Negative Sentiment），并在同一句话中夹杂了投诉和转人工。测试系统是优先安抚，还是优先执行动作。

        String[][] stressData6 = {
                // 1. 正常业务开场
                {"你好，我是李老师", "GREETING"}, // 这里 GREETING 或 INFORM 均可
                {"我想查一下怎么重置密码", "QUERY"},

                // 2. 用户操作失败，情绪爆发 (复合意图：负面反馈 + 挂断/转人工指令)
                {"你们这系统太垃圾了，密码根本不对，快给我找个活人！", "COMMAND"}
                // 预期：COMMAND。虽然有强烈的 FEEDBACK (Negative)，但“找个活人”触发了 ACTION_TRANSFER，指令动作的优先级最高！
        };
        //场景三：指代消解与口语重播（测试 REPLAY 边界）
       // 设计目的：在电话或者语音交互中，用户经常会因为环境嘈杂听不清。本例测试极简口语（如 "啊？"、"什么"）在有上下文和没有上下文时的不同判定。
        String[][] stressData7 = {
                // 1. 正常开场
                {"你好，我是李老师，请问怎么重置密码？", "QUERY"},
                // 此时 AI 会回复一大堆关于密码的 TTS 语音

                // 2. 极简口语触发重播
                {"什么？", "COMMAND"},
                // 预期：COMMAND (ACTION_REPLAY)。因为 AI 刚说完话，用户说“什么？”代表没听清，应触发重新播放。

                // 3. 再次询问
                {"没听清，你再说一遍", "COMMAND"}
                // 预期：COMMAND (ACTION_REPLAY)。语义泛化的重播请求。
        };
       // 场景四：多身份混淆与防呆设计（测试身份隔离）
       // 设计目的：根据你代码里的系统提示词（System Prompt），系统严禁跨角色取值。如果用户一会儿说是老师，一会儿问学生的事，测试系统能不能做到“防呆”和“身份锁定”。
        String[][] stressData8 = {
                // 1. 锁定身份为老师
                {"你好，我是老师", "INFORM"},

                // 2. 询问本属于老师的业务
                {"怎么重置密码？", "QUERY"}, // 预期：给出教师的 A202101b

                // 3. 突然跨角色询问学生业务
                {"那我们班学生的初始密码是多少？", "QUERY"}
                // 预期：QUERY。系统应重写为“学生初始密码是多少”。
                // 根据你的背景知识隔离原则，系统应该能查到并给出学生初始密码（大写A202101小写b），或者提示李老师这是学生业务。
        };

        logger.debug("=== 自动化意图分发测试 (基于 SessionManager 内置注册) ===\n");
        String configPath = "e:\\ai";


        SessionManager.init(  configPath);

       // SessionManager.warmUp();
        // 3. 执行集成测试
       // runIntegratedTest(stressData4, sessionManager);
        runIntegratedTest(stressData8);
        logger.debug("所有自动化链路测试完毕。");
    }

    private static void runIntegratedTest(String[][] testData) {
        String sessionId = "SESSION_" + UUID.randomUUID().toString().substring(0, 8);
        ChatSession session = SessionManager.getSession(sessionId);

        for (String[] test : testData) {
            String userInput = test[0];
            String expectedIntent = test[1];

            logger.debug("==================================================");
            logger.debug("👤 用户输入: " + userInput + " | 预期意图: " + expectedIntent);

            // 直接调用 ask()，分类+派发全部内聚在里面
            ChatAnswer ca = session.ask(userInput);

            // 从返回结果里取 intentResult 做校验，不再自己调分类器
            IntentResult result = ca.intentResult;
            if (result != null) {
                String actual = result.intent.name();
                boolean pass = actual.equalsIgnoreCase(expectedIntent);

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
            }

            logger.debug("     └─ [状态码]: " + ca.code);
            logger.debug("     └─ [Action]: " + ca.action);
            logger.debug("     └─ [AI回复]: " + ca.answer);
            logger.debug("--------------------------------------------------");
        }
    }
    private static void displayTestLog(String input, IntentResult intentRes, ChatAnswer answer, String expected) {
        // 1. 获取意图名称 (注意：你代码里 intent 是枚举)
        String actual = intentRes.intent.name();
        boolean success = actual.equalsIgnoreCase(expected);

       // logger.debug("--------------------------------------------------");
        logger.debug(String.format("[%s] 用户输入: %s", success ? "PASS" : "FAIL", input));
        logger.debug(String.format("     识别意图: %-10s | 预期意图: %s", actual, expected));

        // 2. 打印协议细节 (直接访问你 IntentResult 里的 public final 字段)
        if (intentRes.refinedQuery != null && !intentRes.refinedQuery.isEmpty()) {
            logger.debug("     └─ [优化查询]: " + intentRes.refinedQuery);
        }

        if (intentRes.subIntent != null) {
            logger.debug("     └─ [子意图]: " + intentRes.subIntent);
        }

        if (intentRes.actionCode != null) {
            logger.debug("     └─ [动作码]: " + intentRes.actionCode);
        }

        // 情绪是枚举，直接打印或转换
        if (intentRes.sentiment != IntentResult.Sentiment.NEUTRAL) {
            logger.debug("     └─ [情绪极性]: " + intentRes.sentiment);
        }

        // 3. 处理结果 (适配你的 ChatAnswer 类)
        if (answer != null) {
            logger.debug("     └─ 状态码: " + answer.code);
            // 注意：你代码里字段叫 answer，不是 result 或 reply
            logger.debug("     └─ AI回复: " + answer.answer);
        }
        logger.debug("--------------------------------------------------");
    }
}