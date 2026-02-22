package com;

 

/**
 * 统一的向量模型接口
 */
public interface EmbeddingClient {
    /**
     * 将文本转为向量
     */
    double[] embed(String text) throws Exception;
}