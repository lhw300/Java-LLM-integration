package com.lcallai;

 

/**
 * 统一的向量模型接口
 */
public interface EmbeddingClient {
    /**
     * 将文本转为向量
     */
    double[] embed(String text) throws Exception;
    // 新增：让上层知道当前模型的维度
    default int getDimension() {
        return 0;
    }
    default String modeType() {
        return "online";
    }
}