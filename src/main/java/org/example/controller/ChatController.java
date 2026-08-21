package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.example.dto.ApiResponse;
import org.example.dto.ChatRequest;
import org.example.dto.ChatResponse;
import org.example.dto.ClearRequest;
import org.example.dto.SessionInfoResponse;
import org.example.dto.SseMessage;
import org.example.exception.BusinessException;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.service.ChatSession;
import org.example.service.ChatSessionService;
import org.example.service.MemoryManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private AiOpsService aiOpsService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private MemoryManagerService memoryManagerService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private ToolCallbackProvider tools;

    /** 标准对话模型（单例，自动装配，temperature 0.7 / maxToken 2000） */
    @Autowired
    private DashScopeChatModel dashScopeChatModel;

    /**
     * SSE 任务线程池：有界队列 + 队列满时由调用线程执行（天然限流）。
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
            4, 32, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** 长任务（AIOps）期间的 SSE 心跳调度器 */
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        heartbeatExecutor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 普通对话接口（支持工具调用）
     * 直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (question.isEmpty()) {
            throw BusinessException.badRequest("问题内容不能为空");
        }

        logger.info("收到对话请求 - SessionId: {}", request.getId());

        // 获取或创建会话
        ChatSession session = chatSessionService.getOrCreate(request.getId());
        List<java.util.Map<String, String>> history = session.getHistory();
        logger.info("会话历史消息对数: {}", history.size() / 2);

        logger.info("开始 ReactAgent 对话（支持自动工具调用）");

        // 构建系统提示词（历史消息不再拼入 system prompt）
        String systemPrompt = chatService.buildSystemPrompt();
        ReactAgent agent = chatService.createReactAgent(dashScopeChatModel, systemPrompt);

        // 执行对话：历史作为真正的 User/Assistant 消息传入
        List<Message> messages = chatService.buildMessages(history, question);
        String fullAnswer;
        try {
            fullAnswer = chatService.executeChat(agent, messages);
        } catch (Exception e) {
            logger.error("对话失败 - SessionId: {}", request.getId(), e);
            throw BusinessException.internal("对话处理失败，请稍后重试");
        }

        // 更新会话历史
        session.addMessage(question, fullAnswer, ChatSessionService.MAX_WINDOW_SIZE);
        logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                request.getId(), session.getMessagePairCount());

        return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        if (request.getId() == null || request.getId().isEmpty()) {
            throw BusinessException.badRequest("会话ID不能为空");
        }
        logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

        boolean cleared = chatSessionService.clearHistory(request.getId());
        if (!cleared) {
            throw BusinessException.badRequest("会话不存在");
        }
        return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 参数校验
        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (question.isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}", request.getId());

                // 获取或创建会话
                ChatSession session = chatSessionService.getOrCreate(request.getId());
                List<java.util.Map<String, String>> history = session.getHistory();
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");

                // 构建系统提示词（历史消息不再拼入 system prompt）
                String systemPrompt = chatService.buildSystemPrompt();
                ReactAgent agent = chatService.createReactAgent(dashScopeChatModel, systemPrompt);

                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();

                // 历史作为真正的 User/Assistant 消息传入
                List<Message> messages = chatService.buildMessages(history, question);

                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(messages);

                // 客户端超时/断开时取消模型推理，避免白烧 token
                emitter.onTimeout(() -> {
                    logger.warn("SSE 超时，取消流式推理 - SessionId: {}", request.getId());
                    Disposable d = subscriptionRef.get();
                    if (d != null) {
                        d.dispose();
                    }
                });

                Disposable subscription = stream.subscribe(
                        output -> {
                            try {
                                // 检查是否为 StreamingOutput 类型
                                if (output instanceof StreamingOutput streamingOutput) {
                                    OutputType type = streamingOutput.getOutputType();

                                    // 处理模型推理的流式输出
                                    if (type == OutputType.AGENT_MODEL_STREAMING) {
                                        // 流式增量内容，逐步显示
                                        String chunk = streamingOutput.message().getText();
                                        if (chunk != null && !chunk.isEmpty()) {
                                            fullAnswerBuilder.append(chunk);

                                            // 实时发送到前端
                                            emitter.send(SseEmitter.event()
                                                    .name("message")
                                                    .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));

                                            logger.debug("发送流式内容块, 长度: {}", chunk.length());
                                        }
                                    } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                        // 模型推理完成
                                        logger.info("模型输出完成");
                                    } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                        // 工具调用完成
                                        logger.info("工具调用完成: {}", output.node());
                                    } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                        // Hook 执行完成
                                        logger.debug("Hook 执行完成: {}", output.node());
                                    }
                                }
                            } catch (IOException e) {
                                logger.error("发送流式消息失败", e);
                                throw new RuntimeException(e);
                            }
                        },
                        error -> {
                            // 错误处理
                            logger.error("ReactAgent 流式对话失败", error);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(SseMessage.error("对话处理失败，请稍后重试"), MediaType.APPLICATION_JSON));
                            } catch (IOException ex) {
                                logger.error("发送错误消息失败", ex);
                            }
                            emitter.completeWithError(error);
                        },
                        () -> {
                            // 完成处理
                            try {
                                String fullAnswer = fullAnswerBuilder.toString();
                                logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}",
                                        request.getId(), fullAnswer.length());

                                // 更新会话历史
                                session.addMessage(question, fullAnswer, ChatSessionService.MAX_WINDOW_SIZE);
                                logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                                        request.getId(), session.getMessagePairCount());

                                // 发送完成标记
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                                emitter.complete();
                            } catch (IOException e) {
                                logger.error("发送完成消息失败", e);
                                emitter.completeWithError(e);
                            }
                        }
                );
                subscriptionRef.set(subscription);

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error("对话初始化失败，请稍后重试"), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程。
     * 多 Agent 编排的每个节点完成都会推送 progress 事件，前端可实时展示分析进度。
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）

        executor.execute(() -> {
            // 多 Agent 分析期间可能长时间无输出，定期发送 SSE 注释心跳，
            // 防止代理/负载均衡器把空闲连接当死连接掐掉
            var heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (Exception e) {
                    logger.debug("心跳发送失败（客户端可能已断开）: {}", e.getMessage());
                }
            }, 15, 15, TimeUnit.SECONDS);

            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                ToolCallback[] toolCallbacks = tools.getToolCallbacks();

                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.progress("正在读取告警并拆解任务..."), MediaType.APPLICATION_JSON));

                // 流式执行多 Agent 编排：每个节点完成实时推送进度，结束后提取报告
                Flux<NodeOutput> stream = aiOpsService.streamAiOpsAnalysis(toolCallbacks);

                AtomicReference<com.alibaba.cloud.ai.graph.OverAllState> lastState = new AtomicReference<>();

                stream.doOnNext(output -> {
                            lastState.set(output.state());
                            try {
                                emitter.send(SseEmitter.event().name("message")
                                        .data(SseMessage.progress(describeProgress(output)), MediaType.APPLICATION_JSON));
                            } catch (IOException e) {
                                logger.warn("发送进度事件失败（客户端可能已断开）: {}", e.getMessage());
                            }
                        })
                        .doFinally(signal -> heartbeat.cancel(false))
                        .subscribe(
                                output -> { /* 进度已在 doOnNext 中处理 */ },
                                error -> {
                                    logger.error("AI Ops 多 Agent 协作失败", error);
                                    try {
                                        emitter.send(SseEmitter.event().name("message")
                                                .data(SseMessage.error("AI Ops 流程失败，请稍后重试"), MediaType.APPLICATION_JSON));
                                    } catch (IOException ex) {
                                        logger.error("发送错误消息失败", ex);
                                    }
                                    emitter.completeWithError(error);
                                },
                                () -> sendFinalReport(emitter, lastState.get())
                        );

            } catch (Exception e) {
                heartbeat.cancel(false);
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("AI Ops 流程失败，请稍后重试"), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 把节点输出转成一句人类可读的进度描述
     */
    private String describeProgress(NodeOutput output) {
        String node = output.node();
        if (node == null) {
            return "分析中...";
        }
        if (node.contains("planner")) {
            return "🧭 Planner 完成一轮规划";
        } else if (node.contains("executor")) {
            return "⚙️ Executor 完成一步执行";
        } else if (output.isEND()) {
            return "✅ 编排完成，正在整理报告...";
        }
        return "节点 [" + node + "] 完成";
    }

    /**
     * 编排结束后：提取并归档报告，再分块推给前端
     */
    private void sendFinalReport(SseEmitter emitter, com.alibaba.cloud.ai.graph.OverAllState state) {
        try {
            if (state == null) {
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                emitter.complete();
                return;
            }

            logger.info("AI Ops 编排完成，开始提取最终报告...");
            Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

            if (finalReportOptional.isPresent()) {
                String finalReportText = finalReportOptional.get();
                logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());

                // 将报告归档到记忆流中
                memoryManagerService.archiveAiOpsReport(finalReportText);

                // 发送分隔线
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));

                // 发送完整的告警分析报告
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));

                int chunkSize = 50;
                for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, finalReportText.length());
                    String chunk = finalReportText.substring(i, end);

                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                }

                // 发送结束分隔线
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));

                logger.info("最终报告已完整输出");
            } else {
                logger.warn("未能提取到 Planner 最终报告（输出可能不是合法报告格式）");
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成符合格式的最终报告，请重试。"), MediaType.APPLICATION_JSON));
            }

            emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
            emitter.complete();
            logger.info("AI Ops 多 Agent 编排完成");
        } catch (Exception e) {
            logger.error("发送最终报告失败", e);
            emitter.completeWithError(e);
        }
    }


    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

        Optional<ChatSession> sessionOpt = chatSessionService.get(sessionId);
        if (sessionOpt.isEmpty()) {
            throw BusinessException.badRequest("会话不存在");
        }
        ChatSession session = sessionOpt.get();
        SessionInfoResponse response = new SessionInfoResponse();
        response.setSessionId(sessionId);
        response.setMessagePairCount(session.getMessagePairCount());
        response.setCreateTime(session.getCreateTime());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
