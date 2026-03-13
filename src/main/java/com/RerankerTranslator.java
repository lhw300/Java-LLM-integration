package com;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.nio.file.Paths;

public class RerankerTranslator implements Translator<String[], Float> {
    private HuggingFaceTokenizer tokenizer;

    public RerankerTranslator(String modelPath) {
        try {
            // 从指定路径加载 tokenizer.json
            this.tokenizer = HuggingFaceTokenizer.newInstance(Paths.get(modelPath));
        } catch (Exception e) {
            // 🌟 CATCH AND RE-THROW AS RUNTIME EXCEPTION
            throw new RuntimeException("Failed to load tokenizer from: " + modelPath, e);
        }
    }

    @Override
    public void prepare(TranslatorContext ctx) throws Exception {
    }

    @Override
    public NDList processInput(TranslatorContext ctx, String[] input) {
        // 对两句话进行交叉编码（拼接在一起）
        Encoding encoding = tokenizer.encode(input[0], input[1]);
        NDManager manager = ctx.getNDManager();

        NDList list = new NDList();
        // 🌟 核心修复：添加 .expandDims(0) 补齐 Batch 维度，变成 [1, seq_len]
        list.add(manager.create(encoding.getIds()).expandDims(0));
        list.add(manager.create(encoding.getAttentionMask()).expandDims(0));

        return list;
    }

    @Override
    public Float processOutput(TranslatorContext ctx, NDList list) {
        // 提取模型打分（Logits）
        float[] scores = list.get(0).toFloatArray();
        return scores[0];
    }

    @Override
    public Batchifier getBatchifier() {
        // 因为我们在 processInput 里手动补充了 Batch 维度，所以这里设为 null 避免 DJL 再次自动套壳
        return null;
    }
}