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
    private static final int MAX_HISTORY = 40;
    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final double SIMILARITY_THRESHOLD = 0.5;
    // ⚡ 优化 1: 复用 ObjectMapper (单例)
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatHistory history=new ChatHistory();
    private   String systemMessage="";
    ChatAnswer ca=new ChatAnswer();
	/*
	 * public ChatSessionOptimized(String systemMessage) {
	 * 
	 * this.systemMessage = systemMessage; history.add(new Message("system",
	 * systemMessage)); }
	 */
    public ChatSessionOptimized( ) {
		 
       // this.systemMessage = systemMessage;
       // history.add(new Message("system", systemMessage));
    }
    void setSystemMessage(String system){
    		//this.systemMessage = system;
    		buildSystem(system);
    		history.addMessage("system", systemMessage);
    		
    		
    }
    
    public   void buildSystem(String knowledgeContext) {
		if(knowledgeContext==null)
			return  ;
		systemMessage= "You are a helpful enterprise assistant.\n" +
           "Use the following pieces of retrieved context to answer the question.\n" +
           "If you don't know the answer, just say you don't know. Keep the answer concise.\n\n" +
           "=== CONTEXT ===\n" +
           knowledgeContext + "\n" +
           "=== END CONTEXT ===\n";
		
    }
 
    /**
     * ⚡ 优化版 ask() - 速度提升点：
     * 1. 减少字符串拼接
     * 2. 复用 ObjectMapper
     * 3. 减少不必要的对象创建
     */
    public void addUserHis(String text) {
        // 添加用户消息
        history.addMessage("user",text);
        history.trim(MAX_HISTORY);
       // trimHistory();
    }
    /*
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
        addUserHis(text);
        
        try {
            // 发送到 OpenAI
            String answer = sendToOpenAI();
            
            // 添加助手回复
            if (answer != null && !answer.isEmpty()) {
                if (answer.length() > MAX_MESSAGE_LENGTH) {
                    answer = answer.substring(0, MAX_MESSAGE_LENGTH);
                }
                history.addMessage("assistant", answer);
                history.trim(MAX_HISTORY);
                
            }
            
            return answer;
            
        } catch (Exception e) {
            e.printStackTrace();
            return "System error";
        }
    }
    */
    
    public ChatAnswer ask(String text) {
        System.out.println("✅ ask AI...");
        // 提前验证和处理输入
        if (text == null || text.isEmpty()) {
        	 		ca.code=-1;
        	 		ca.answer="客户问题为空";
        	 		return ca;
           // return "Invalid input";
        }
        
        // 截断过长消息
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH);
        }
        
        // 添加用户消息
        addUserHis(text);
        try {
        List<SearchService.KnowledgeItem> items = SearchService.getRelevantKnowledge(text);
        System.out.println("✅ items ."+items.size());
        if (items.isEmpty()) {
            System.out.println("⚠️ 知识库中没有找到任何内容");
            //handleNoKnowledge(clientId, userQuery);
           // session.addHis(userQuery);
          
            ca.code=-2;
            ca.answer="知识库中没有找到任何内容";
            return ca;
        }
        
        double bestDistance = items.get(0).distance;
        System.out.println("📊 最佳匹配距离: " + String.format("%.4f", bestDistance));

        if (bestDistance > SIMILARITY_THRESHOLD) {
            System.out.println("⚠️ 相似度不足 (阈值: " + SIMILARITY_THRESHOLD + ")");
            
            System.out.println("🔄 检测到低相似度，但仍尝试使用最接近的知识\n");
            
            // 显示找到的知识
            System.out.println("📚 找到的最接近内容:");
            for (int i = 0; i < Math.min(3, items.size()); i++) {
                System.out.println((i + 1) + ". " + items.get(i).content + 
                    " (距离: " + String.format("%.4f", items.get(i).distance) + ")");
            }
            System.out.println();
            
            // 选项1: 直接返回预设回答
			/*
			 * System.out.println("💬 系统回答:");
			 * System.out.println("抱歉，我在知识库中未找到与您问题完全相关的信息。"); System.out.println("您可以尝试:");
			 * System.out.println("1. 换一种方式描述您的问题"); System.out.println("2. 联系人工客服获取帮助");
			 */
            
            ca.code=-100;
            ca.answer="抱歉，我在知识库中未找到与您问题完全相关的信息,您可以尝试: 换一种方式描述您的问题";
            
            return ca;
        }
        
        System.out.println("✅ 找到相关知识，正在构建上下文...\n");
        
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            SearchService.KnowledgeItem item = items.get(i);
            context.append(String.format("%d. %s (相似度: %.4f)\n", 
                i + 1, item.content, item.distance));
        }
        
        System.out.println("📚 检索到的知识:");
        System.out.println(context.toString());
        
        //buildSystem( context.toString());
        setSystemMessage	 (context.toString());
            // 发送到 OpenAI
            String answer = sendToOpenAI();
            
            // 添加助手回复
            if (answer != null && !answer.isEmpty()) {
                if (answer.length() > MAX_MESSAGE_LENGTH) {
                    answer = answer.substring(0, MAX_MESSAGE_LENGTH);
                }
              
                history.addMessage("assistant", answer);
                history.trim(MAX_HISTORY);
            }
            
            ca.code=0;
            ca.answer=answer;
            return ca;
            
        } catch (Exception e) {
            e.printStackTrace();
            ca.code=-1;
            ca.answer="机器人系统故障";
            return ca;
          //  return "System error";
        }
    }
    
    
    
    /**
     * ⚡ 优化版 sendToOpenAI()
     */
    private String sendToOpenAI() throws Exception {
        // 创建请求 JSON
        ObjectNode rootNode = MAPPER.createObjectNode();
        rootNode.put("model", "gpt-4o-mini");
        rootNode.put("max_tokens", 300);

        
        
     // 关键代码
        rootNode.set("messages", history.toJsonArray());

        // 生成 JSON
        String bodyJson = MAPPER.writeValueAsString(rootNode);
        /*
        // 创建 input 数组
        ArrayNode messagesArray = rootNode.putArray("messages");

        // ✅ 直接遍历 history，格式更简单
        for (Message msg : history) {
            ObjectNode messageNode = messagesArray.addObject();
            messageNode.put("role", msg.role);
            messageNode.put("content", msg.content); // ✅ 直接是字符串，不需要 content 数组
        }
	*/
 
        System.out.println("bodyJson:"+bodyJson);
        // 调用优化版 SessionManagerOptimized
        return SessionManager.sendToOpenAI(bodyJson);
    }

 
}
