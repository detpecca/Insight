package org.example.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemoryManagerService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManagerService.class);

    private final Path insightFile = Paths.get("INSIGHT.md").toAbsolutePath().normalize();
    private final Path memoryDir = Paths.get(".memory").toAbsolutePath().normalize();
    private final Path memoryIndexFile = memoryDir.resolve("MEMORY.md").normalize();
    private final Path reportsDir = memoryDir.resolve("reports").normalize();

    private final ReentrantReadWriteLock insightLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock memoryLock = new ReentrantReadWriteLock();

    /** INSIGHT.md 规则条数上限，超出后裁剪最旧的，防止无界增长拖垮 system prompt */
    @Value("${memory.max-insight-lines:200}")
    private int maxInsightLines;

    @PostConstruct
    public void init() {
        try {
            if (Files.notExists(insightFile)) {
                Files.createFile(insightFile);
                Files.writeString(insightFile, "# 全局强规则与环境配置\n\n");
                logger.info("创建 INSIGHT.md 文件: {}", insightFile);
            }
            if (Files.notExists(memoryDir)) {
                Files.createDirectory(memoryDir);
                logger.info("创建 .memory 目录: {}", memoryDir);
            }
            if (Files.notExists(memoryIndexFile)) {
                Files.createFile(memoryIndexFile);
                logger.info("创建 MEMORY.md 文件: {}", memoryIndexFile);
            }
            if (Files.notExists(reportsDir)) {
                Files.createDirectory(reportsDir);
                logger.info("创建 .memory/reports 目录: {}", reportsDir);
            }
        } catch (IOException e) {
            logger.error("初始化记忆目录和文件失败", e);
        }
    }

    public String readInsight() {
        insightLock.readLock().lock();
        try {
            if (Files.exists(insightFile)) {
                return Files.readString(insightFile);
            }
            return "";
        } catch (IOException e) {
            logger.error("读取 INSIGHT.md 失败", e);
            return "";
        } finally {
            insightLock.readLock().unlock();
        }
    }

    /**
     * 追加一条全局规则。
     * 安全考虑：
     * 1. 规则会被清洗为单行并限制长度（防止注入超长/带换行的控制文本）；
     * 2. 写入时带时间戳，便于审计与排查投毒；
     * 3. INSIGHT.md 有行数上限，超出时裁剪最旧的规则。
     */
    public void updateInsight(String ruleContent) {
        if (ruleContent == null || ruleContent.trim().isEmpty()) {
            logger.warn("尝试写入空规则，已忽略");
            return;
        }
        String sanitized = sanitizeRule(ruleContent);

        insightLock.writeLock().lock();
        try {
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            String entry = String.format("%n- [%s] %s%n", time, sanitized);
            Files.writeString(insightFile, entry, StandardOpenOption.APPEND);
            logger.info("已更新 INSIGHT.md，添加规则: {}", sanitized);

            pruneInsightFile();
        } catch (IOException e) {
            logger.error("更新 INSIGHT.md 失败", e);
        } finally {
            insightLock.writeLock().unlock();
        }
    }

    /**
     * 规则清洗：压缩为单行、限制长度。
     * 对外可见（用于在测试与工具返回中说明），故为 package-private/可见。
     */
    String sanitizeRule(String ruleContent) {
        String oneLine = ruleContent.replaceAll("\\s+", " ").trim();
        int maxLen = 300;
        if (oneLine.length() > maxLen) {
            oneLine = oneLine.substring(0, maxLen - 3) + "...";
        }
        return oneLine;
    }

    /**
     * INSIGHT.md 行数超限时，裁剪最旧的规则行（保留文件开头的标题等非规则行）。
     * 规则行以 "- " 开头。
     */
    private void pruneInsightFile() throws IOException {
        List<String> lines = Files.readAllLines(insightFile);
        // 统计规则行数量
        long ruleCount = lines.stream().filter(l -> l.startsWith("- ")).count();
        if (ruleCount <= maxInsightLines) {
            return;
        }
        long toRemove = ruleCount - maxInsightLines;
        java.util.Iterator<String> it = lines.iterator();
        while (it.hasNext() && toRemove > 0) {
            if (it.next().startsWith("- ")) {
                it.remove();
                toRemove--;
            }
        }
        Files.writeString(insightFile, String.join("\n", lines) + "\n");
        logger.info("INSIGHT.md 规则数超过上限 {}，已裁剪最旧的 {} 条", maxInsightLines, ruleCount - maxInsightLines);
    }

    public String readMemoryIndex() {
        memoryLock.readLock().lock();
        try {
            if (Files.exists(memoryIndexFile)) {
                List<String> allLines = Files.readAllLines(memoryIndexFile);
                if (allLines.size() <= 100) {
                    return String.join("\n", allLines);
                } else {
                    return String.join("\n", allLines.subList(allLines.size() - 100, allLines.size()));
                }
            }
            return "";
        } catch (IOException e) {
            logger.error("读取 MEMORY.md 失败", e);
            return "";
        } finally {
            memoryLock.readLock().unlock();
        }
    }

    public String readReport(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "文件名称不能为空";
        }
        Path targetFile = reportsDir.resolve(fileName).normalize();
        // 防越权访问
        if (!targetFile.startsWith(reportsDir)) {
            logger.warn("尝试越权访问报告文件: {}", fileName);
            return "越权访问被拒绝";
        }
        
        try {
            if (Files.exists(targetFile)) {
                return Files.readString(targetFile);
            } else {
                return "报告文件不存在: " + fileName;
            }
        } catch (IOException e) {
            logger.error("读取报告文件失败: {}", fileName, e);
            return "读取报告失败";
        }
    }

    public void archiveAiOpsReport(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }

        memoryLock.writeLock().lock();
        try {
            // 提取摘要（逻辑见 extractSummary，抽出便于单元测试）
            String summary = extractSummary(content);

            // 剔除内容中的 summary 标签以便存储
            String finalContent = content.replaceAll("(?s)<summary>.*?</summary>\\s*", "");

            // 生成文件名（时间戳 + 随机后缀，避免同一秒内的两次分析互相覆盖）
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = "report_" + timestamp + "_" + Integer.toHexString(RANDOM.nextInt(0x10000)) + ".md";
            Path reportFile = reportsDir.resolve(fileName).normalize();

            // 写入报告文件
            Files.writeString(reportFile, finalContent);
            logger.info("已归档 AIOps 报告: {}", reportFile);

            // 追加索引到 MEMORY.md
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            String indexLine = String.format("- [%s] [%s] : %s\n", timeStr, fileName, summary);
            Files.writeString(memoryIndexFile, indexLine, StandardOpenOption.APPEND);

            // 检查行数限制
            List<String> allLines = Files.readAllLines(memoryIndexFile);
            if (allLines.size() > 200) {
                // 删除最早的 50 行
                List<String> prunedLines = allLines.subList(50, allLines.size());
                Files.writeString(memoryIndexFile, String.join("\n", prunedLines) + "\n", StandardOpenOption.TRUNCATE_EXISTING);
                logger.info("MEMORY.md 达到 200 行，已自动删减最早的 50 行记录");
            }
        } catch (Exception e) {
            logger.error("归档 AIOps 报告失败", e);
        } finally {
            memoryLock.writeLock().unlock();
        }
    }

    private static final java.util.Random RANDOM = new java.util.Random();

    /**
     * 从报告内容中提取摘要：
     * 1. 优先取 <summary>...</summary> 标签内的文字（压成单行、限长 50）；
     * 2. 没有标签时退化为剥离全部标签后的前 50 个字符。
     * 包级可见便于单元测试。
     */
    static String extractSummary(String content) {
        String summary = "未提取到摘要";
        Matcher matcher = Pattern.compile("<summary>(.*?)</summary>", Pattern.DOTALL | Pattern.MULTILINE).matcher(content);
        if (matcher.find()) {
            summary = matcher.group(1).trim().replaceAll("\n", " ");
            if (summary.length() > 50) {
                summary = summary.substring(0, 47) + "...";
            }
        } else {
            // 如果没有找到 summary 标签，退化为截断前 50 字符
            String plain = content.replaceAll("<[^>]+>", "").trim().replaceAll("\n", " ");
            if (!plain.isEmpty()) {
                summary = plain.length() > 50 ? plain.substring(0, 47) + "..." : plain;
            }
        }
        return summary;
    }
}
