package com.lcallai;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public class ChatHistory {
	int max_ask_history_to_ai=10;
    /**
     * 内部消息结构
     */
    public static class Message {
        private String role; /* user system assistant */
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
    public  ChatHistory(int size){
    	max_ask_history_to_ai=size;
    }
    private final LinkedList<Message> messages = new LinkedList<>();

    /**
     * 添加消息
     * - system 消息唯一且始终置顶
     * - user / assistant 追加到末尾
     */
    public void addMessage(String role, String content) {
        if ("system".equalsIgnoreCase(role)) {
            // 删除已有 system 消息
            Iterator<Message> iterator = messages.iterator();
            while (iterator.hasNext()) {
                if ("system".equalsIgnoreCase(iterator.next().getRole())) {
                    iterator.remove();
                    break; // 只可能存在一个
                }
            }
            // 插入到首位
            messages.addFirst(new Message("system", content));
        } else {
            messages.addLast(new Message(role, content));
        }
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
            if (!messages.isEmpty() && "system".equalsIgnoreCase(messages.getFirst().getRole())) {
                // 保留 system，从索引 1 删除
                messages.remove(1);
            } else {
                // 理论上不会发生，但做安全处理
                messages.removeFirst();
            }
        }
    }
    /**
     * 转换为 Jackson ArrayNode，并使用滑动窗口限制发给大模型的上下文
     * @param windowSize 限制发送给 LLM 的最近消息条数（例如 10 条，即最近 5 轮）
     */
    public ArrayNode toJsonArrayWithWindow( ) {
    		int windowSize=max_ask_history_to_ai;
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arrayNode = mapper.createArrayNode();

        // 1. 始终强制保留 System Message (知识库和人设)
        if (!messages.isEmpty() && "system".equalsIgnoreCase(messages.getFirst().getRole())) {
            ObjectNode sysNode = mapper.createObjectNode();
            sysNode.put("role", messages.getFirst().getRole());
            sysNode.put("content", messages.getFirst().getContent());
            arrayNode.add(sysNode);
        }

        // 2. 计算滑动窗口的起始点
        int size = messages.size();
        // 排除掉 system 消息后，从哪里开始截取
        int startIndex = Math.max(1, size - windowSize); 

        // 3. 只把最近的 windowSize 条消息加进去
        for (int i = startIndex; i < size; i++) {
            Message msg = messages.get(i);
            ObjectNode node = mapper.createObjectNode();
            node.put("role", msg.getRole());
            node.put("content", msg.getContent());
            arrayNode.add(node);
        }

        return arrayNode;
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

    public String toPlainText(int windowSize) {
        StringBuilder sb = new StringBuilder();
        int size = messages.size();
        int start = Math.max(1, size - windowSize);
        for (int i = start; i < size; i++) {
            Message msg = messages.get(i);
            if ("system".equalsIgnoreCase(msg.getRole())) continue;
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString().trim();
    }
}
