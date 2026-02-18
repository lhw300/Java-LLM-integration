package com;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
public class ChatExample2 {
    public static void main(String[] args) throws Exception {

    	 long t1=System.currentTimeMillis();
        SessionManager sessionManager = new SessionManager();

        // 假设 clientId 是用户唯一 ID
        String clientId = "user123";
        ChatSession session = sessionManager.getSession(clientId);
        String ques="hello i want to know the room number for our class seven three five in n y i t vancouver";
       
        ques="hello i want to know the room number for seven three five in one i t vancouver";
       
        System.out.println("ai question=" +ques);
       String re= session.ask(ques);
        System.out.println("ai answer=" +re);
        long t2=System.currentTimeMillis();
        System.out.println("ask cost="+(t2-t1));
        
        
        
        ques="Do I need to study 775?short answer!";
          t1=System.currentTimeMillis();
        System.out.println("ai question=" +ques);
         re= session.ask(ques);
        System.out.println("ai answer=" +re);
          t2=System.currentTimeMillis();
        System.out.println("ask cost="+(t2-t1));
        
        ques="I was admitted in 2024 fall.Is it a good time?";
        t1=System.currentTimeMillis();
      System.out.println("ai question=" +ques);
       re= session.ask(ques);
      System.out.println("ai answer=" +re);
        t2=System.currentTimeMillis();
      System.out.println("ask cost="+(t2-t1));
        
      
      ques="do i need to study 775?";
      t1=System.currentTimeMillis();
    System.out.println("ai question=" +ques);
     re= session.ask(ques);
    System.out.println("ai answer=" +re);
      t2=System.currentTimeMillis();
    System.out.println("ask cost="+(t2-t1));
        /*
        String ques="what room for 735 at NYIT vancouver?";
        System.out.println("ai question=" +ques);
        String re= session.ask(ques);
        System.out.println("ai answer=" +re);
        System.out.println("");
        
        ques="Do I need to study 775?short answer!";
        re= session.ask(ques);
        System.out.println("ai question=" +ques);
        System.out.println("ai answer=" +re);
        System.out.println("");

        ques="I was admitted in 2024 fall.Is it a good time?";
       re= session.ask(ques);
       System.out.println("ai question=" +ques);
        System.out.println("ai answer=" +re);
        System.out.println("");
        

        
        ques="Tell me headquater of NYIT new york Institue technology?";
        re= session.ask(ques);
        System.out.println("ai question=" +ques);
         System.out.println("ai answer=" +re);
         System.out.println("");
         
         ques="Do I need to study 775? short answer!";
         re= session.ask(ques);
         System.out.println("ai question=" +ques);
         System.out.println("ai answer=" +re);
         System.out.println("");
         
         */
       // ques="Do I need to study 775? give me accurate answer based on conversation history.";
        // re= session.ask(ques);
       //  System.out.println("ai answer=" +re);
         
         
    }

    
 

    
    static String escapeJson(String s) {
        // 足够用于构造 JSON 字符串
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
