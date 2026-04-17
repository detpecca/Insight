package org.example.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public void updateInsight(String ruleContent) {
        insightLock.writeLock().lock();
        try {
            String entry = "\n- " + ruleContent + "\n";
            Files.writeString(insightFile, entry, StandardOpenOption.APPEND);
            logger.info("已更新 INSIGHT.md，添加规则: {}", ruleContent);
        } catch (IOException e) {
            logger.error("更新 INSIGHT.md 失败", e);
        } finally {
            insightLock.writeLock().unlock();
        }
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
            // 提取摘要：从 <summary>...</summary> 中提取
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
            
            // 剔除内容中的 summary 标签以便存储
            String finalContent = content.replaceAll("(?s)<summary>.*?</summary>\\s*", "");

            // 生成文件名
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = "report_" + timestamp + ".md";
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
}
