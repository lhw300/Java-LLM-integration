package com;



import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.Model;
public class DJLLocalClient implements EmbeddingClient {
    private static ZooModel<String, float[]> model;
    private ZooModel<String[], Float> rerankModel; // 专门用于 Cross-Encoder 的模型
    //private Predictor<String[], Float> rerankPredictor;
    // 统一配置路径
    private static final String RERANK_MODEL_PATH = "E:/EIT/openai/models/bge-reranker-base";
    private static final String EMBED_MODEL_PATH = "E:/EIT/openai/text2vec-base-chinese-pt";
    String localModelPath = "file:///E:/EIT/openai/text2vec-base-chinese-paraphrase";
// 1. 指定原生 PyTorch 核心库路径
// 🌟 必须包裹在 static 块中，否则报 <identifier> expected 错误
static {
    System.setProperty("PYTORCH_LIBRARY_PATH", "E:/EIT/openai/libtorch");
    System.setProperty("java.library.path", "E:/EIT/openai/libtorch");
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
            path = "C:/Users/Administrator/.djl.ai/cache/repo/model/nlp/text_embedding/ai/djl/huggingface/pytorch/shibing624/text2vec-base-chinese/0.0.1/text2vec-base-chinese";
            path = "E:/EIT/openai/text2vec-base-chinese-pt";
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
            String modelPath = "E:/EIT/openai/bge-reranker-large";

            Criteria<String[], Float> criteria = Criteria.builder()
                    .setTypes(String[].class, Float.class)
                    // 🌟 修复 2：Windows 本地路径必须用 file:/// (三个斜杠)
                    .optModelUrls("file:///" + modelPath)
                    .optEngine("PyTorch")
                    // 🌟 修复 3：明确告诉它，你要找的文件名叫 bge-reranker-base (.pt)
                    .optOption("modelName", "bge-reranker-base")
                    // 确保 Translator 也是去这个新路径下找 tokenizer.json
                    .optTranslator(new RerankerTranslator(modelPath))
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
    public String generate(String query, String document) throws Exception {
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
        try (var predictor = rerankModel.newPredictor()) {
            float score = predictor.predict(new String[]{query, document});
            return String.valueOf(score);
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
    // --- 🚀 终极对比测试：证明 Rerank 价值的 Main 方法 ---
    public static void main(String[] args) {
        try {
            System.out.println("⏳ 正在初始化双擎模型 (Embedding + Rerank)...");
            DJLLocalClient client = new DJLLocalClient();
            System.out.println("✅ 初始化完成！\n");

            // ==========================================
            // 构造极端的“困难负样本”测试集
            // ==========================================
            String query = "教师的默认登录口令是什么？";

            // 正确答案：语义一致，但特意避开了“默认、登录、口令、是什么”这些词
            String docA_Correct = "【适用对象：老师】初始密码统一设置为大写A202101小写b。";

            // 错误陷阱：句式完美对齐，包含了连续的“默认登录口令”，但实体是“学生”
            String docB_Trap = "【适用对象：学生】的默认登录口令是身份证后六位。";


           // String docA_Correct = "教师的默认登录口令是大写A202101小写b。";
           // String docB_Trap = "学生的默认登录口令是身份证后六位。";

            System.out.println("【用户提问】: " + query);
            System.out.println("---------------------------------------------------------");
            System.out.println("[文档 A - 语义正确但字面不同]: " + docA_Correct);
            System.out.println("[文档 B - 语义错误但字面高度重合]: " + docB_Trap);
            System.out.println("---------------------------------------------------------\n");

            // 1. 粗排测试：直接调用你现成的 embed 和 printDistance
            System.out.println("=== 🧪 阶段一：Embedding 向量检索测试 ===");
            double[] vQuery = client.embed(query);
            double[] vDocA = client.embed(docA_Correct);
            double[] vDocB = client.embed(docB_Trap);

            // 使用你写好的 0.2 风格距离打印法
            printDistance("正确答案 (字面不同)", query, docA_Correct, vQuery, vDocA);
            printDistance("陷阱答案 (字面重合)", query, docB_Trap, vQuery, vDocB);

            // 2. 精排测试：直接调用你现成的 generate
            System.out.println("=== 🎯 阶段二：Rerank 交叉精排测试 ===");
            long start = System.currentTimeMillis();
            String scoreA = client.generate(query, docA_Correct);
            long timeA = System.currentTimeMillis() - start;

            start = System.currentTimeMillis();
            String scoreB = client.generate(query, docB_Trap);
            long timeB = System.currentTimeMillis() - start;

            System.out.printf("[正确答案 - 耗时 %d ms] 👉 Rerank 打分/距离: %s\n", timeA, scoreA);
            System.out.printf("[陷阱答案 - 耗时 %d ms] 👉 Rerank 打分/距离: %s\n\n", timeB, scoreB);

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
        double dotProduct = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            normA += Math.pow(v1[i], 2);
            normB += Math.pow(v2[i], 2);
        }
        double cosineSim = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        // 🌟 转换为你习惯的距离：D = 2 * (1 - S)
        double distance = 2.0 * (1.0 - cosineSim);

        System.out.printf("[%s]\n   A: %s\n   B: %s\n   👉 统一距离: %.4f\n\n", label, t1, t2, distance);
    }
}