# 🌟 智能星知识助手

> **Star Knowledge Assistant** — 一个用 LangChain4j 把 Agent 整条链路走通的工程样板。
> RAG 检索 · 多轮记忆 · 工具调用 · 状态落库 · SSE 流式，**五件事一件不落**。

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.5-6DB33F?logo=springboot&logoColor=white)
![LangChain4j](https://img.shields.io/badge/LangChain4j-1.20.0-blue)
![Milvus](https://img.shields.io/badge/Milvus-2.5.27-00A1EA)
![DeepSeek](https://img.shields.io/badge/LLM-DeepSeek--V4.1--Flash-4B6BFB)
![Vue](https://img.shields.io/badge/Frontend-Vue%203-42B883?logo=vuedotjs&logoColor=white)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen)

一句话：**一个能对话、能查自己的知识库、能调后端工具干活、敢做危险操作前先问人、崩溃重启也不丢审计链的 Agent。**

---

## 🤔 它和「套壳调大模型 API」有什么不一样？

大部分「AI 知识库」项目的做法是：文档塞进向量库 → 拼一段 prompt → 调一次大模型 → 返回。
这个项目想做的是**工程上真正难的那部分**——让 Agent 的每一次运行都**可持久化、可审计、可恢复**。

| 用户感受到的 | 背后真正发生的事 |
|---|---|
| 能追问「那第二条呢？」 | 每轮从自己的 `conversation_message` 表重建 prompt，并在 token 预算内裁剪 |
| 回答带着自己上传资料的依据 | 文档 → 解析切片 → **本地 ONNX 向量化** → 向量 + BM25 混合召回 → RRF 融合 → 重排 |
| 问「现在几点」它真去查了 | `@Tool` 把后端能力暴露给模型，模型在 ReAct 循环里自己决定**调不调、调几次、何时停** |
| 点「停止」就真停，回来还能接着干 | 运行状态从进程内存搬进 `agent_run` / `agent_step` / `agent_event` 三张表，崩溃重启审计链仍在 |
| 字是一个个蹦出来的 | SSE 事件流；里程碑事件落库并自带事件 `id`，为断线重连铺路 |

---

## ✨ 核心能力

### 🧠 对话与记忆
- 多轮会话、历史分页、**截断重发**（回退到某条消息重新问）
- 记忆重建：每轮 LLM 调用前，从库里取历史并按 **token 预算**裁剪，系统提示词常驻、不拆散工具消息对

### 📚 RAG 知识库
- 知识库 / 分类 / 文档的完整生命周期：**建库 → 上传 → 解析 → 切片 → 索引 → 检索 → 重建**
- 支持 PDF / Word / Markdown / TXT / HTML 网页
- **中文优先**：本地大模型 + 中文 bigram 分词，向量与 BM25 双路召回，RRF 融合，可选本地重排
- **增量索引**：切片级 diff，只重建改动的那篇文档，不重算全库

### 🛠️ 工具调用（Agent）
- `@Tool` 注解式工具注册，启动即校验契约；工具带**副作用分级**元数据
- **ReAct 主循环** `LLM → 工具 → LLM …`，带 `maxRounds` 护栏与结果截断
- 工具调用过程通过 SSE 实时可见：`tool_call` / `tool_result`
- **危险操作先问人（M10）**：`createTicket` 这类副作用工具会挂到 `WAITING_APPROVAL`，
  前端确认后才执行；幂等键 + 唯一索引保证「同意多少次都只执行一次」，结果未知时**绝不自动重跑**

### 💾 Agent 状态落库
- 每次运行 = 一条 `agent_run`，每轮 LLM / 每次工具 = 一条 `agent_step`，里程碑事件 = `agent_event`
- **「先落库再执行」**：副作用未知时，重启后能知道「这一步本来打算做什么」
- 启动扫描非终态运行 → 标记为 `SUSPENDED(SERVER_RESTART)`，幂等恢复的基础

### 📊 可观测性（M12）
- 每次 LLM 调用一行 `ai_call_log`：provider / 模型 / token / 首 token 延迟 / 总耗时 / 成本
- 每次工具真实执行或结果重放一行 `ai_tool_log`：参数、结果、耗时、`replayed`、幂等键
- `GET /tools/logs` 查明细，`GET /tools/logs/summary` 看 token、成本、失败与重放次数
- **写日志失败不影响对话**；价格未配置时成本写 `NULL`，不伪装成 0

### 📏 离线评测（M1.5）
- **94 篇语料 + 30 条带标注查询 + 5 个变体**，把「检索有没有变好」变成可复现的数字
- 指标：Hit@K / MRR / Recall / Precision / Diversity / 空结果率 / P50·P95
- 变体对比：全开 / 关混合 / 关多样性限制 / 关 rerank / topK=10
- 未命中自动分类为「**不在库**（补数据）」或「**检索漏掉**（调检索）」
- 两种触发：opt-in `RagEvalIT` 或 dev-only `POST /rag/eval`；报告落 `docs/rag-eval/reports/`
- 当前基线：全开 **Hit@5 86.7% / MRR 0.598 / Recall 68.9%**（详见 [knowledge/60](knowledge/60-离线评测.md)）

### ⚡ 流式输出（SSE）
- `StreamingChatModel` → 事件表 → SSE，打字机效果
- `run_start` / `message_delta` / `tool_call` / `tool_result` / `interrupt` / `run_end` / `error` 一套协议
- **能停、能续、能重连**：停止后挂起，继续时从断点重放已完成的完整轮；断线重连带 `lastEventId` 补发事件
- **心跳与封存（M13）**：约每 15 秒一个 `ping` 心跳；`SUSPENDED` 超过 24h 自动封存为 `CANCELLED`，
  半截回答落成 `interrupted=true` 的助手消息
- 建流失败优雅降级为 JSON 信封 40100 / 40000 / 40400

### 🎨 演示前端
- 配套前端为独立项目：**Vue 3 + Vite + TypeScript + Pinia + Tailwind**，聊天区全自写组件
- 本仓库的 [`frontend/`](frontend/00-索引与阅读顺序.md) 是**前后端接口契约文档**，拿到即可开发

---

## 🏗️ 架构分层

```
接入层   controller     user / conversation / chat / kb(知识库) / tools / rag-eval(dev-only)
                            │  DTO / VO，统一 BaseResponse 信封
编排层   agent          AgentRunner  ReAct 主循环（LLM → 工具 → LLM …）
                        AgentToolRegistry  工具注册与契约校验
                        RunRecoveryScanner 启动扫描 + 僵尸运行恢复
                        MemoryBuilder  从 conversation_message 重建 prompt
                        AgentEventSink  事件落库 + 推给 SSE
                            │
能力层   检索  RetrievalService   向量召回 + BM25 + RRF + rerank
        索引  KbIndexService      解析 → 切片 → 向量化 → 入 Milvus
        工具  *Tool               查时间、查知识库 …（可扩展）
        评测  RagEvalService      语料导入 + 变体检索 + 指标报告
                            │
模型层   对话模型   ChatModel       DeepSeek-V4.1-Flash（远端，流式）
        向量模型   EmbeddingModel  本地 ONNX bge-small-zh-v15（512 维）
        重排模型   ScoringModel    本地 ONNX bge-reranker（可选）
                            │
存储层   MySQL    业务数据 + 会话消息 + Agent 状态（权威源）
        Milvus    向量索引（派生数据，可重建）
        Redis     登录态 + 验证码
        文件系统   上传的原始文档 + 本地模型
```

**一条贯穿始终的设计原则：MySQL 是权威源，Milvus 是派生索引。**
向量库只擅长「按相似度找」，不擅长列表、统计、改状态、事务；让它当主存，就问不出「这个文档解析到哪一步了」。

---

## 🧩 本项目最重要的一条立场：LangChain4j 用到哪，哪里自研

**把 LangChain4j 当「厂商适配层 + 数据模型」，不当「架构骨架」。**

| 能力 | 谁做 | 为什么 |
|---|---|---|
| `ChatMessage` 消息模型、`@Tool` 的 JSON Schema 生成、`AiServices` 的 POJO 绑定、`MilvusEmbeddingStore`、`OnnxEmbeddingModel`、`StreamingChatModel` / `TokenStream` | **LangChain4j** | 协议转换、schema 生成、厂商适配——它做得又好又无争议，自己写没有收益 |
| 消息持久化、记忆窗口裁剪、Agent 状态机、工具幂等、SSE 事件落库、混合检索融合、rerank 编排、增量索引 | **自研** | 这些和自有业务数据强耦合。框架的抽象（`ChatMemoryStore` / `RetrievalAugmentor`）会逼你把业务数据塞进它的模型，而我们自己的消息**本来就是业务数据**（要展示、审计、分页、支持截断重发），再存一份到框架 store 里就是双写 + 一致性问题 |

> 举例：官方 `MessageWindowChatMemory` 能帮你「只保留最近 N 条消息」，听起来正好是我们要的。但它把消息存在自己的 `ChatMemoryStore` 里，而我们的消息存在 `conversation_message` 表里——两边都存，就会出现「用户删了消息但记忆里还在」。所以正确的做法是**借它的策略思想，实现完全自研**，直接读自己的表。

---

## 🚀 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 框架 | **Java 21** · Spring Boot **3.5.5** |
| 持久化 | MySQL 8 · MyBatis-Plus 3.5.13 |
| 缓存 / 登录态 | Redis（验证码 + Spring Session） |
| 接口文档 | knife4j 4.4.0（springdoc 2.8.17） |
| 向量库 | **Milvus 2.5.27**（自带 docker-compose，独立栈） |
| AI 框架 | **LangChain4j `1.20.0`**（集成模块 `1.20.0-beta30`） |
| 对话模型 | **DeepSeek-V4.1-Flash**（DeepSeek 官方 API 直连，OpenAI 兼容，流式） |
| 向量 / 重排模型 | **本地 ONNX**：bge-small-zh-v15（512 维）/ bge-reranker |
| 前端 | Vue 3 · Vite · TypeScript · Pinia · Tailwind CSS |

**为什么对话模型用远端、向量模型用本地？**

| | embedding / rerank | 对话模型 |
|---|---|---|
| 频次 / 成本 | 每次检索都调，入库一次就是一笔钱 | 每轮 1~3 次，成本可控 |
| 效果差距 | 小模型和大模型对**检索**的差距有限，可用 rerank 补救 | 开源小模型和 DeepSeek 这类闭源大模型在**指令遵循、工具调用**上差距明显 |
| 结论 | **本地部署**（零调用成本、零网络延迟） | **远端 API**（效果优先） |

---

## 🏁 快速开始

### 0. 准备

- **JDK 21**（本机若默认是 11，运行时要显式指定 `JAVA_HOME`）
- **MySQL 8** · **Redis**
- **Docker**（用于 Milvus）

### 1. 起 Milvus（向量库）

```bash
docker compose up -d     # etcd + minio + milvus v2.5.27，数据落在 ./volumes/
```

### 2. 建表

```bash
mysql -uroot -p < sql/create_table.sql          # 建库 star_assistant + user 表
mysql -uroot -p star_assistant < sql/knowledge_tables.sql      # 知识库 4 张表
mysql -uroot -p star_assistant < sql/conversation_tables.sql   # 会话 2 张表
mysql -uroot -p star_assistant < sql/agent_tables.sql          # Agent 状态 3 张表
```

### 3. 配置密钥

- `src/main/resources/application.yml` 里填写 `spring.mail.*`（换成自己的 SMTP 授权码）。
  ⚠️ **这段不能删**——缺了 `JavaMailSender` bean 不会创建，应用启动直接失败；没配 SMTP 时发验证码只会降级打日志。
- 对话模型 key 走**环境变量**，不写进版本库：

  ```bash
  export DEEPSEEK_API_KEY=sk-xxxx
  ```

  > 没配 key 也能启动——`ChatModelConfig` 走条件装配跳过，只是没有对话能力。

### 4. 启动

```bash
# 本机默认 JDK 是 11 编译不过，要指定 21
JAVA_HOME=/e/Java/jdk-21.0.5 mvn spring-boot:run
```

| | |
|---|---|
| 服务端口 | `8102` |
| context-path | `/api` |
| 接口文档 | http://localhost:8102/api/doc.html |

---

## 📡 接口总览

| 模块 | 代表接口 |
|---|---|
| **用户** | `POST /api/user/register` · `login` · `logout` · `GET /api/user/current` · `POST /api/user/updatePassword` |
| **对话** | `POST /api/chat/message`（非流式）· `POST /api/chat/stream`（SSE 流式） |
| **会话** | `POST /api/conversation/create` · `GET /api/conversation/list` · `GET /api/conversation/{id}/messages` · `POST /api/conversation/message/delete` |
| **知识库** | `POST /api/kb/create` · `POST /api/kb/{kbId}/doc/upload` · `doc/url` · `POST /api/kb/{id}/rebuild` · `POST /api/kb/{id}/search` |
| **工具** | `GET /api/tools/list`（能力清单） · `GET /api/tools/logs`（调用记录） · `GET /api/tools/logs/summary`（token/成本汇总） |
| **Agent 运行** | `POST /api/agent/run/{id}/interrupt` · `resume` · `approve`（人工确认） · `GET /api/agent/run/{id}` · `GET /api/agent/run/{id}/stream` |
| **离线评测（dev-only）** | `POST /api/rag/eval` · `GET /api/rag/eval/status` · `GET /api/rag/eval/report/latest` · `POST /api/rag/eval/corpus/rebuild` |

> 完整契约见 [`frontend/`](frontend/00-索引与阅读顺序.md)。

---

## 🗺️ 路线图

| 阶段 | 里程碑 | 目标 | 状态 |
|---|---|---|---|
| **Phase 1** | — | 骨架 + 用户模块，端到端跑通 | ✅ |
| **Phase 2** | M0–M3 + M1.5 | RAG 从远端搬到本地，评测体系先立起来 | ✅（M1.5 于 2026-10-24 补齐） |
| **Phase 3** | M4–M6 | 会话、记忆、流式——「能用起来」 | ✅ |
| **Phase 4** | M7–M10 | 工具调用 + 中断续跑——「像 Agent 了」 | ✅ M7 / M8 / M9 / M10 全部完成 |
| **Phase 5** | M11–M13 | 知识库管理、可观测性、前端联调——「能交付」 | ✅ M11 / M12 / M13 全部完成（M13 于 2026-10-22） |

**已落地**：RAG 全链路（混合检索 + RRF + 增量索引）、会话与消息、对话记忆、SSE 流式、工具调用、
Agent 状态落库与启动恢复、中断/续跑/断线重连、副作用工具人工确认与幂等执行、
LLM/工具调用日志与 token/成本汇总、**离线检索评测基线（M1.5）**。

**待做**：可观测性的日志归档/分区与脱敏；`agent_todo` / `todo_update` 计划功能（可选、未排期）；
检索的 `rrf-k` 与切片参数标定（召回数 / `minScore` 已于 2026-10-27 完成）。

---

## 📖 文档体系：「文档带动实现」

本项目每个模块**先写清楚「治哪类病 → 官方怎么做 → 本项目怎么落地 → 数据结构 → 边界与坑」，再照着文档写代码**。

| 目录 | 放什么 | 谁看 |
|---|---|---|
| [`knowledge/`](knowledge/00-索引与阅读顺序.md) | 每个模块的**内部流程、设计说明、踩过的坑** | 后端 |
| [`frontend/`](frontend/00-索引与阅读顺序.md) | 与前端交互的**接口契约**，拿到即可开发 | 前端 |

**第一次接触请从 [`knowledge/01-总体架构与路线图.md`](knowledge/01-总体架构与路线图.md) 读起。**

几条值得一读的「坑」记录：
- [`21-本地Embedding与Rerank`](knowledge/21-本地Embedding与Rerank.md) — ONNX 原生库版本坑（报错信息里完全没有版本线索）
- [`31-流式输出SSE`](knowledge/31-流式输出SSE.md) — 建流失败为什么回的是 JSON，以及后端 406 陷阱
- [`12-中断与恢复的状态机`](knowledge/12-中断与恢复的状态机.md) — 为什么自研而不用官方 `langchain4j-agentic`

---

## 📂 目录结构

```
star-knowledge-assistant/
├── src/main/java/com/xiaohua/star/
│   ├── agent/          ReAct 主循环、工具注册、事件落库、启动恢复
│   ├── ai/             向量/BM25/RRF/rerank/切片/增量索引等检索内核
│   ├── controller/     user / conversation / chat / kb / tools
│   ├── service/        业务服务与实现
│   ├── model/          entity / dto / vo
│   └── config/         模型装配、线程池、Milvus、各类参数属性
├── sql/                4 份 DDL（user / knowledge / conversation / agent）
├── knowledge/          后端设计文档（文档带动实现）
├── frontend/           前后端接口契约
├── docs/rag-eval/      评测语料 + 评估集（报告目录已 gitignore）
├── docker-compose.yml  Milvus 2.5.27（etcd + minio + milvus）
└── models/             本地重排模型（体积大，已 gitignore）
```

---

## 🧪 测试

```bash
# 不依赖外部环境的测试（推荐，本地平时跑这个）
JAVA_HOME=/e/Java/jdk-21.0.5 mvn test -Dtest=LocalEmbeddingModelTest -DfailIfNoSpecifiedTests=false

# 评测相关纯单测（不依赖外部环境）
JAVA_HOME=/e/Java/jdk-21.0.5 mvn test \
  -Dtest='Eval*Test,RetrievalMetricsTest,RagEvalSetTest,SearchOptionsTest,RagEvalControllerTest' \
  -DfailIfNoSpecifiedTests=false

# 离线评测集成测试（需要 MySQL / Redis / Milvus；首次导入语料约 1~2 分钟）
JAVA_HOME=/e/Java/jdk-21.0.5 mvn test -Dtest=RagEvalIT -DfailIfNoSpecifiedTests=false

# 全量（MainApplicationTests 需要 MySQL / Redis / 邮件配置齐备）
JAVA_HOME=/e/Java/jdk-21.0.5 mvn test
```

---

## 📌 说明

- 本项目是独立项目，用户模块从另一个 Spring Boot 项目移植并做了裁剪，基础设施（Milvus 等）独立部署。
- Milvus 用的是 LangChain4j 的 **legacy `langchain4j-milvus`** 模块（LangChain4j 2.0 会移除它），这是一个**有到期日的刻意权衡**，理由见 [knowledge/01](knowledge/01-总体架构与路线图.md)。
- 检索链路的召回数与 `minScore` 已于 2026-10-27 用 [离线评测](knowledge/60-离线评测.md) 标定（15 / 15 / 0.8）；
  `rrf-k`、切片大小等仍是起点不是结论——改动前后请用离线评测比 delta，再决定是否保留。

---

如果这个项目对你有帮助，欢迎点个 ⭐ Star 支持一下～
