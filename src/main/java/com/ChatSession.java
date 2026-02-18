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

public class ChatSession {
    private static final int MAX_HISTORY = 10;         // 最大保留消息条数
    private static final int MAX_MESSAGE_LENGTH = 1000; // 单条消息最大字符数

    private final List<Message> history = new ArrayList<>();
    private final String systemMessage;

    public ChatSession(String systemMessage) {
        this.systemMessage = systemMessage;
        // 系统消息始终在第一条
        history.add(new Message("system", systemMessage));
    }
    
    public String ask(String text) {
    		addUserMessage(text);
    		String an=sendToOpenAI();
    		return an;
    }
    public String sendToOpenAI( )  {

    
       

    		try {
        // 1. 创建 ObjectMapper 实例 (通常建议作为单例复用)
        ObjectMapper mapper = new ObjectMapper();

        // 2. 创建根节点 (对应原本的 bodyJson)
        ObjectNode rootNode = mapper.createObjectNode();
        rootNode.put("model", "gpt-4o-mini");
        rootNode.put("max_output_tokens", 300);

        // 3. 创建 input 数组节点 (对应原本的 inputBuilder)
        ArrayNode inputArray = rootNode.putArray("input");

        List<ChatSession.Message> history         = getHistory();

        for (ChatSession.Message msg : history) {
            // 逻辑保持不变
            String type = "assistant".equals(msg.role) ? "output_text" : "input_text";

            // 创建单条消息对象
            ObjectNode messageNode = inputArray.addObject();
            messageNode.put("role", msg.role);

            // 创建 content 数组
            ArrayNode contentArray = messageNode.putArray("content");
            
            // 创建 content 内部的对象
            ObjectNode contentItem = contentArray.addObject();
            contentItem.put("type", type);
            // 重点：这里不需要 escapeJson()，Jackson 会自动处理所有特殊字符
            contentItem.put("text", msg.content); 
        }
        String bodyJson="";
        // 4. 生成最终 JSON 字符串
       
            // 如果你想看 input 部分 (对应原代码的 System.out.println)
          //  System.out.println("inputArray=" + mapper.writeValueAsString(inputArray));

            // 生成最终的 bodyJson
            bodyJson = mapper.writeValueAsString(rootNode);
            //System.out.println("Final request JSON: " + bodyJson);
            
      
        
        String aiText=SessionManager.sendToOpenAI(bodyJson);

        addAssistantMessage(aiText); 
        return aiText;
    		  } catch (Exception e) {
    	            e.printStackTrace();
    	            return "system error";
    	        }
    	        
      
    }
    public void addUserMessage(String text) {
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH);
        }
        history.add(new Message("user", text));
        trimHistory();
    }

    public void addAssistantMessage(String text) {
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH);
        }
        history.add(new Message("assistant", text));
        trimHistory();
    }

    private void trimHistory() {
        // 保留系统消息 + 最近 MAX_HISTORY 条消息
        int start = Math.max(1, history.size() - MAX_HISTORY);
        List<Message> recent = new ArrayList<>();
        recent.add(history.get(0)); // 系统消息
        for (int i = start; i < history.size(); i++) {
            recent.add(history.get(i));
        }
        history.clear();
        history.addAll(recent);
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
