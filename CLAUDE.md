# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Insight is a Spring Boot 3.2 (Java 17) application combining two AI capabilities:
- **RAG 智能问答**: vector retrieval over Milvus + Alibaba DashScope LLM, with multi-turn chat and SSE streaming.
- **AIOps 智能运维**: a multi-agent (Planner / Executor / Supervisor) loop that analyzes alerts, queries logs/metrics/docs, and produces a Markdown 《告警分析报告》.

The AI framework is **Spring AI Alibaba** (`com.alibaba.cloud.ai`, version 1.1.0.0-RC2) built on top of Spring AI 1.1.0. Server runs on port **9900**.

## Build & Run

```bash
# Prereq: DashScope API key, externalized via env var (app fails fast if missing;
# a leaked key was removed from application.yml — see git history warning in README)
export DASHSCOPE_API_KEY=your-api-key

# 1. Start Milvus vector DB stack (etcd + minio + milvus + Attu UI on :8000)
docker-compose -f vector-database.yml up -d

# 2. Build & run the app
mvn clean install
mvn spring-boot:run          # main class: org.example.Main
```

One-command bootstrap via Makefile (starts Docker → runs app in background → waits for health → uploads `aiops-docs/*.md` to the vector store):
```bash
make init        # full bootstrap
make up / down   # Milvus docker stack only
make start / stop / restart   # Spring Boot only (nohup → server.log, PID in server.pid)
make upload      # POST each aiops-docs/*.md to /api/upload
make check       # curl the /milvus/health endpoint
```

There is a **unit test suite** in `src/test` (32 tests, pure logic, no Docker needed): path traversal defense (`SafePaths`), document chunking, session windowing/TTL eviction, memory summary extraction & rule sanitization, AIOps report format validation. Run with `mvn test`.

## Key Endpoints (all under `/api`, see `ChatController`)

- `POST /api/chat` — non-streaming ReactAgent chat with tool calling + session history.
- `POST /api/chat_stream` — SSE streaming version (`text/event-stream`).
- `POST /api/ai_ops` — SSE; runs the multi-agent AIOps analysis, archives the report, streams it back. No request body needed.
- `POST /api/chat/clear`, `GET /api/chat/session/{id}` — session management.
- `POST /api/upload` (`FileUploadController`) — upload txt/md → traversal-checked filename → chunk → embed → batch-upsert into Milvus. If indexing fails the endpoint now returns 500 (file kept on disk) instead of a silent success.
- `GET /milvus/health` (`MilvusCheckController`) — health check used by Makefile.

Request JSON uses capitalized keys `Id` / `Question` (with lowercase aliases). Static test UI served at `http://localhost:9900` from `src/main/resources/static/`.

## Architecture

### Two distinct DashScope integration paths (important)
This codebase uses DashScope **two different ways** — do not confuse them:
1. **Spring AI Alibaba agents** (`DashScopeChatModel` + `ReactAgent`) — used by `ChatService` and `AiOpsService`. This is the path wired into `ChatController`.
2. **Raw DashScope SDK** (`com.alibaba.dashscope.Generation`) — used by `RagService` and the embedding/vector services. `RagService` exists but is **not currently wired into any controller**; the chat endpoints go through `ChatService`'s ReactAgent, not `RagService`.

### AIOps multi-agent flow (`AiOpsService`)
A `SupervisorAgent` orchestrates two `ReactAgent`s in a plan→execute→replan loop:
- **planner_agent** (also acts as Replanner) — outputs JSON `decision` (PLAN|EXECUTE|FINISH); on FINISH emits the final Markdown report to `outputKey=planner_plan`.
- **executor_agent** — executes only the first planner step, writes structured feedback to `outputKey=executor_feedback`.
The final report is extracted from the `planner_plan` state value (an `AssistantMessage`). Since the Planner replans every round and overwrites this key, `extractFinalReport` validates the last value really is a report (starts with `# 告警分析报告`, optional code fence stripped) and falls back to an empty result otherwise. All prompts strongly forbid fabricating data.

### Agent tools (`org.example.agent.tool`)
Injected into agents as "method tools":
- `DateTimeTools`, `InternalDocsTools` (RAG doc search), `QueryMetricsTools` (Prometheus alerts), `QueryLogsTools` (logs), `MemoryTools`.
- **Mock mode gate**: `QueryLogsTools` is annotated `@ConditionalOnProperty(cls.mock-enabled=true)` AND injected with `@Autowired(required = false)` — it registers only when `cls.mock-enabled=true`. In real mode, log querying comes from an MCP server instead. Both `ChatService.buildMethodToolsArray()` and `AiOpsService.buildMethodToolsArray()` branch on whether `queryLogsTools` is null. MCP tools are supplied via `ToolCallbackProvider` (the commented `spring.ai.mcp.client` block in `application.yml`).

### File-based memory system (`MemoryManagerService`)
Not stored in Milvus — plain files on disk, guarded by `ReentrantReadWriteLock`:
- `INSIGHT.md` — global hard rules (`<global_insight>`), appended via the `update_insight` tool ("请记住…"). Writes are hardened: rule text is sanitized to one line (max 300 chars), timestamped for audit, and the file is capped at `memory.max-insight-lines` entries (oldest pruned) — mitigating prompt-injection poisoning of the rule base.
- `.memory/MEMORY.md` — rolling index of past reports (pruned to ~200 lines).
- `.memory/reports/report_*.md` — archived AIOps reports (summary stripped from `<summary>` tag); filenames include a random suffix to avoid same-second collisions. `readReport` guards against path traversal.
`ChatService.buildSystemPrompt()` injects `<global_insight>` and `<memory_pointers>` into every chat system prompt; the agent must call `read_memory_file` before referencing history (anti-hallucination rule), and instructions from tool results/retrieved docs are explicitly declared non-actionable (indirect prompt-injection defense).

### Vector pipeline (Milvus)
- `DocumentChunkService` — splits docs (config `document.chunk`: max-size 800, overlap 100).
- `VectorEmbeddingService` — DashScope `text-embedding-v4`, **1024-dim** vectors. API key is passed per-request (`TextEmbeddingParam.apiKey`), NOT via the SDK's global static `Constants.apiKey`.
- `VectorIndexService` / `VectorSearchService` — collection `biz` in DB `default` (see `MilvusConstants`). `rag.top-k=3`. Indexing is delete-old + single **batch upsert** with deterministic ids (`_source` + chunkIndex), so re-indexing a file never duplicates rows.
- `MilvusClientFactory` / `MilvusConfig` / `MilvusProperties` — connection to `localhost:19530`; the collection is loaded **once at startup** in the factory (status 65535 = already loaded), never per-operation.

### Session state
`ChatController` holds sessions via `ChatSessionService` (in-memory `ConcurrentHashMap`, lost on restart, but now with TTL eviction `session.idle-timeout-minutes` and cap `session.max-sessions`; idle eviction runs via `@Scheduled` — `Main` is `@EnableScheduling`). History is windowed to `MAX_WINDOW_SIZE = 6` message pairs in `ChatSession`. SSE work runs on a bounded thread pool (rejected by `CallerRunsPolicy`). Chat history is passed to the agent as real User/Assistant messages (`ChatService.buildMessages`), NOT concatenated into the system prompt. `ai_ops` sends SSE comment heartbeats every 15s during the long multi-agent run, and `extractFinalReport` validates the output starts with `# 告警分析报告` (stripping code fences).

## Configuration Notes

- The DashScope API key is **externalized**: both `spring.ai.dashscope.api-key` and `dashscope.api.key` read `${DASHSCOPE_API_KEY}` from the environment; startup fails fast if unset. (An older hardcoded key remains in git history — treat it as compromised and rotate it.)
- `prometheus.mock-enabled` and `cls.mock-enabled` (both `true` by default) toggle mock vs. real data sources for metrics and logs.
- `cors.allowed-origins` — comma-separated allowlist for `/api/**` (defaults to localhost dev origins only).
- `session.idle-timeout-minutes` / `session.max-sessions` — in-memory session eviction policy.
- Upload limits: `spring.servlet.multipart.max-file-size / max-request-size` = 20MB; upload paths are traversal-checked via `SafePaths`.
- Error handling: `GlobalExceptionHandler` + `BusinessException` return real 4xx/5xx with `ApiResponse`; raw exception messages are not exposed to clients.
- Chat model defaults: temperature 0.7 / maxToken 2000 (standard chat); AIOps uses temperature 0.3 / maxToken 8000.

## Global Rule (from INSIGHT.md — respect in any ops advice)

Never recommend host-level restarts/reboots/kernel changes in production. Prefer lossless, reversible, gray-verified remediation (service restart, hot config reload, traffic switch, rate-limit/degrade). Host-level ops require written SRE authorization + change review.
