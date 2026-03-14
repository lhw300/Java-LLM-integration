package com;

/**
 * 模型路由器 - 按业务职责分派不同的模型实例
 *
 * 设计原则：
 *   - 调用方（ChatSession）只依赖此路由器，不直接持有具体客户端
 *   - 每个"职责角色"对应一个独立的 LlmClient 实例
 *   - 新增角色只需在此处扩展，外部代码无需修改
 *
 * 当前路由策略：
 *   REWRITE  → qwen-turbo  (快速、低成本，适合查询改写)
 *   RERANK   → qwen-turbo  (快速、低成本，适合语义评分)
 *   FINAL    → qwen-plus   (高质量，适合最终回答生成)
 */
public class ModelRouter {

    /** 职责枚举：表示当前调用的业务用途 */
    public enum Role {
        /** 查询重写：将用户原始提问结合历史改写成语义完整的检索句 */
        REWRITE,
        /** 精排打分：对候选知识条目进行语义相关性评分 */
        RERANK,
        /** 最终回答：基于检索结果生成用户可见的完整答案 */
        FINAL
    }

    private final LlmClient rewriteClient;
    private final LlmClient rerankClient;
    private final LlmClient finalClient;
    private String rerankPrompt = ""; // 绑定在路由层
    public void setRerankPrompt(String prompt) {
        this.rerankPrompt = prompt;
    }
    /**
     * 构造路由器。
     *
     * @param rewriteClient 用于查询重写的模型（推荐 turbo 系列）
     * @param rerankClient  用于精排打分的模型（推荐 turbo 系列）
     * @param finalClient   用于最终回答的模型（推荐 plus/max 系列）
     */
    public ModelRouter(LlmClient rewriteClient, LlmClient rerankClient, LlmClient finalClient) {
        if (rewriteClient == null || rerankClient == null || finalClient == null) {
            throw new IllegalArgumentException("ModelRouter: 所有角色对应的客户端均不能为 null");
        }
        this.rewriteClient = rewriteClient;
        this.rerankClient  = rerankClient;
        this.finalClient   = finalClient;
    }

    /**
     * 便捷工厂方法：当 rewrite 和 rerank 使用同一个客户端时，简化构造。
     *
     * @param lightClient 轻量级模型（同时担任 rewrite + rerank）
     * @param heavyClient 重量级模型（担任 final）
     */
    public static ModelRouter of(LlmClient lightClient, LlmClient heavyClient) {
        return new ModelRouter(lightClient, lightClient, heavyClient);
    }

    /**
     * 根据业务职责获取对应的模型客户端。
     *
     * @param role 业务职责枚举
     * @return 对应的 LlmClient 实例
     */
    public LlmClient get(Role role) {
        return switch (role) {
            case REWRITE -> rewriteClient;
            case RERANK  -> rerankClient;
            case FINAL   -> finalClient;
        };
    }
    /**
     * 对外暴露的 rerank 入口，prompt 已内聚，调用方无感知
     */
    public double rerank(String query, String document) throws Exception {
        return rerankClient.rerank(query, document, rerankPrompt);
    }
    // ── 语义糖快捷方法，提升调用处可读性 ──────────────────────────────────────

    /** 获取负责查询重写的客户端 */
    public LlmClient rewriter()  { return rewriteClient; }

    /** 获取负责精排评分的客户端 */
   // public LlmClient reranker()  { return rerankClient;  }

    /** 获取负责最终回答的客户端 */
    public LlmClient finalLlm()  { return finalClient;   }
}
