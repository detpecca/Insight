package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆系统纯逻辑测试：摘要提取与规则清洗
 */
class MemoryManagerServiceTest {

    private final MemoryManagerService service = new MemoryManagerService();

    // ---------- extractSummary ----------

    @Test
    void summaryExtractedFromTag() {
        String report = "# 告警分析报告\n<summary>CPU 过载导致支付服务超时</summary>\n---\n正文";
        assertEquals("CPU 过载导致支付服务超时", MemoryManagerService.extractSummary(report));
    }

    @Test
    void summaryTagContentIsFlattenedToSingleLine() {
        String report = "<summary>第一行\n第二行</summary>";
        String summary = MemoryManagerService.extractSummary(report);
        assertFalse(summary.contains("\n"));
        assertTrue(summary.contains("第一行"));
    }

    @Test
    void summaryIsTruncatedAt50Chars() {
        String longSummary = "A".repeat(80);
        String report = "<summary>" + longSummary + "</summary>";
        String summary = MemoryManagerService.extractSummary(report);
        assertEquals(50, summary.length());
        assertTrue(summary.endsWith("..."));
    }

    @Test
    void missingSummaryTagFallsBackToPlainText() {
        String report = "# 告警分析报告\n\n这里是正文内容";
        String summary = MemoryManagerService.extractSummary(report);
        // 标签被剥离，正文前 50 字符
        assertTrue(summary.startsWith("# 告警分析报告"));
    }

    // ---------- sanitizeRule ----------

    @Test
    void ruleIsCompressedToSingleLine() {
        String dirty = "禁止\n重启\n宿主机";
        String clean = service.sanitizeRule(dirty);
        assertEquals("禁止 重启 宿主机", clean);
        assertFalse(clean.contains("\n"));
    }

    @Test
    void ruleIsLengthLimited() {
        String huge = "X".repeat(1000);
        String clean = service.sanitizeRule(huge);
        assertEquals(300, clean.length());
        assertTrue(clean.endsWith("..."));
    }

    @Test
    void ruleTrimsWhitespace() {
        assertEquals("abc", service.sanitizeRule("  abc  "));
    }
}
