package com;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ChatSessionOptimized {
    private static final int MAX_HISTORY = 10;
    private static final int MAX_MESSAGE_LENGTH = 1000;
    
    // ⚡ 优化 1: 复用 ObjectMapper (单例)
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Message> history = new ArrayList<>();
    private final String systemMessage;

    public ChatSessionOptimized(String systemMessage) {
        this.systemMessage = systemMessage;
        history.add(new Message("system", systemMessage));
    }
    
    /**
     * ⚡ 优化版 ask() - 速度提升点：
     * 1. 减少字符串拼接
     * 2. 复用 ObjectMapper
     * 3. 减少不必要的对象创建
     */
    public String ask(String text) {
        // 提前验证和处理输入
        if (text == null || text.isEmpty()) {
            return "Invalid input";
        }
        
        // 截断过长消息
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH);
        }
        
        // 添加用户消息
        history.add(new Message("user", text));
        trimHistory();
        
        try {
            // 发送到 OpenAI
            String answer = sendToOpenAI();
            
            // 添加助手回复
            if (answer != null && !answer.isEmpty()) {
                if (answer.length() > MAX_MESSAGE_LENGTH) {
                    answer = answer.substring(0, MAX_MESSAGE_LENGTH);
                }
                history.add(new Message("assistant", answer));
                trimHistory();
            }
            
            return answer;
            
        } catch (Exception e) {
            e.printStackTrace();
            return "System error";
        }
    }
    
    /**
     * ⚡ 优化版 sendToOpenAI()
     */
    private String sendToOpenAI() throws Exception {
        // 创建请求 JSON
        ObjectNode rootNode = MAPPER.createObjectNode();
        rootNode.put("model", "gpt-4o-mini");
        rootNode.put("max_output_tokens", 300);

        // 创建 input 数组
        ArrayNode inputArray = rootNode.putArray("input");

        // ⚡ 优化 2: 直接遍历 history，避免创建副本
        for (Message msg : history) {
            String type = "assistant".equals(msg.role) ? "output_text" : "input_text";

            ObjectNode messageNode = inputArray.addObject();
            messageNode.put("role", msg.role);

            ArrayNode contentArray = messageNode.putArray("content");
            ObjectNode contentItem = contentArray.addObject();
            contentItem.put("type", type);
            contentItem.put("text", msg.content);
        }

        // 生成 JSON
        String bodyJson = MAPPER.writeValueAsString(rootNode);
        
        // 调用优化版 SessionManagerOptimized
        return SessionManagerOptimized.sendToOpenAI(bodyJson);
    }

    /**
     * ⚡ 优化版 trimHistory() - 减少列表操作
     */
    private void trimHistory() {
        // 如果历史记录未超限，直接返回
        if (history.size() <= MAX_HISTORY + 1) { // +1 是系统消息
            return;
        }
        
        // 只保留系统消息 + 最近 MAX_HISTORY 条
        int removeCount = history.size() - MAX_HISTORY - 1;
        
        // 从索引 1 开始删除（保留系统消息）
        for (int i = 0; i < removeCount; i++) {
            history.remove(1); // 始终删除索引1的元素
        }
    }

    public List<Message> getHistory() {
        return new ArrayList<>(history);
    }

    public static class Message {
        public final String role;
        public final String content;
        
        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
