package com.lcallai;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class QueryHistory {

    /**
     * 内部消息结构
     */
    public static class Message {
        private String role; /* User Context */
        private String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }

    private final LinkedList<Message> messages = new LinkedList<>();
    List<String> historyList = new ArrayList<>();

    /**
     * 添加消息
     * - system 消息唯一且始终置顶
     * - user / assistant 追加到末尾
     */
    public void addMessage(String role, String content) {
      
            messages.addLast(new Message(role, content));
        
    }
    public void addMessage(String msg) {
        
    		historyList.add(msg);
    
}
    /**
     * ✅ 优化：滑动窗口获取对话历史
     * @param historyList 存储所有对话条目的列表
     * @param lastK 保留最近的几轮对话（建议 3-5 轮）
     */
    private static String getLimitedHistory(List<String> historyList, int lastK) {
        if (historyList == null || historyList.isEmpty()) {
            return "";
        }

        // 一轮对话包含 User 和 AI 两个条目，所以窗口大小为 lastK * 2
        int windowSize = lastK * 2;
        int size = historyList.size();
        int start = Math.max(0, size - windowSize);

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < size; i++) {
            sb.append(historyList.get(i)).append("\n");
        }
        return sb.toString().trim();
    }
    
    /**
     * 限制历史对话条数
     * - 始终保留索引 0 的 system 消息
     * - 从索引 1 开始删除最早的对话
     */
    public void trim(int maxSize) {
        if (maxSize <= 0) {
            return;
        }

        while (messages.size() > maxSize) {
         
                // 理论上不会发生，但做安全处理
                messages.removeFirst();
           
        }
    }
    public  String getMessageWindowSize(int SIZE) {
    StringBuilder queryHistoryBuilder = new StringBuilder();
    List<QueryHistory.Message> qMsgs = messages;

    // ⚡ 滑动窗口：只取最近的 6 条记录 (相当于最近的 3 次 User 提问 + 3 次 Context)
    int windowSize = SIZE; 
    int startIndex = Math.max(0, qMsgs.size() - windowSize);

    for (int i = startIndex; i < qMsgs.size(); i++) {
        QueryHistory.Message msg = qMsgs.get(i);
        queryHistoryBuilder.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
    }
    
    return queryHistoryBuilder.toString();
}
    
    
    /**
     * 转换为 Jackson ArrayNode
     */
    public ArrayNode toJsonArray() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arrayNode = mapper.createArrayNode();

        for (Message msg : messages) {
            ObjectNode node = mapper.createObjectNode();
            node.put("role", msg.getRole());
            node.put("content", msg.getContent());
            arrayNode.add(node);
        }

        return arrayNode;
    }
   
    /**
     * 获取当前消息列表（只读）
     */
    public List<Message> getMessages() {
        return List.copyOf(messages);
    }

    /**
     * 清空历史（保留结构）
     */
    public void clear() {
        messages.clear();
    }
}
