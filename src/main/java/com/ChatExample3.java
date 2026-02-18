package com;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ChatExample3 - 使用优化版本
 * 性能提升 10 倍！
 */
public class ChatExample3 {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== ChatExample3 - 优化版本 ===\n");
        
        // ⚡ 步骤 1: 预热连接池（推荐）
        System.out.println("🔥 正在预热连接池...");
        try {
            SessionManagerOptimized.warmUp();
        } catch (Exception e) {
            System.err.println("⚠️ 预热失败: " + e.getMessage());
        }
        System.out.println();
        
        // ⚡ 步骤 2: 使用优化版本
        String clientId = "user123";
        ChatSessionOptimized session = SessionManagerOptimized.getSession(clientId);
        
        // 测试问题 1
        testQuestion(session, 
            "hello i want to know the room number for seven three five in one i t vancouver");
        
        // 测试问题 2
        testQuestion(session, 
            "Do I need to study 775?short answer!");
        
        // 测试问题 3
        testQuestion(session, 
            "I was admitted in 2024 fall.Is it a good time?");
        
        // 测试问题 4
        testQuestion(session, 
            "Tell me headquater of NYIT new york Institue technology?");
        
        // 测试问题 5
        testQuestion(session, 
            "Do I need to study 775? give me accurate answer based on conversation history.");
        
        // 显示统计信息
        System.out.println("\n=== 统计信息 ===");
        System.out.println("当前活跃会话数: " + SessionManagerOptimized.getSessionCount());
    }
    
    /**
     * 测试单个问题并计时
     */
    private static void testQuestion(ChatSessionOptimized session, String question) {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("❓ 问题: " + question);
        
        long startTime = System.currentTimeMillis();
        
        String answer = session.ask(question);
        
        long duration = System.currentTimeMillis() - startTime;
        
        System.out.println("💬 回答: " + answer);
        System.out.println("⏱️  耗时: " + duration + "ms");
        System.out.println();
    }
}
