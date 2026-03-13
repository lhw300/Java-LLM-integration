package com;

import okhttp3.OkHttpClient;
import org.apache.poi.ss.usermodel.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库自动化导入服务
 * 适配数据库表: enterprise_knowledge_qwen_1024
 * 支持格式:
 * - TXT: 分类 || 摘要 || 内容
 * - XLSX: A列(分类), B列(摘要), C列(内容)
 */
public class IngestionService4 {
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String DB_USER = "postgres";
    private static final String DB_PASS = "call";
    private static final OkHttpClient CLIENT = new OkHttpClient();

    public static void main(String[] args) throws Exception {

        // ==========================================
        // 🌟 配置区
        // ==========================================
        // 自动识别后缀，你可以将此处改为 .xlsx 进行测试
        String filePath = "e:\\eit\\openai\\publishknowledge.xlsx";
        String aiType = "qwen-online";
        //aiType = "hybrid"; // 修改为 hybrid
        System.out.println("🚀 启动知识库导入流水线...");

        EmbeddingClient embedClient;
        String tableName;
        if ("hybrid".equalsIgnoreCase(aiType)) {
            // ── 1. 使用本地 Ollama 进行向量化 ──────────────────────
            // 采样 nomic-embed-text (768维)
            embedClient = new OllamaClient(
                    "http://localhost:11434/v1",
                    "qwen2.5:1.5b",         // 仅占位
                    "nomic-embed-text",      // 🌟 必须匹配本地模型
                    CLIENT,
                    null                    // 本地无需 API Key
            );
            // ── 2. 目标表必须是 768 维度的表 ────────────────────────
            tableName = "enterprise_knowledge_768";

        } else
        // 阿里百炼 Qwen-Online 配置
        if ("qwen-online".equalsIgnoreCase(aiType)) {
            String aliyunApiKey = System.getenv("QWEN_API_KEY");
            String aliyunBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

            embedClient = new OllamaClient(
                    aliyunBaseUrl,
                    "qwen-plus",
                    "text-embedding-v3",  // 1024维模型
                    CLIENT,
                    aliyunApiKey
            );
            tableName = "enterprise_knowledge_qwen_1024";
        } else {
            throw new IllegalArgumentException("当前配置仅支持 qwen-online 演示");
        }

        System.out.println("🗄️ 目标数据库表: " + tableName);
        System.out.println("📂 正在解析文件: " + filePath);

        // 🌟 2. 根据后缀名选择读取办法
        List<KnowledgeEntry> entries = new ArrayList<>();
        if (filePath.toLowerCase().endsWith(".xlsx") || filePath.toLowerCase().endsWith(".xls")) {
            entries = readFromExcel(filePath);
        } else if (filePath.toLowerCase().endsWith(".txt")) {
            entries = readFromTxt(filePath);
        } else {
            System.err.println("❌ 不支持的文件格式，仅限 .txt 或 .xlsx");
            return;
        }
        //clearDatabase(tableName);
        int successCount = 0;
        for (int i = 0; i < entries.size(); i++) {
            KnowledgeEntry entry = entries.get(i);
            if ("ID".equalsIgnoreCase(entry.id)) continue;
            // 🌟 4. 截断保护与数据清洗
            String rawCategory = entry.category != null ? entry.category.trim() : "未分类";
            String rawSummary  = entry.summary != null ? entry.summary.trim() : "无摘要";
            String content     = entry.content != null ? entry.content.trim() : "";

            if (content.isEmpty()) {
                System.out.println("⚠️ 第 " + (i + 1) + " 条记录内容为空，已跳过");
                continue;
            }

            String category = rawCategory.length() > 50 ? rawCategory.substring(0, 50) : rawCategory;
            String summary  = rawSummary.length() > 255 ? rawSummary.substring(0, 255) : rawSummary;

            // 🌟 调试打印区：在 embed 之前确认数据读取是否正常
            System.out.println("--------------------------------------------------");
            System.out.println("🔍 正在处理第 " + (i + 1) + " 条数据:");
            System.out.println("   [分类]: " + category);
            System.out.println("   [摘要]: " + summary);
            System.out.println("   [内容长度]: " + content.length() + " 字");
            System.out.println("   [内容 ]: " + content );
            try {
                // ✅ 向量化：合并三个字段以增强检索语义
                // 修改 IngestionService.java 中的这一行
// 原逻辑：double[] vector = embedClient.embed(category + " " + summary + " " + content);

// 优化逻辑：通过显式的语义引导，让向量具备更强的身份属性
                // 优化后的写法：去除干扰词，强化“分类”与“内容”的绑定关系
                //turbo-plus模式
                 String semanticText = String.format("分类：【%s】。摘要：%s。内容：%s",
                        category, summary, content);


                // 修改 IngestionService.java
// 原逻辑：String.format("分类：【%s】。摘要：%s。内容：%s", category, summary, content);
// 优化逻辑：弱化分类标签，直接强调核心内容，减少噪声干扰
               // String semanticText = String.format("内容主题：%s。详细描述：%s", summary, content);

                double[] vector = embedClient.embed(semanticText);

                //double[] vector = embedClient.embed(category + " " + summary + " " + content);


                    // 🌟 调用新的 Upsert 方法
                    upsertToDatabase(tableName, entry, vector);
                    System.out.println("   ✅ ID [" + entry.id + "] 处理成功 (Insert/Update)");
                    successCount++;
                } catch (Exception e) {
                    System.err.println("   ❌ ID [" + entry.id + "] 失败: " + e.getMessage());
                }
        }

        System.out.println("✨ 导入完成！共成功处理 " + successCount + " 条知识。");

        CLIENT.dispatcher().executorService().shutdown();
        CLIENT.connectionPool().evictAll();
    }

    /**
     * XLSX 导入：按列读取 (A:分类, B:摘要, C:内容)
     */
    /**
     * XLSX 导入：增加深度调试逻辑，排查回车符丢失问题
     */
    private static List<KnowledgeEntry> readFromExcel(String filePath) throws Exception {
        List<KnowledgeEntry> list = new ArrayList<>();
        try (InputStream is = Files.newInputStream(Paths.get(filePath));
             Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            // 创建公式计算器，防止公式产生的换行符丢失
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                // 1. 原始对齐列：A:ID, B:分类, C:摘要, D:内容
                String id  = getCellValue(row.getCell(0)).trim();
                String cat = getCellValue(row.getCell(1)).trim();
                String sum = getCellValue(row.getCell(2)).trim();
                String rawContent = getCellValue(row.getCell(3)); // 保持原始读取，不 trim
                rawContent=cleanField(rawContent);
                id=cleanField(id);
                cat=cleanField(cat);
                sum=cleanField(sum);

                String finalContent = "";
                if (rawContent != null && !rawContent.isEmpty()) {
                    // 第一步：将所有形式的回车/换行直接替换为一个空格
                    // 这样 ID 4 中的“注意：...”会紧跟在上一句后面，不会断开
                    finalContent = rawContent.replace("\r\n", " ").replace("\r", " ").replace("\n", " ");

                    // 第二步：剔除其他可能干扰 SQL 的不可见控制字符
                    finalContent = finalContent.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "");

                    // 第三步：处理连续的多个空格（可选，让内容更美观）
                    finalContent = finalContent.replaceAll("\\s+", " ");

                    finalContent = finalContent.trim();
                }

// 4. 入队：此时 list 里的 content 已经没有任何换行符了
                if (!id.isEmpty() && !finalContent.isEmpty()) {
                    list.add(new KnowledgeEntry(id, cat, sum, rawContent));
                }


            }
        }
        return list;
    }
    /**
     * 🧹 字段深度清洗工具
     * 作用：去除回车换行、剔除不可见控制字符、压缩多余空格
     */
    private static String cleanField(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }

        // 1. 将所有形式的回车/换行直接替换为一个空格
        String cleaned = raw.replace("\r\n", " ")
                .replace("\r", " ")
                .replace("\n", " ");

        // 2. 剔除其他可能干扰 SQL 的不可见控制字符 (0x00-0x1F)
        cleaned = cleaned.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "");

        // 3. 处理连续的多个空格，让内容更美观
        cleaned = cleaned.replaceAll("\\s+", " ");

        return cleaned.trim();
    }
    /**
     * TXT 导入：原有逻辑
     */
    private static List<KnowledgeEntry> readFromTxt(String filePath) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
        List<KnowledgeEntry> list = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            String[] parts = trimmed.split("\\|\\|", 3);
            if (parts.length >= 3) {
                list.add(new KnowledgeEntry(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim())  );
            }
        }
        return list;
    }

    private static String getCellValue(Cell cell) {
        if (cell == null) return "";

        // 强制使用 DataFormatter 可以更智能地获取显示文本，但手动处理类型更精准
        switch (cell.getCellType()) {
            case STRING:
                // 直接返回，保留原始换行符
                return cell.getStringCellValue();

            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double value = cell.getNumericCellValue();
                if (value == (long) value) {
                    return String.valueOf((long) value);
                } else {
                    return String.valueOf(value);
                }

            case FORMULA:
                // 🌟 核心改进：处理公式计算后的结果类型
                CellValue cellValue = cell.getSheet().getWorkbook()
                        .getCreationHelper().createFormulaEvaluator()
                        .evaluate(cell);
                if (cellValue == null) return "";
                switch (cellValue.getCellType()) {
                    case STRING: return cellValue.getStringValue();
                    case NUMERIC:
                        double val = cellValue.getNumberValue();
                        return (val == (long) val) ? String.valueOf((long) val) : String.valueOf(val);
                    default: return cell.getCellFormula();
                }

            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "";
        }
    }
    /**
     * 🌟 核心修改：利用 PostgreSQL 的 ON CONFLICT 实现 Upsert
     /**
     * 🌟 执行 Upsert：存在则更新内容和向量，不存在则插入
     */
    private static void upsertToDatabase(String tableName, KnowledgeEntry entry, double[] vector) throws Exception {
        // SQL 逻辑：
        // 1. 尝试插入所有字段
        // 2. 如果 id 冲突 (ON CONFLICT)，则执行 UPDATE
        // 3. EXCLUDED 指代原本要插入但冲突了的那行数据
        String sql = "INSERT INTO " + tableName + " (" +
                "id, category, summary, content, source_name, is_active, create_time, embedding" +
                ") VALUES (?, ?, ?, ?, 'System_Import', true, CURRENT_TIMESTAMP, ?::vector) " +
                "ON CONFLICT (id) DO UPDATE SET " +
                "category = EXCLUDED.category, " +
                "summary = EXCLUDED.summary, " +
                "content = EXCLUDED.content, " +
                "embedding = EXCLUDED.embedding, " +
                "create_time = CURRENT_TIMESTAMP";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // 参数填充顺序：
            pstmt.setString(1, entry.id);       // id
            pstmt.setString(2, entry.category); // category
            pstmt.setString(3, entry.summary);  // summary
            pstmt.setString(4, entry.content);  // content

            // 处理向量：将 double[] 转换为 pgvector 字符串格式 "[v1,v2...]"
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                sb.append(vector[i]).append(i == vector.length - 1 ? "" : ",");
            }
            sb.append("]");
            pstmt.setObject(5, sb.toString());  // embedding

            pstmt.executeUpdate();
        }
    }
    /**
     * 执行 SQL 插入
     */
    private static void saveToDatabase(String tableName, String category, String summary, String content, double[] vector) throws Exception {
        String sql = "INSERT INTO " + tableName + " (category, summary, content, embedding, is_active, create_time, source_name) " +
                "VALUES (?, ?, ?, ?::vector, true, CURRENT_TIMESTAMP, 'System_Import')";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, category);
            pstmt.setString(2, summary);
            pstmt.setString(3, content);

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                sb.append(vector[i]).append(i == vector.length - 1 ? "" : ",");
            }
            sb.append("]");

            pstmt.setObject(4, sb.toString());
            pstmt.executeUpdate();
        }
    }
    /**
     * 🧹 自动化清理逻辑
     * 在导入新数据前，彻底物理清空旧数据
     */
    private static void clearDatabase(String tableName) throws Exception {
        String sql = "TRUNCATE TABLE " + tableName + " RESTART IDENTITY";

        // 🌟 修改点：直接使用 DriverManager 获取连接，不再引用不存在的 dataSource
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             java.sql.Statement stmt = conn.createStatement()) {

            System.out.println("⚠️ 正在全量清理数据库表: " + tableName + "...");
            stmt.execute(sql);
            System.out.println("✅ 清理完成。");
        } catch (Exception e) {
            System.err.println("❌ 清理失败: " + e.getMessage());
            throw e;
        }
    }
    /**
     * 数据载体类
     */
    static class KnowledgeEntry {
        String id,category, summary, content;
        KnowledgeEntry(String id, String c, String s, String ct) {
            this.id = id;
            this.category = c;
            this.summary = s;
            this.content = ct;
        }
    }
}