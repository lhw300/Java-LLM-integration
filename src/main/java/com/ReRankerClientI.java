package com;



import java.util.List;

public interface ReRankerClientI {
    /**
     * 对检索出的知识项进行重排序
     * @param query 用户问题
     * @param candidates 候选知识列表
     * @return 排序后的列表（score 已更新为统一距离：越小越准）
     */
    List<SearchService.KnowledgeItem> rerank(String query, List<SearchService.KnowledgeItem> candidates) throws Exception;

    /**
     * 标识当前模式（local 或 online）
     */
    String modeType();
}