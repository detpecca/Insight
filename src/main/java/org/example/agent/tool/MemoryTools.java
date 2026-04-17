package org.example.agent.tool;

import org.example.service.MemoryManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 记忆管理工具集
 * 提供给 Agent 读取历史报告和更新全局设定的能力
 */
@Component
public class MemoryTools {

    private static final Logger logger = LoggerFactory.getLogger(MemoryTools.class);

    @Autowired
    private MemoryManagerService memoryManagerService;

    @Tool(description = "由于历史告警信息都在记忆库中。当用户提问涉及历史故障或过去的 AIOps 诊断记录时，通过此工具读取报告详情。参数为记录在记忆索引中的文件名。")
    public String read_memory_file(String fileName) {
        logger.info("【MemoryTools】Agent 正在调用工具尝试读取历史 AIOps 报告: {}", fileName);
        return memoryManagerService.readReport(fileName);
    }

    @Tool(description = "当用户明确要求记住某个配置、红线规则或长期架构信息时调用，将其永久写入全局规则库，以便在所有未来对话中生效。")
    public String update_insight(String ruleContent) {
        logger.info("【MemoryTools】Agent 正在调用工具强制写入全局准则: {}", ruleContent);
        memoryManagerService.updateInsight(ruleContent);
        return "成功将规则写入 INSIGHT 规则库。";
    }
}
