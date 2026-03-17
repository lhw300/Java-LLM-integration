package com.lcallai;



import java.util.List;
import java.util.stream.Collectors;

public class PromptBuilder {

    // 🌟 生产环境建议的距离阈值
    private static final double DIST_STRONG = 0.25; // 强相关阈值
    private static final double DIST_WEAK = 0.45;   // 弱相关/过滤阈值

    /**
     * 动态构建包含背景知识的 Prompt
     */
    public static String buildRAGPrompt(String userQuery, List<SearchService.KnowledgeItem> items) {
        // 1. 过滤掉距离太远（不相关）的干扰项
        List<SearchService.KnowledgeItem> filteredItems = items.stream()
                .filter(item -> item.distance <= DIST_WEAK)
                .collect(Collectors.toList());

        if (filteredItems.isEmpty()) {
            return userQuery; // 如果没搜到相关知识，直接按普通对话处理
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的客服助手。请参考以下【已知信息】来回答用户的问题。");
        sb.append("如果已知信息中没有相关内容，请告知用户你暂不清楚，不要胡乱猜测。\n\n");
        sb.append("【已知信息】:\n");

        for (SearchService.KnowledgeItem item : filteredItems) {
            // 2. 根据距离动态添加权重标记或优先级描述
            if (item.distance <= DIST_STRONG) {
                // 🌟 对于 0.23 这种高质量匹配，明确告诉模型这是核心依据
                sb.append("（核心参考）");
            } else {
                sb.append("（参考）");
            }

            sb.append(String.format("【%s - %s】: %s\n",
                    item.category, item.summary, item.content));
        }

        sb.append("\n【用户问题】: ").append(userQuery);
        sb.append("\n【助手回答】: ");

        return sb.toString();
    }
}