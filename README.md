# Insight

> 基于 Spring Boot + AI Agent 的智能问答与运维系统

## 📖 项目简介

企业级智能业务代理系统，包含两大核心模块：

### 1. RAG 智能问答
集成 Milvus 向量数据库和阿里云 DashScope，提供基于检索增强生成的智能问答能力，支持多轮对话和流式输出。

### 2. AIOps 智能运维
基于 AI Agent 的自动化运维系统，采用 Planner-Executor-Replanner 架构，实现告警分析、日志查询、智能诊断和报告生成。

## 🚀 核心特性

- ✅ **RAG 问答**: 向量检索 + 多轮对话 + 流式输出（统一走 Spring AI Alibaba 单路径）
- ✅ **AIOps 运维**: 智能诊断 + 多 Agent 协作 + 自动报告（实时进度事件）
- ✅ **工具集成**: 文档检索、告警查询、日志分析、时间工具
- ✅ **会话管理**: 上下文维护、历史管理、自动清理
- ✅ **Web 界面**: 提供测试界面和 RESTful API


## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI / Spring AI Alibaba | 1.1.0 / 1.1.0.0-RC2 | AI Agent 框架（聊天+向量化统一入口） |
| Milvus SDK | 2.6.10 | 向量数据库 |

## 📦 核心模块

```
Insight/
├── src/main/java/org/example/
│   ├── controller/
│   │   ├── ChatController.java        # 统一接口控制器 ⭐
│   │   ├── FileUploadController.java  # 文件上传（路径穿越防护）
│   │   └── MilvusCheckController.java # Milvus 健康检查
│   ├── service/
│   │   ├── ChatService.java           # 对话服务 ⭐
│   │   ├── AiOpsService.java          # AIOps 服务（流式编排）⭐
│   │   ├── ChatSession(Service).java  # 会话管理（TTL 淘汰 + 上限）
│   │   ├── MemoryManagerService.java  # 文件记忆系统（规则加固）
│   │   └── Vector*.java               # 向量服务（批量 upsert + 距离阈值过滤）
│   ├── agent/tool/                    # Agent 工具集
│   │   ├── DateTimeTools.java         # 时间工具
│   │   ├── InternalDocsTools.java     # 文档检索
│   │   ├── QueryMetricsTools.java     # 告警查询
│   │   ├── QueryLogsTools.java        # 日志查询（仅 mock 模式注册）
│   │   └── MemoryTools.java           # 记忆读写
│   ├── dto/                           # ApiResponse 等传输对象
│   ├── exception/                     # 全局异常处理
│   ├── util/                          # SafePaths（路径安全）
│   └── config/                        # 配置类
├── src/test/java/                     # 32 个单元测试（无需 Docker）
├── src/main/resources/
│   ├── static/                        # Web 界面
│   └── application.yml                # 应用配置
└── aiops-docs/                        # 运维文档库
```


## 📡 核心接口

### 1. 智能问答接口

**流式对话（推荐）**
```bash
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
支持 SSE 流式输出、自动工具调用、多轮对话。

**普通对话**
```bash
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
一次性返回完整结果，支持工具调用和多轮对话。

### 2. AIOps 智能运维接口

```bash
POST /api/ai_ops
```
自动执行告警分析流程，生成运维报告（SSE 流式输出）。

### 3. 会话管理

- `POST /api/chat/clear` - 清空会话历史
- `GET /api/chat/session/{sessionId}` - 获取会话信息

### 4. 文件管理

- `POST /api/upload` - 上传文件并自动向量化
- `GET /milvus/health` - Milvus 健康检查

### 5. 观测（Actuator）

- `GET /actuator/health` - 标准健康检查（K8s 探针 / 监控可直接使用）
- `GET /actuator/metrics` - JVM / HTTP 指标列表


## ⚙️ 核心配置

### application.yml

```yaml
server:
  port: 9900

# Milvus 向量数据库
milvus:
  host: localhost
  port: 19530

# 阿里云 DashScope（从环境变量读取，勿在仓库中硬编码）
spring:
  ai:
    dashscope:
      api-key: "${DASHSCOPE_API_KEY}"
      embedding:
        model: text-embedding-v4

# CORS 允许来源（默认仅本机开发）
cors:
  allowed-origins: http://localhost:9900,http://127.0.0.1:9900

# 会话生存策略（内存会话）
session:
  idle-timeout-minutes: 30
  max-sessions: 1000

# 记忆系统
memory:
  max-insight-lines: 200

# RAG 配置
rag:
  top-k: 3
  max-l2-distance: 0   # >0 时按 L2 距离过滤，防无关文档诱导幻觉

# 文档分片
document:
  chunk:
    max-size: 800
    overlap: 100
```

### 环境变量

```bash
export DASHSCOPE_API_KEY=your-api-key
```

> ⚠️ **安全提醒**：API Key 曾泄露到 git 历史中，若此仓库曾推送远端，请务必先在 DashScope 控制台吊销旧 Key 并重建。


## 🚀 快速开始

### 1. 环境准备

```bash
# 设置 API Key
export DASHSCOPE_API_KEY=your-api-key
```

### 2. 启动应用

方法一： 手动启动
```bash
1.先启动向量数据库
docker compose up -d -f vector-database.yml

2.启动服务
mvn clean install
mvn spring-boot:run
```

方法二：一键启动
```bash
make init  # 会自动启动向量数据库并上传运维文档到向量库
```


### 3. 使用示例

**Web 界面**
```
http://localhost:9900
```

**命令行**
```bash
# 上传文档
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.txt"

# 智能问答
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"什么是向量数据库？"}'

# 健康检查
curl http://localhost:9900/milvus/health
```

### 4. 运行测试

```bash
mvn test   # 37 个单元测试：路径穿越防护、分片、会话窗口、记忆系统、报告校验、距离阈值过滤
```

## 📝 运行时产物

以下文件由应用运行时生成，**已被 git 忽略**，不会进入版本库：

- `INSIGHT.md` — 全局规则库（由 Agent 的 `update_insight` 工具写入）
- `.memory/` — AIOps 报告归档与索引
- `logs/` — 滚动日志（50MB/文件，保留 30 天，见 `logback-spring.xml`）
- `uploads/` — 上传的原始文档
