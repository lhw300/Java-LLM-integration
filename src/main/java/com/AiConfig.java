package com;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AiConfig {

    public static String configPath=null;
    public static String configFile;

    private static final Map<String, String> configMap = new ConcurrentHashMap<>();


    /**
     * 初始化配置
     */
    public static synchronized void init(String filePath) {
        configPath = filePath;
        // ❌ 修改前：configFile = filePath + "\\config\\ai.conf";
        // ✅ 修改后：直接用正斜杠，Java 会在所有系统上自动处理
        configPath = filePath.replace("\\", "/");
        configFile = filePath + "/config/ai.conf";
        reload();
    }

    /**
     * 手动重新加载配置
     */
    public static synchronized void reload() {

        if (configFile == null) {
            throw new RuntimeException("AiConfig not initialized");
        }

        try {

            Map<String, String> newMap = new ConcurrentHashMap<>();

            BufferedReader reader = new BufferedReader(new FileReader(configFile));

            String line;

            while ((line = reader.readLine()) != null) {

                line = line.trim();

                if (line.isEmpty()) continue;

                if (line.startsWith("#")) continue;

                int idx = line.indexOf('=');

                if (idx < 0) continue;

                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();

                newMap.put(key, value);
            }

            reader.close();

            configMap.clear();
            configMap.putAll(newMap);

            System.out.println("AiConfig loaded: " + configMap.size());

        } catch (Exception e) {

            throw new RuntimeException("Load config error: " + e.getMessage(), e);
        }
    }


    private static String get(String key) {

        return configMap.get(key);
    }


    public static String getStringConfig(String key, String defaultValue) {

        String value = get(key);

        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        // 否则一律返回传入的 defaultValue（即环境变量）
        return defaultValue;
    }


    public static int getIntConfig(String key, int defaultValue) {

        String v = get(key);

        if (v == null||v.trim().isEmpty()) return defaultValue;

        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return defaultValue;
        }
    }


    public static double getDoubleConfig(String key, double defaultValue) {

        String v = get(key);

        if (v == null||v.trim().isEmpty()) return defaultValue;

        try {
            return Double.parseDouble(v);
        } catch (Exception e) {
            return defaultValue;
        }
    }


    public static boolean getBooleanConfig(String key, boolean defaultValue) {

        String v = get(key);

        if (v == null||v.trim().isEmpty()) return defaultValue;

        return v.equalsIgnoreCase("true")
                || v.equalsIgnoreCase("1")
                || v.equalsIgnoreCase("yes");
    }

}