package com;


import java.util.HashMap;
import java.util.Map;

public class SessionManager {

    private final Map<String, ChatSession> sessions = new HashMap<>();
    private final String defaultSystemMessage;

    public SessionManager(String defaultSystemMessage) {
        this.defaultSystemMessage = defaultSystemMessage;
    }

    // 获取或创建一个客户会话
    public ChatSession getSession(String clientId) {
        return sessions.computeIfAbsent(clientId,
                id -> new ChatSession(defaultSystemMessage));
    }

    // 可选：清理一个客户会话
    public void removeSession(String clientId) {
        sessions.remove(clientId);
    }
}
