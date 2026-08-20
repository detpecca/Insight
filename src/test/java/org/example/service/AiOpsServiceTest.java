package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AIOps 最终报告提取的格式校验测试（P1-10）
 */
class AiOpsServiceTest {

    @Test
    void validReportIsAccepted() {
        String report = "# 告警分析报告\n<summary>摘要</summary>\n---\n内容";
        assertEquals(report, AiOpsService.normalizeReport(report));
    }

    @Test
    void reportWrappedInCodeFenceIsStripped() {
        String raw = "```markdown\n# 告警分析报告\n内容\n```";
        String result = AiOpsService.normalizeReport(raw);
        assertEquals("# 告警分析报告\n内容", result);
    }

    @Test
    void surroundingWhitespaceIsTolerated() {
        String raw = "  \n # 告警分析报告\n内容 \n";
        assertEquals("# 告警分析报告\n内容", AiOpsService.normalizeReport(raw));
    }

    @Test
    void jsonDecisionOutputIsRejected() {
        String raw = "{\"decision\": \"EXECUTE\", \"step\": \"查询日志\"}";
        assertNull(AiOpsService.normalizeReport(raw));
    }

    @Test
    void wrongHeadingIsRejected() {
        assertNull(AiOpsService.normalizeReport("# 其他报告\n内容"));
        assertNull(AiOpsService.normalizeReport("## 告警分析报告\n内容"));
    }

    @Test
    void nullAndEmptyAreRejected() {
        assertNull(AiOpsService.normalizeReport(null));
        assertNull(AiOpsService.normalizeReport(""));
        assertNull(AiOpsService.normalizeReport("   "));
    }
}
