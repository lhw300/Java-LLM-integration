package com;

 

import java.util.ArrayList;
import java.util.List;

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
