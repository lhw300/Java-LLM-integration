package com;



import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.Model;
import com.fasterxml.jackson.databind.node.ArrayNode;

public class DJLLocalClient implements EmbeddingClient,LlmClient {
    private static ZooModel<String, float[]> model;
    private ZooModel<String[], Float> rerankModel; // 专门用于 Cross-Encoder 的模型
    //private Predictor<String[], Float> rerankPredictor;
    // 统一配置路径
    static String modeName="bge-reranker-v2-m3";
   private static final String RERANK_MODEL_PATH = "E:/AI/"+modeName;
    private static final String EMBED_MODEL_PATH = "E:/AI/text2vec-base-chinese-paraphrase-pt";
    String localModelPath = "file:///E:/EIT/openai/text2vec-base-chinese-paraphrase";
// 1. 指定原生 PyTorch 核心库路径
// 🌟 必须包裹在 static 块中，否则报 <identifier> expected 错误
static {
    System.setProperty("PYTORCH_LIBRARY_PATH", "E:/AI/libtorch_win");
    System.setProperty("java.library.path", "E:/AI/libtorch_win");
    System.setProperty("offline", "true");
    System.out.println("✅ 已锁定本地路径并开启离线模式");
}
    public DJLLocalClient() throws Exception {
        if (model == null) {
            /*
            检测 (Detection)：它发现你需要 PyTorch 引擎，但本地或远程只有 .bin。

自动转换 (Auto-Conversion)：DJL 会利用它内置的工具（或者调用你系统里的 Python/PyTorch 环境），在后台默默地将 pytorch_model.bin 转换为适合 Java/LibTorch 使用的 TorchScript (.pt) 格式。

持久化 (Caching)：转换完成后，它会将这个生成的 .pt 文件存在你的 .djl.ai\cache 目录下，以便下次秒开。
String path = "C:/Users/Administrator/.djl.ai/cache/repo/model/nlp/text_embedding/ai/djl/huggingface/pytorch/shibing624/text2vec-base-chinese/0.0.1/text2vec-base-chinese";
             */
           /* Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelUrls("djl://ai.djl.huggingface.pytorch/shibing624/text2vec-base-chinese")
                    .optEngine("PyTorch")
                    .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                    .build();

            */
            // 正确写法：
            String path = "E:/EIT/openai/text2vec-base-chinese-pt";
            path=EMBED_MODEL_PATH;
           // path = "C:/Users/Administrator/.djl.ai/cache/repo/model/nlp/text_embedding/ai/djl/huggingface/pytorch/shibing624/text2vec-base-chinese/0.0.1/text2vec-base-chinese";
           // path = "E:/EIT/openai/text2vec-base-chinese-pt";
           // System.out.println("当前加载目录: " + Paths.get("E:\\EIT\\openai\\text2vec-base-chinese-pt").toAbsolutePath());
            Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    // 注意：必须确保路径前面有 file:/
                    .optModelUrls("file:/" + path)
                    .optEngine("PyTorch")
                    // 🌟 核心修复 1: 显式告诉 DJL 权重文件叫 pytorch_model
                    // 注意：不要加 .bin 或 .pt 后缀，DJL 会自动根据引擎寻找
                    //.optOption("modelName", "pytorch_model")
                    // 🌟 核心修复 2: 显式指定 Translator，不要只靠 Factory 自动推断
                    .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                    .build();


            model = criteria.loadModel();


            initRerankModel(RERANK_MODEL_PATH);
        }
        // 🌟 2. 执行预热 (Warmup)
       // warmup(3); // 生产环境建议连续预热 3-5 次
    }
    private void initRerankModel(String modelPath1) throws Exception {

            // 🌟 修复 1：去掉 models/，对齐你 Python 脚本生成的绝对真实路径
          //  String modelPath = "E:/EIT/openai/bge-reranker-large";

            Criteria<String[], Float> criteria = Criteria.builder()
                    .setTypes(String[].class, Float.class)
                    // 🌟 修复 2：Windows 本地路径必须用 file:/// (三个斜杠)
                    .optModelUrls("file:///" + modelPath1)
                    .optEngine("PyTorch")
                    // 🌟 修复 3：明确告诉它，你要找的文件名叫 bge-reranker-base (.pt)
                    .optOption("modelName", modeName)
                    // 确保 Translator 也是去这个新路径下找 tokenizer.json
                    .optTranslator(new RerankerTranslator(modelPath1))
                    .build();

            this.rerankModel = criteria.loadModel();
            System.out.println("✅ Rerank 本地 TorchScript 模型彻底加载成功！");
        }

    private void initRerankModel3(String modelPath) throws Exception {
        // 必须使用 Criteria 的方式加载，这样 Criteria 内部会自动创建一个 ZooModel
        Criteria<String[], Float> criteria = Criteria.builder()
                .setTypes(String[].class, Float.class)
                .optModelUrls("file:/" + modelPath)
                .optEngine("PyTorch")
                .optOption("modelName", "pytorch_model")
                // 🌟 如果你定义了专门的 RerankerTranslator，就在这里指定
                .optTranslator(new RerankerTranslator(modelPath))
                .build();

        // 这里调用 loadModel() 会直接返回 ZooModel<String[], Float>，不会触发类型转换错误
        this.rerankModel = criteria.loadModel();

        System.out.println("✅ ZooModel 加载成功！");
    }
    public String chat(ArrayNode messages) throws Exception {
        System.out.println("🔥 如果这里运行，就出错！！！");
        return null;
    }
    public String generate(String query, String document) throws Exception {
        System.out.println("🔥 如果这里运行，就出错！！！");
        return null;
    }
    @Override
    public double rerank(String query, String document, String systemPrompt) throws Exception {
        // systemPrompt 对 Cross-Encoder 无意义，直接忽略
        try (var predictor = rerankModel.newPredictor()) {
            float rawLogit = predictor.predict(new String[]{query, document});
            double temperature = 0.5;
            return 1.0 / (1.0 + Math.exp(-rawLogit / temperature));
        }
    }
    public double rerank(String query, String document) throws Exception {
        // 使用 Cross-Encoder 进行深度交互打分
        // 注意：这里输入的是数组 [query, document]
        //Predictor 本身不是线程安全的。如果你的服务是高并发的，建议使用 model.newPredictor() 在方法内部创建局部预测器，或者使用 ThreadLocal 包装。
       //如果你的应用场景是高并发的 Web 服务，频繁创建 Predictor 可能会带来一定的开销，此时可以考虑使用 Predictor 对象池：
        //
        //使用 java.util.concurrent 包下的池化工具（例如 ObjectPool）来维护一组 Predictor 实例。
        //
        //每个请求（ChatSession）从池中借用一个 Predictor，使用完毕后归还。
        //
        //这种方法可以有效避免频繁创建和销毁对象的开销，同时保持线程安全
// 使用 Cross-Encoder 进行深度交互打分
        try (var predictor = rerankModel.newPredictor()) {
            // 1. 拿到原始 Logit
            float rawLogit = predictor.predict(new String[]{query, document});

            // 2. 使用标准的温度缩放 (不加 Offset 偏移，防止误伤)
            // T=0.5 能够在拉大差距和保护弱相关文本之间取得完美的平衡
            double temperature = 0.5;

            // 3. 标准 Sigmoid 映射
            double finalScore = 1.0 / (1.0 + Math.exp(-rawLogit / temperature));

            return finalScore;
        }

    }
    @Override
    public double[] embed(String text) throws Exception {
        try (var predictor = model.newPredictor()) {
            float[] fVec = predictor.predict(text);
            double[] dVec = new double[fVec.length];
            for (int i = 0; i < fVec.length; i++) dVec[i] = fVec[i];
            return dVec;
        }
    }
    public String modeType() {
        return "local"; // 声明自己是本地引擎
    }
    @Override
    public int getDimension() { return 768; }

    public static void main(String[] args) {
        try {
            System.out.println("⏳ 正在初始化本地 DJL Rerank 引擎..."+RERANK_MODEL_PATH);
            long initStart = System.currentTimeMillis();
            DJLLocalClient client = new DJLLocalClient();
            System.out.println("✅ 初始化完成！总耗时: " + (System.currentTimeMillis() - initStart) + " ms\n");

            // 预热 (消除冷启动差异)
            System.out.println("🔥 正在进行预热...");
            for (int i = 0; i < 3; i++) {
                client.generate("预热", "预热");
            }
            System.out.println("✅ 预热完成！\n");

            // ==========================================
            // 🚨 场景一：角色错位 (最经典的陷阱)
            // ==========================================
            System.out.println("==================================================");
            System.out.println("🚨 场景一：角色错位 (测试模型是否能精准识别主语)");
            String q1 = "学校管理员的初始密码是什么？";
            String q1_docA = "【适用对象：学校管理员】管理员账号是本校十位数的学校标识码，初始密码统一由所属教育局下发。"; // 正确
            String q1_docB = "【适用对象：教师】账号为个人身份证号码，初始密码为大写A202101小写b。"; // 陷阱：字面包含“初始密码”
            String q1_docC = "【适用对象：学生】个人账号是学生的个人身份证号码，初始密码是向大写A202101小写b。"; // 陷阱

            System.out.printf("[用户问题]: %s\n", q1);
            printTimedRank(client, "正确-管理员", q1, q1_docA);
            printTimedRank(client, "陷阱-教师", q1, q1_docB);
            printTimedRank(client, "陷阱-学生", q1, q1_docC);
            System.out.println();

            // ==========================================
            // 🚨 场景二：语义理解与口语化 (测试非关键字匹配)
            // ==========================================
            System.out.println("==================================================");
            System.out.println("🚨 场景二：口语化提问 (测试模型能否理解“孩子”=“学生”，“苹果”=“iOS”)");
            // String q2 = "苹果手机怎么装这个软件，孩子要用？"; // 导致docA分数很低
            // q2="学生版的APP对手机系统有什么要求？";
            // q2="学生电脑端要求Windows几才能运行？";
            String q2 = "我是学生，我的安卓手机需要升级到什么版本才能用？";
            String q2_docA = "【适用对象：学生】电脑客户端支持Windows 7或以上，安卓客户端支持安卓6.0版本或以上，苹果客户端支持iOS 13或以上的系统。"; // 正确
            String q2_docB = "【适用对象：教研员/教师】电脑客户端要求Windows 7或以上的操作系统。"; // 陷阱：教师无移动端

            System.out.printf("[用户问题]: %s\n", q2);
            printTimedRank(client, "正确-学生多端", q2, q2_docA);
            printTimedRank(client, "陷阱-教师单端", q2, q2_docB);
            System.out.println();

            // ==========================================
            // 🚨 场景三：条件分支嵌套 (逻辑迷宫)
            // ==========================================
            System.out.println("==================================================");
            System.out.println("🚨 场景三：条件迷宫 (测试模型能否处理前置条件)");
            String q3 = "我是学生，手机没绑定，密码忘了怎么办？";
            String q3_docA = "【适用对象：学生】若没绑定手机号，需联系本班教师或学校管理员重置密码；若已绑定手机号，可通过登录页面点击“忘记密码”自助重置。"; // 正确
            String q3_docB = "【适用对象：教师】若忘记密码，可在登录页面点击“忘记密码”通过手机接收验证码重置。"; // 陷阱：教师默认用手机找回

            System.out.printf("[用户问题]: %s\n", q3);
            printTimedRank(client, "正确-未绑定找老师", q3, q3_docA);
            printTimedRank(client, "陷阱-教师手机找回", q3, q3_docB);
            System.out.println();

            // ==========================================
            // 🚨 场景四：克隆文本鉴别 (终极挑战)
            // ==========================================
            System.out.println("==================================================");
            System.out.println("🚨 场景四：克隆文本 (正文 100% 相同，只有开头对象不同)");
            String q4 = "学校管理员的省/市区级培训在哪看？";
            String q4_docA = "【适用对象：学校管理员】请登录粤教翔云数字教材应用平台3.0客户端，点击“教研天地”，相关培训要求以当地教育部门发文为准。"; // 正确
            String q4_docB = "【适用对象：教研员/教师】请登录粤教翔云数字教材应用平台3.0客户端，点击“教研天地”，相关培训要求以当地教育部门发文为准。"; // 陷阱

            System.out.printf("[用户问题]: %s\n", q4);
            printTimedRank(client, "正确-管理员", q4, q4_docA);
            printTimedRank(client, "陷阱-教师", q4, q4_docB);
            System.out.println();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 辅助测试方法：执行 Rerank 并严格统计单次比对的耗时
     */
    private static void printTimedRank(DJLLocalClient client, String label, String query, String doc) {
        try {
            long start = System.currentTimeMillis();
            String score = client.generate(query, doc);
            long time = System.currentTimeMillis() - start;

            // %-14s 让标签左对齐，方便视觉对比；%3d 保证耗时位对齐
            System.out.printf("  👉 [%-14s] ⏱️耗时: %3d ms | 🎯打分: %s\n", label, time, score);
        } catch (Exception e) {
            System.out.printf("  👉 [%-14s] ❌ 评估失败: %s\n", label, e.getMessage());
        }
    }
    // --- 🚀 终极对比测试：证明 Rerank 价值的 Main 方法 ---
    public static void main生肉大法(String[] args) {
        try {
            System.out.println("⏳ 正在初始化双擎模型DJLLocalClient (Embedding + Rerank)...");
            DJLLocalClient client = new DJLLocalClient();
            System.out.println("✅ 初始化完成！\n");

            // ==========================================
            // 0. 🔥 模型预热 (Warm-up)
            // ==========================================
            System.out.println("🔥 正在进行模型预热 (消除冷启动延迟)...");
            long warmupStart = System.currentTimeMillis();
            // 循环几次，让底层推理引擎（C++/CUDA）初始化完毕，并触发 Java JIT 编译
            for (int i = 0; i < 3; i++) {
                client.embed("这是预热测试用的占位文本。");
                client.generate("预热问题", "预热文档内容");
            }
            long warmupTime = System.currentTimeMillis() - warmupStart;
            System.out.println("✅ 预热完成！(预热阶段总耗时: " + warmupTime + " ms)\n");

            // ==========================================
            // 构造极端的“困难负样本”测试集
            // ==========================================
            String query = "教师的默认登录口令是什么？";

            // 正确答案：语义一致，但特意避开了“默认、登录、口令、是什么”这些词
            String docA_Correct = "【适用对象：老师】初始密码统一设置为大写A202101小写b。";
            // docA_Correct="教师的默认登录口令是1234567";

            // 错误陷阱：句式完美对齐，包含了连续的“默认登录口令”，但实体是“学生”
            String docB_Trap = "【适用对象：学生】的默认登录口令是身份证后六位。";

            // String docA_Correct = "教师的默认登录口令是大写A202101小写b。";
            // String docB_Trap = "学生的默认登录口令是身份证后六位。";

            System.out.println("【用户提问】: " + query);
            System.out.println("---------------------------------------------------------");
            System.out.println("[文档 A - 语义正确但字面不同]: " + docA_Correct);
            System.out.println("[文档 B - 语义错误但字面高度重合]: " + docB_Trap);
            System.out.println("---------------------------------------------------------\n");

            // ==========================================
            // 1. 粗排测试：Embedding 向量检索测试
            // ==========================================
            System.out.println("=== 🧪 阶段一：Embedding 向量检索测试 ===");
            long embedStart = System.currentTimeMillis();
            double[] vQuery = client.embed(query);
            double[] vDocA = client.embed(docA_Correct);
            double[] vDocB = client.embed(docB_Trap);
            long embedTime = System.currentTimeMillis() - embedStart;

            System.out.printf("⏱️ Embedding 3条文本实际计算总耗时: %d ms (平均: %d ms/条)\n\n", embedTime, embedTime / 3);

            // 使用你写好的距离打印法
            printDistance("正确答案 (字面不同)", query, docA_Correct, vQuery, vDocA);
            printDistance("陷阱答案 (字面重合)", query, docB_Trap, vQuery, vDocB);


            // ==========================================
            // 2. 精排测试：Rerank 交叉精排测试
            // ==========================================
            System.out.println("=== 🎯 阶段二：Rerank 交叉精排测试 ===");
            long rerankTotalStart = System.currentTimeMillis();

            long start = System.currentTimeMillis();
            String scoreA = client.generate(query, docA_Correct);
            long timeA = System.currentTimeMillis() - start;

            start = System.currentTimeMillis();
            String scoreB = client.generate(query, docB_Trap);
            long timeB = System.currentTimeMillis() - start;

            long rerankTotalTime = System.currentTimeMillis() - rerankTotalStart;

            System.out.printf("[正确答案 - 耗时 %d ms] 👉 Rerank 打分/距离: %s\n", timeA, scoreA);
            System.out.printf("[陷阱答案 - 耗时 %d ms] 👉 Rerank 打分/距离: %s\n", timeB, scoreB);
            System.out.printf("⏱️ Rerank 2次比对实际计算总耗时: %d ms\n\n", rerankTotalTime);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // --- 🚀 简单测试 Main 方法 ---
    public static void main21(String[] args) {
        try {
            System.out.println("⏳ 正在初始化双擎模型 (Embedding + Rerank)...");
            long initStart = System.currentTimeMillis();
            DJLLocalClient client = new DJLLocalClient();
            System.out.println("✅ 初始化完成，耗时: " + (System.currentTimeMillis() - initStart) + " ms\n");

            // ==========================================
            // 测试 1: 向量化 Embedding 距离测试
            // ==========================================
            String s1 = "老师的初始密码是什么？";
            String s2 = "教研员的账号密码是多少";
            String s3 = "今天天气不错";

            double[] v1 = client.embed(s1);
            double[] v2 = client.embed(s2);
            double[] v3 = client.embed(s3);

            System.out.println("=== 🧪 阶段一：向量检索距离测试 (0.2 风格) ===");
            printDistance("相关句测试", s1, s2, v1, v2);
            printDistance("无关句测试", s1, s3, v1, v3);


            // ==========================================
            // 测试 2: Rerank 交叉编码器精度测试
            // ==========================================
            System.out.println("=== 🎯 阶段二：Rerank 交叉精排能力测试 ===");

            String query = "老师的初始密码是多少？";
            // 文档1：强相关（完全符合教师身份）
            String doc1 = "账号为个人身份证号码，初始密码为大写A202101小写b。若忘记密码，可在登录页面点击“忘记密码”通过手机接收验证码重置。";
            // 手动补全丢失的上下文实体
              doc1 = "【适用对象：教师】账号为个人身份证号码，初始密码为大写A202101小写b。若忘记密码，可在登录页面点击“忘记密码”通过手机接收验证码重置。";
                doc1="老师的初始密码是：大写A202101小写b。";
              // 文档2：高混淆（包含极多相似词，但主体是学生）
            String doc3 = "个人账号是学生的个人身份证号码，初始密码是向大写A202101小写b。";
            // 文档3：极无关
            String doc2 = "电脑客户端要求Windows 7或以上的操作系统。暂不支持苹果 iOS 或安卓移动端。";

            // 执行测试并统计单次耗时
            long start1 = System.currentTimeMillis();
            String score1 = client.generate(query, doc1);
            long t1 = System.currentTimeMillis();

            String score3 = client.generate(query, doc3);
            long t2 = System.currentTimeMillis();

            String score2 = client.generate(query, doc2);
            long t3 = System.currentTimeMillis();

            System.out.printf("【用户提问】: %s\n", query);
            System.out.println("-------------------------------------------------------------------------");

            System.out.printf("[强相关 - 耗时 %3d ms] 教师密码说明\n", (t1 - start1));
            System.out.printf("内容: %s\n👉 Rerank 原始打分: %s \n\n", doc1, score1);

            System.out.printf("[易混淆 - 耗时 %3d ms] 学生密码说明 (向量检索容易误判)\n", (t2 - t1));
            System.out.printf("内容: %s\n👉 Rerank 原始打分: %s \n\n", doc3, score3);

            System.out.printf("[极无关 - 耗时 %3d ms] 安装环境说明\n", (t3 - t2));
            System.out.printf("内容: %s\n👉 Rerank 原始打分: %s \n\n", doc2, score2);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- 🚀 简单测试 Main 方法 ---
    public static void main2(String[] args) {
        try {
            DJLLocalClient client = new DJLLocalClient();

            String s1 = "老师的初始密码是什么？";
            String s2 = "教研员的账号密码是多少";
            String s3 = "今天天气不错";

            double[] v1 = client.embed(s1);
            double[] v2 = client.embed(s2);
            double[] v3 = client.embed(s3);

            System.out.println("\n=== 🧪 语义距离测试 (0.2 风格) ===");
            printDistance("相关句测试", s1, s2, v1, v2);
            printDistance("无关句测试", s1, s3, v1, v3);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printDistance(String label, String t1, String t2, double[] v1, double[] v2) {
// 这样写性能更好，且逻辑与 Postgres 对齐
        double dotProduct = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            normA += v1[i] * v1[i]; // 代替 Math.pow
            normB += v2[i] * v2[i]; // 代替 Math.pow
        }
        double cosineSim = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        double distance = 1.0 - cosineSim; // 现在和 Postgres 握手成功了

        System.out.printf("[%s]\n   A: %s\n   B: %s\n   👉 统一距离: %.4f\n\n", label, t1, t2, distance);
    }
}