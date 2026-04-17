package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册，所以设置为 optional,真实环境通过mcp配置注入
    private QueryLogsTools queryLogsTools;

    @Autowired
    private ToolCallbackProvider tools;

    @Autowired
    private MemoryManagerService memoryManagerService;

    @Autowired
    private org.example.agent.tool.MemoryTools memoryTools;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    /**
     * 创建 ChatModel
     *
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken    最大输出长度
     * @param topP        核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（包含历史消息）
     *
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        StringBuilder systemPromptBuilder = new StringBuilder();

        // 1. 获取动态记忆数据
        String insightContent = memoryManagerService.readInsight();
        String memoryPointers = memoryManagerService.readMemoryIndex();

        // 2. 拼接 XML 结构
        systemPromptBuilder.append("<system_role>\n")
                .append("你是一个熟悉当前系统环境的高级运维专家 Agent。你的职责是回答用户提问、排障，并遵守系统架构规范。\n")
                .append("你可以访问历史报告，并在必要时更新全局准则。你还可以获取当前时间、搜索内部文档知识库以及查询 Prometheus 告警信息。\n")
                .append("</system_role>\n\n");

        systemPromptBuilder.append("<global_insight>\n")
                .append(insightContent == null || insightContent.isEmpty() ? "暂无全局准则" : insightContent).append("\n")
                .append("</global_insight>\n\n");

        systemPromptBuilder.append("<memory_pointers>\n")
                .append(memoryPointers == null || memoryPointers.isEmpty() ? "暂无历史记忆索引" : memoryPointers).append("\n")
                .append("</memory_pointers>\n\n");

        systemPromptBuilder.append("<instructions>\n")
                .append("1. 【防幻觉】: <memory_pointers> 只是索引！如果用户问到相关历史事件，你必须先调用 `read_memory_file` 工具读取详情，绝对禁止盲猜。\n")
                .append("2. 【规则更新】: 如果用户要求“请记住...”、“把这个设为规则”，你必须提取核心内容并调用 `update_insight` 工具。\n")
                .append("3. 【遵守规则】: 任何建议必须严格遵守 <global_insight> 中的红线。\n")
                .append("4. 【其他工具】: 查询时间请用 getCurrentDateTime；查内部文档用 queryInternalDocs；查监控用 queryPrometheusAlerts。\n")
                .append("</instructions>\n\n");

        // 添加历史消息
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }

        systemPromptBuilder.append("请基于以上背景和对话历史，回答用户的新问题。");

        return systemPromptBuilder.toString();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    // 返回的是“方法工具”数组，也就是本地 Java 类里那些用注解声明过的方法工具
    public Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, memoryTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, memoryTools};
        }
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    // ToolCallbackProvider 拿到外部注册的工具回调，再把这些回调交给 Agent 使用
    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     *
     * @param chatModel    聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     *
     * @param agent    ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }
}
