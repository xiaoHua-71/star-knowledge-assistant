# ReAct 主循环全链路（面试讲解版）

> **定位**：把「一次流式对话」从 HTTP 入口、记忆重建、ReAct 循环、工具执行、人工确认、状态落库、SSE 推流到中断续跑**全部串起来**的一篇走读文档。
>
> 本文负责「串链路」；状态机细节见 [12-中断与恢复的状态机](12-中断与恢复的状态机.md)，工具契约见 [30-工具调用](30-工具调用.md)，推流细节见 [31-流式输出SSE](31-流式输出SSE.md)。三篇不重复，本文只讲**它们怎么接在一起**。
>
> 适用场景：面试讲项目、带新人走读、自查「某一步到底在哪个类里发生」。
>
> 代码基线：LangChain4j `1.20.0`；入口 `ChatStreamServiceImpl`，主循环 `AgentRunner`。

---

## 0. 先给结论

### 0.1 一句话版本

本项目的 ReAct 是**用原生 tool calling 实现的宿主侧循环**：模型每轮返回「文本 + 工具调用请求」，宿主执行工具、把结果回填成 `ToolExecutionResultMessage`、再调用模型；循环自己写，是为了在每次模型/工具调用前后插入**落库、事件、中断检查、审批、幂等**这些框架黑盒循环给不了的钩子。

### 0.2 三个不变量（面试先抛这三个）

1. **意图先落库，再执行**：工具行 `PENDING` 提交后才真正调用；崩溃后知道「本来要做什么」。
2. **主循环不包长事务**：每一步一个短事务；模型/工具调用在事务外。
3. **续跑只重放完整轮**：LLM 成功且本轮所有工具调用都有结果；否则整轮丢弃重生成，避免孤儿 `tool_call`。

### 0.3 三档讲法

| 时长 | 讲什么 | 关键句 |
|---|---|---|
| 30 秒 | 0.1 + 0.2 | 「借 LC4j 的零件，不用它的循环」 |
| 3 分钟 | 0.1 + 第 2 节调用链 + 第 3 节主循环 | 「入口只负责建 run 和 SSE，循环在 `AgentRunner`」 |
| 20–30 分钟 | 第 1–7 节，按第 8 节应对追问 | 「可靠性来自状态机，不是来自 prompt」 |

### 0.4 一张总图

```
POST /chat/stream
  → ChatController.stream
  → ChatStreamServiceImpl.stream
      校验 / 归属 / 取模型 / 收敛旧 run
      落用户消息 (conversation_message)
      MemoryBuilder.build → [System, ...预算内历史, 当前用户消息]
      createRun(PENDING) + SseEmitter + AgentStreamHub.create
      agentExecutor.execute
          → AgentRunner.run
              emit run_start
              markRunning → drive
                  while round < maxRounds:
                      ① SP1 查 Redis 中断标志
                      ② beginLlmStep(PENDING)
                      ③ streamOnce(StreamingChatModel.chat)
                           delta → hub → SSE
                      ④ 规整 toolCallId / 累计 token / ai_call_log
                      ⑤ SP2 查中断标志
                      ⑥ finishLlmStep(SUCCEEDED | INTERRUPTED)
                      ⑦ messages += AiMessage
                      ⑧ 无工具请求 → finish：落助手消息 + finalizeRun + run_end
                      ⑨ 有工具请求 → executeToolBatch
                           逐个：beginToolStep(PENDING) → tool_call →
                                 需审批? 挂 WAITING_APPROVAL
                                 副作用? 幂等认领 → 执行 / 重放 / 转人工
                                 只读? 执行
                                 tool_result → messages += ToolExecutionResultMessage
                      → 下一轮
      finally hub.finish
```

---

## 1. 问题定义：ReAct 在治什么病

### 1.1 从「模型只会说」到「模型能做事」

大模型是无状态的文本生成器：它不能读数据库、不能发工单、不知道现在几点。要让它「动手」，需要一套协议：

> **模型用结构化输出表达「我想调用某个能力、参数是什么」→ 宿主解析并真正执行 → 把结果作为新消息回填 → 模型据此继续。**

这条「推理 → 行动 → 观察 → 再推理」的交替，就是 ReAct（Reasoning + Acting）。**ReAct 是范式，tool calling 是实现协议**。本项目用的是 OpenAI 兼容的原生 tool calling，不是让模型输出 `Thought:/Action:/Observation:` 再正则解析。

### 1.2 四类典型失败模式

| # | 失败模式 | 后果 | 本项目对策 |
|---|---|---|---|
| 1 | 模型点了不存在的工具（幻觉工具名） | 反射失败，整轮崩 | 未知工具变成一条「可用工具清单」结果回给模型 |
| 2 | 模型该调工具却不调 | 答非所问、凭空编造 | 工具 `description` + system prompt 引导；**无法保证**，见第 9 节 |
| 3 | 循环不收敛（反复调工具） | 成本与延迟失控 | `maxRounds` 护栏（默认 8） |
| 4 | 参数错 / 工具自身抛异常 | 拖垮整轮 | 工具错误一律转成结果文本回给模型，不向上抛 |

### 1.3 宿主侧还要额外解决四件事

这四件事是「能跑」和「敢上生产」的分界线，也是本项目自研循环的根本原因：

1. **可观测**：模型点了哪个工具、参数、结果、耗时、token。
2. **可中断**：用户点停止，要能停，而且不能停在脏状态。
3. **可恢复**：进程崩溃/用户中断后，能从断点接着跑，且不重复副作用。
4. **可治理**：副作用工具需要分级、人工确认、幂等。

### 1.4 分层边界

| 层 | 归它管 | 不归它管 |
|---|---|---|
| 协议层 | `@Tool` schema、JSON→POJO、厂商适配 | 循环控制、状态 |
| 编排层 | 模型→工具→模型的循环、轮次护栏、工具分派 | 运行状态怎么存、SSE 怎么推 |
| 状态层 | `agent_run` / `agent_step` / `agent_event`、中断、续跑、幂等 | 单步工具内部实现 |
| 传输层 | SSE 事件、心跳、断线重连 | 状态流转 |

本项目的位置：**协议层用 LC4j，编排层自研，状态层自研，传输层自研**。

---

## 2. 代码地图：谁调用谁

### 2.1 类职责表

| 类 | 职责 | 明确不负责 |
|---|---|---|
| `ChatController` | HTTP 入口、登录校验、建流失败回 JSON 信封 | 循环、状态 |
| `ChatStreamServiceImpl` | 接入：校验、落用户消息、重建 prompt、`createRun`、建 hub 通道、提交线程池 | 循环、事件落库 |
| `AgentRunner` | 主循环、安全点、工具批次、`finish/suspend/fail`、事件 | HTTP、SSE 细节、状态实现 |
| `AgentToolRegistry` | 工具注册、schema 生成、执行、错误收口 | 审批、幂等记录（它只是执行器） |
| `AgentStateService` | 所有状态落库的**短事务门面**（`claimToolStep` 刻意不加事务，见 12） | 业务编排 |
| `ResumePlanner` | 从 `agent_step` 重建消息列表 | 执行、状态流转 |
| `AgentRunServiceImpl` | `interrupt` / `resume` / `approve` / `reconnect` 的编排 | 主循环 |
| `AgentStreamHub` | 每 run 一个通道、去重游标、delta 缓冲、重连 | 状态持久化 |
| `MemoryBuilder` | 从 `conversation_message` 重建 prompt | 本次 run 的工具消息（它们在内存/`agent_step`） |
| `ObservabilityService` | `ai_call_log` / `ai_tool_log` | 失败不影响主流程 |

### 2.2 入口调用链（一次新消息）

```
ChatController#stream                       controller/ChatController.java:55
  └─ ChatStreamServiceImpl#stream           service/impl/ChatStreamServiceImpl.java:90
       ├─ 参数校验 / 会话归属 / 取 StreamingChatModel
       ├─ findActiveRunId：旧 run 未结束 → 置 NEW_MESSAGE 标志
       ├─ appendMessage(USER)              事务 #1
       ├─ new RunContext(userId, conversationId, kbIds)
       ├─ MemoryBuilder#build              SYSTEM_PROMPT + 预算内历史
       ├─ AgentStateService#createRun      agent_run: PENDING
       ├─ new SseEmitter(300s) + SseHeartbeat + AgentStreamHub#create
       └─ agentExecutor.execute
            └─ AgentRunner#run             agent/AgentRunner.java:133
                 ├─ emit(run_start)
                 ├─ markRunning            PENDING → RUNNING
                 └─ drive(...)             agent/AgentRunner.java:162
```

**为什么 `createRun` 在提交循环之前？** hub 的通道以 `runId` 为键，必须先有 `runId` 才能登记通道；否则循环先跑、前端后接流时找不到通道。M9 重构时从循环内部挪到了这里。

### 2.3 接口清单

| 接口 | 实现 | 作用 |
|---|---|---|
| `POST /api/chat/stream` | `ChatStreamServiceImpl#stream` | 新消息 + SSE |
| `POST /api/agent/run/{id}/interrupt` | `AgentRunServiceImpl#interrupt` | 登记停止请求 |
| `POST /api/agent/run/{id}/resume` | `AgentRunServiceImpl#resume` | 从挂起点续跑 |
| `POST /api/agent/run/{id}/approve` | `AgentRunServiceImpl#approve` | 同意/拒绝副作用工具 |
| `GET /api/agent/run/{id}/stream` | `AgentRunServiceImpl#reconnect` | 断线重连，纯读 |
| `GET /api/agent/run/{id}` | `AgentRunServiceImpl#getRun` | 运行状态 + `pendingTool` |
| `GET /api/agent/run/{id}/steps` | `AgentRunServiceImpl#listSteps` | 执行链 |
| `GET /api/agent/run/list` | `AgentRunServiceImpl#listRuns` | 会话下运行列表 |

### 2.4 一次完整故事：查年假 + 建工单

场景：用户发「帮我查一下年假政策，然后给 HR 建个工单」。模型先调只读的 `searchKnowledge`，再调需确认的 `createTicket`。

| 时刻 | 发生了什么 | 代码位置 | 落库 / 事件 |
|---|---|---|---|
| T1 | 收到 `POST /chat/stream` | `ChatStreamServiceImpl#stream` | — |
| T2 | 落用户消息、重建 prompt、建 run | 同上 | `conversation_message`（USER）、`agent_run`（PENDING） |
| T3 | 提交循环，发 `run_start`，`PENDING → RUNNING` | `AgentRunner#run` | `agent_event(run_start)` |
| T4 | 第 1 轮：SP1 未命中 → 落 LLM 步 → 流式调模型 → 返回 `searchKnowledge` 调用 | `AgentRunner#drive` / `streamOnce` | `agent_step(LLM_CALL, seq=1, SUCCEEDED)` |
| T5 | 执行工具批次：先落意图行 → `tool_call` → 只读执行 → `tool_result` → 回填消息 | `AgentRunner#executeToolBatch` | `agent_step(TOOL_CALL, seq=2, SUCCEEDED)`、`agent_event(tool_call/tool_result)` |
| T6 | 第 2 轮：LLM 返回 `createTicket` 调用；落意图行；发现 `requireApproval=true` | `executeToolBatch` → `suspendForApproval` | `agent_step(LLM_CALL, seq=3, SUCCEEDED)`、`agent_step(TOOL_CALL, seq=4, PENDING)`、`agent_run(WAITING_APPROVAL)`、`agent_event(interrupt, APPROVAL_REQUIRED)` |
| T7 | 循环 `return` 挂起，线程释放；hub 收尾，SSE 断开 | `drive` 返回 `suspended=true` | — |
| T8 | 用户点「同意」：校验、重建 prompt、`WAITING_APPROVAL → RUNNING` | `AgentRunServiceImpl#approve` | — |
| T9 | 先执行待确认的 `createTicket`（幂等认领 → 执行恰好一次），再补齐同批剩余调用 | `completeApprovedBatch` → `executeSideEffectTool` | `agent_step(seq=4, SUCCEEDED, idempotencyKey=...)`、`agent_event(tool_result)` |
| T10 | 用 `ResumePlanner` 重建消息，复用 `AgentRunner#resume` 继续 | `AgentRunServiceImpl#approve` → `AgentRunner#resume` | `agent_event(run_start)` |
| T11 | 第 3 轮：LLM 生成最终回答，无工具请求 | `drive` → `finish` | `agent_step(LLM_CALL, SUCCEEDED)` |
| T12 | 落助手消息、`COMPLETED`、`run_end` | `AgentRunner#finish` | `conversation_message(ASSISTANT)`、`agent_run(COMPLETED)`、`agent_event(run_end)` |

**注意 T7**：approve 接口会**立即返回** `RUNNING`，但后续事件不在原 SSE 上——前端必须重新接 `GET /agent/run/{id}/stream` 才能看到 T9–T12 的流。这是前端契约里反复强调的一点。

---

## 3. 主循环逐轮拆解

### 3.1 伪代码

```
run():
    emit(run_start)
    markRunning()
    drive(prompt, round0=0, seq0=0, in0=0, out0=0)

resume(messages, round0, seq0, in0, out0):
    emit(run_start)
    drive(messages, round0, seq0, in0, out0)

drive():
    specs = toolRegistry.specifications()
    while round < maxRounds:
        if interruptFlag.get(runId) present:              # SP1
            return suspend(type)

        round++; seq++
        beginLlmStep(round, seq, requestSnapshot)         # PENDING
        resp = streamOnce(messages, specs)                # 阻塞桥接
        ai = normalizeToolCallIds(resp.aiMessage())
        tokens += ...
        logChatCall(...)                                  # 中断也照样记
        if interruptFlag.get(runId) present:              # SP2（无条件）
            finishLlmStep(INTERRUPTED)
            return suspend(type)

        finishLlmStep(SUCCEEDED)
        messages += ai
        if ai has no tool requests:
            return finish(ai.text)

        batch = executeToolBatch(ai)
        if batch.suspended:
            return suspended
    return finish(lastText or MAX_ROUNDS_MESSAGE)
```

### 3.2 每一步为什么这么做

#### ① SP1：每轮开头查中断

在模型调用**之前**查。命中时这一轮还没有任何在飞步骤，状态最干净：直接 `suspend`，不写助手消息。

#### ② `beginLlmStep`：调用前先落 LLM 步

`agent_step` 里先写一行 `PENDING`，携带轻量请求快照（`messageCount` / `toolNames` / `lastUserMessage`）。目的：

- 崩溃后知道「这次运行已经发起过第几轮」；
- 续跑时用它判断这一轮是否完整；
- 完整 prompt 体积大且序列化 LC4j 消息有兼容风险，所以只存轻量快照。

#### ③ `streamOnce`：把回调式流式桥接成阻塞

LC4j 的 `StreamingChatModel#chat` 是回调式的：注册 handler 后立即返回。`streamOnce` 用 `CompletableFuture` 把它桥接成「阻塞等结果」：

- `onPartialResponse` → `sink.onDelta` → SSE `message_delta`；
- `onCompleteResponse` → `future.complete`；
- `onError` → `future.completeExceptionally`。

**中断抑制**：回调里每 200ms 节流查一次 Redis 标志；命中后置 `suppressed=true`，**不再推 delta**（用户看到字立刻停），但**不提前掐断 HTTP**。原因：`streamingHandle().cancel()` 后终态回调是否到达依 provider 而定，可能让阻塞等待的 future 永远挂死。线程等这次调用自然结束，再由 SP2 收敛。

#### ④ 规整 `toolCallId`、累计 token、写调用日志

模型偶尔不给工具调用 id，测试替身也不给。`normalizeToolCallIds` 把空 id 补成 `call-{round}-{i+1}`。**必须规整**：响应快照里的 id、`agent_step.toolCallId`、回喂模型的消息三处必须一致，否则续跑重放会拼出「有 `tool_call` 没有 `tool_result`」的孤儿请求。

token 在这里累计；`ai_call_log` 也在这里写。**即使本轮随后被 SP2 中断，token 也已经真实消耗，所以日志照写**。

#### ⑤ SP2：模型返回后**无条件**查中断

这是实现上的一处刻意偏离（见 [12](12-中断与恢复的状态机.md) 的说明）：图里原本画在「模型要调工具」分支内，实现改成无条件。

- **理由**：最常见的停止场景是用户看着最终回答流到一半想停；若只在有工具请求时检查，停止在这类轮次下等于无效。
- **代价**：丢弃最多一轮已生成的文字，续跑时重生成，token 双计（`ai_call_log` 里能看到同一 run 两次 CHAT）。
- **取舍**：前端已收到 `interrupt` 并停止渲染，让这轮「完整跑完再当 COMPLETED」反而更怪。

#### ⑥ `finishLlmStep` 与 `messages += ai`

成功记 `SUCCEEDED`，中断记 `INTERRUPTED`。无论有没有工具请求，`AiMessage` 都先放进本轮消息列表——它可能是「最终回答」，也可能是「带 tool_calls 的中间轮」。

#### ⑦ 无工具请求 → `finish`

`finish` 做四件事：

1. `appendMessage(ASSISTANT)` 落助手消息（**只有正常结束才落**）；
2. `finalizeRun(COMPLETED, outputMessageId, tokenIn, tokenOut, rounds)`；
3. `interruptFlag.clear(runId)`；
4. `emit(run_end)`。

#### ⑧ 有工具请求 → `executeToolBatch`

见第 4 节。批次返回 `suspended=true` 时，`drive` 直接返回 `RunResult(suspended=true)`，不写助手消息。

#### ⑨ `maxRounds` 护栏

默认 8 轮（`ai.agent.max-rounds`，`AgentProperties#maxRounds`）。一轮 = 一次「模型响应 + 可能的一批工具执行」，不是工具个数。撞顶后用最后一轮文本；没有文本则用 `MAX_ROUNDS_MESSAGE` 兜底。

#### ⑩ 异常路径

`drive` 的 `catch (Exception)`：`failRun` → 清中断标志 → 发 `error` 事件（`code=50001`）→ 重新抛出。`ChatStreamServiceImpl` 的 `finally` 负责 `hub.finish`。**工具异常不会走到这里**——`AgentToolRegistry`/`executeAndRecord` 已把它转成工具结果。

### 3.3 线程模型

```
Servlet 请求线程                 agentExecutor 线程
     │                                  │
     ├─ 建 SseEmitter                   │
     ├─ hub.create(runId, emitter)      │
     ├─ execute(() -> runner.run(...)) ─┘
     └─ return emitter（请求线程释放）
                                        ├─ 调模型（阻塞）
                                        ├─ 执行工具（阻塞）
                                        └─ 完成 / 挂起 → 线程释放
```

- 循环是**阻塞式**的，一次活跃运行占一个线程；
- 线程池 `agentExecutor`：core 2 / max 4 / queue 100 / `CallerRunsPolicy`（队列满时提交线程自己跑，形成背压而不是丢任务）；
- **挂起后线程释放**，用户点继续时从池里取新线程，从数据库读状态接着跑；
- 仍存在的限制：一次**正在执行**的 LLM 调用/工具调用会占线程直到这次调用结束。

### 3.4 主循环的关键不变量

| # | 不变量 | 违反的后果 |
|---|---|---|
| 1 | 工具意图行先提交，再执行 | 崩溃后不知道副作用是否发生 |
| 2 | 主循环不包 `@Transactional` | 连接池耗尽；回滚抹掉副作用证据 |
| 3 | 挂起用 `return` 而不是抛异常 | 抛异常会被 `catch(Exception)` 误判成 `FAILED` |
| 4 | 半截回答不落助手消息 | 恢复后出现两条助手回复 |
| 5 | 中断标志用后即清 | 续跑时立刻又被挂起 |
| 6 | 里程碑事件先落库再推 | 断线后无法补发 |

---

## 4. 工具批次：三种执行语义

### 4.1 批次流程

```
executeToolBatch(ai):
    for req in ai.toolExecutionRequests():
        seq++
        sideEffect = registry.sideEffect(req.name)
        requireApproval = registry.requireApproval(req.name)

        beginToolStep(runId, round, seq, req.id, req.name, req.args,
                      sideEffect, requireApproval)       # 意图行先提交
        emit(tool_call)

        if requireApproval:
            suspendForApproval(...)                     # WAITING_APPROVAL
            return suspended

        if sideEffect:
            out = executeSideEffectTool(...)            # 认领 + 三分支
        else:
            out = executeAndRecord(...)                 # 只读，直接执行

        if out.needsApproval:
            suspendForApproval(...)
            return suspended

        messages += ToolExecutionResultMessage.from(req, out.resultText)

    return seq, not suspended
```

**为什么一批工具要顺序处理？** 模型在一轮里可能请求多个工具，但事件、`seq`、意图行、结果回填都需要确定顺序；顺序执行也让「批次中途需确认」的语义简单：整批暂停，等用户决定。

### 4.2 只读工具

`executeAndRecord` 直接调用 `AgentToolRegistry#execute`：

```
result = registry.execute(req, ctx)
finishToolStep(stepId, truncate(result.text), elapsed, SUCCEEDED|FAILED)
emit(tool_result)
logToolCall(...)
```

`registry.execute` **绝不向上抛**：

- 未知工具 → 返回「未知工具，可用工具：…」；
- 执行异常 → 返回「工具执行失败：…」；
- 参数绑定异常 → 同样兜住。

错误作为**工具结果**回给模型，让它自行纠正或改口。

### 4.3 副作用工具与幂等三分支

`executeSideEffectTool`：

```
key = IdempotencyKeys.of(runId, step.stepNo, step.toolName, step.argumentsJson)
claim = agentStateService.claimToolStep(step.id, key)

if claim == CLAIMED:
    return executeAndRecord(...)                        # 真正执行

existing = claim == KEY_CONFLICT
         ? findStepByIdempotencyKey(key)
         : getStep(step.id)

if existing != null && existing.toolResultJson != null:
    return replayResult(existing, ...)                  # 重放，绝不重新调用

return needsApproval(existing, UNKNOWN_SIDE_EFFECT_WARNING)  # 结果未知 → 转人工
```

三分支：

| 已存在那行 | 含义 | 处理 |
|---|---|---|
| 有 `toolResultJson` | 上次执行成功（或失败但有结果） | **重放**：直接当本次结果，`replayed=1`，不发起新调用 |
| 无结果、已认领 | 上次可能已发出、结果未知 | **绝不重跑**，转 `WAITING_APPROVAL` 人工确认 |
| 无结果、从未认领 | 操作没发生过 | 可以安全执行 |

补充：`FAILED` 且**从未认领**（无幂等键、无结果）可以重新认领并执行——它意味着执行前就失败了；而 `FAILED` 但**已经认领过**（有幂等键、无结果）说明请求可能已经发出，按「结果未知」转人工。

**为什么「无结果」不能重跑？** 因为「请求已经发到对方系统、对方已处理、只是响应没回来」这个状态没有技术手段自动判定。承认这一点，比假装能自动处理更安全。

**幂等键公式**：

```
idempotencyKey = sha256(runId | stepNo | toolName | canonical(argumentsJson)) 前 32 位
```

- `runId`：区分不同运行，合法的「再建一个一模一样工单」不会被误拦；
- `stepNo`：同一步重复插入会撞唯一键（`uk_idempotencyKey`），这是防线；
- `toolName`：不同工具同参数不互相干扰；
- `canonical(args)`：键排序、去空白、数值统一（`5.0` → `5`），否则 JSON 键序不稳定会让防线**静默失效**。

只读工具不生成键（`NULL`），依赖 MySQL 唯一索引允许多个 `NULL` 的特性。

### 4.4 人工确认：挂起、同意、拒绝

**碰到 `requireApproval=true`**：

1. 意图行已落、`tool_call` 已发；
2. `markWaitingApproval(runId, round)`：`RUNNING → WAITING_APPROVAL`；
3. `emit(interrupt, {status: WAITING_APPROVAL, interruptType: APPROVAL_REQUIRED, pendingTool})`；
4. `return`，线程释放。

**并发兜底**：`suspendForApproval` 里 `markWaitingApproval` 是条件更新；如果状态已被并发改变（例如用户刚点了停止），它不会成功，此时改为按中断标志（缺省 `USER_STOP`）收敛为 `SUSPENDED`，避免运行卡在 `RUNNING`。

**用户同意（`approve`）**：

1. 校验归属、状态 `WAITING_APPROVAL`、用户消息仍是会话最新一条；
2. **先重建 prompt**，再条件翻转 `WAITING_APPROVAL → RUNNING`（双击只有一个成功）；
3. 提交线程池：`completeApprovedBatch` 先执行当前待确认步骤（幂等认领 → 执行恰好一次），再补齐同一批剩余调用；若又遇到需确认工具，再次挂起；
4. 批次全部有结果后，`ResumePlanner` 重建消息，复用 `AgentRunner#resume` 继续。

**用户拒绝（`approve=false`）**：

1. `denyApproval`：`WAITING_APPROVAL → SUSPENDED`，清 `interruptType`；
2. `fillUnresolvedBatch`：把当前待确认行和本批剩余未执行调用全部写成 `FAILED` 结果，文本是「用户拒绝执行……原因：…」；
3. 发 `interrupt` 事件，返回 `SUSPENDED`。

**为什么拒绝也要补齐整批？** 因为 `ResumePlanner` 只重放完整轮；只要有一个 `tool_call` 缺结果，整轮会被丢弃，用户看到的拒绝原因就传不给模型。补齐后，续跑时模型能看到「用户拒绝 + 原因」，可以换个思路。

**等待确认时用户点停止**：同样没有循环线程可收敛，`AgentRunServiceImpl#interrupt` 会先 `fillUnresolvedBatch`（写「用户停止，该操作未执行」），再 `suspendRun(USER_STOP)`。

**等待确认时用户直接发新消息**：`ChatStreamServiceImpl` 发现旧 run 是 `WAITING_APPROVAL`，直接 `suspendRun(NEW_MESSAGE)`（没有循环线程可收敛），不让它悬空。

### 4.5 工具注册与启动校验

`AgentToolRegistry` 构造时扫描所有 `AgentToolPack`：

- 用 `ToolSpecifications.toolSpecificationFrom(method)` 生成 schema；
- 用 **方法级** `new DefaultToolExecutor(pack, method)`，因此 `@Tool(name=...)` 与 Java 方法名可以不同；
- 启动即失败：工具重名、缺 `@ToolMeta`、`@ToolMemoryId` 参数类型不是 `RunContext`、**副作用工具既不幂等也不需确认**。

最后一条是 M10 的安全底线：这种工具在恢复时一旦重跑就会重复下单/发通知，宁可启动失败，也不要等事故。

`RunContext` 通过 `@ToolMemoryId` 注入（`userId` / `conversationId` / `kbIds`），不进模型可见的参数表，避免模型去猜或编造。

---

## 5. 中断、续跑、重连、崩溃恢复

### 5.1 中断是协作式的

`POST /agent/run/{id}/interrupt` **只登记请求**：写 Redis `agent:interrupt:{runId}`，值 = `InterruptTypeEnum` 名。真正的状态收敛由运行线程在下一个安全点完成。

**例外**：状态是 `WAITING_APPROVAL` 时没有循环线程在跑，接口会直接补齐本批次未执行的调用为「未执行」，再把状态置为 `SUSPENDED(USER_STOP)`——否则这个运行会永远挂着。

**为什么不用 `Thread.interrupt()`？**

1. LLM 调用是阻塞 IO，interrupt 不一定能让 HTTP 客户端干净退出；
2. 挂起动作本身要写数据库，线程被 interrupt 打断可能导致写库失败，状态不一致；
3. 协作式的中断点位置明确，出问题时容易推断「它停在哪了」。

**为什么用 Redis 而不是内存 Map？** 当前单实例内存 Map 也能工作，但 SSE 重连、将来多实例、以及「点停止的请求和跑着的运行不在同一实例」都会让它失效。Redis 顺手解决，成本几乎为零。标志**不设 TTL、必须显式删除**，否则会出现「用户点了停止、标志却自己过期了」。

### 5.2 两个安全点

| 安全点 | 位置 | 命中后的动作 |
|---|---|---|
| SP1 | 每轮开头、模型调用前 | 无在飞步骤，直接挂起 |
| SP2 | 模型返回后、工具执行前 | LLM 步记 `INTERRUPTED`，挂起；本轮输出丢弃 |

**为什么工具执行中间没有安全点？** 因为工具是一个 HTTP 调用，**要么没发出去，要么已经发出去了**，中间没有一个「可以干净停下」的时刻。硬要在中间停，会产生「请求发出去了但不知道结果」的模糊状态——那是最难处理的情况。所以 SP2 放在工具执行之前：要么整组不执行，要么整组执行完。

**流式中断**：`streamOnce` 在回调里节流查标志，命中后只抑制 `onDelta`，不提前掐断 HTTP；线程等调用自然结束，再由 SP2 收敛。

### 5.3 续跑：`ResumePlanner` 的完整轮规则

`POST /agent/run/{id}/resume`：

1. 校验 `SUSPENDED`、归属、用户消息仍是会话最新一条；
2. `MemoryBuilder` 重建基础历史（系统 + 预算内 USER/ASSISTANT）；
3. `ResumePlanner.reconstruct(base, steps)` 从 `agent_step` 叠加本次运行已完成的轮；
4. 条件翻转 `SUSPENDED → RUNNING`（防双击/并发续跑）；
5. 建 hub 通道，提交 `AgentRunner#resume`，轮次/序号/token 累计都续上。

**完整轮的定义**：

> 该轮 LLM 步 `SUCCEEDED`，且**模型请求的每一个工具调用**都有对应的、带结果的工具步（`SUCCEEDED` 或 `FAILED`——失败结果也要回给模型）。

遇到第一个不完整轮就**整轮丢弃**，由主循环重新生成。

**为什么必须整轮丢、不能只丢半截？** OpenAI 兼容协议不允许出现「有 `tool_call` 却没有对应 `tool_result`」的 `AiMessage`，那是一次 400（孤儿 `tool_call`）。一旦某轮工具结果不齐，连它前面的 `AiMessage` 也不能重放。

**两个容易踩的细节**：

- **`maxSeq` 必须取全部步骤（含未完成）的最大 `seq`**。否则「模型返回后挂起」时那条 `INTERRUPTED` 的 LLM 步已占了 `seq=3`，续跑新 LLM 步再用 `seq=3` 会撞 `uk_runId_seq`；
- **`maxSeq` 不是已重放轮的最大值**，否则同样会撞号。

**遇到「已开始、结果未知」的副作用步**：`ResumePlanner` 返回 `danglingSideEffect=true`，`resume` 不自动跑，转为 `WAITING_APPROVAL`：

- 无幂等键（从未认领）→ 用户同意后可以安全执行；
- 有幂等键（结果未知）→ 用户「同意」只代表已知晓，系统**绝不重跑**。

### 5.4 重连 ≠ 续跑

| | 重连 | 续跑 |
|---|---|---|
| 触发 | 网络断了 / 刷新页面 | 用户点「继续」 |
| 接口 | `GET /agent/run/{id}/stream?lastEventId=` | `POST /agent/run/{id}/resume` |
| 运行状态 | 可能还在跑，也可能已结束 | 已经挂起 |
| 做什么 | 补发漏掉的事件 + `message_snapshot`，**不改变状态** | `SUSPENDED → RUNNING`，**从断点继续执行** |
| 性质 | 纯读、幂等 | 有状态变更 |

`AgentStreamHub` 每个 `runId` 一个通道，通道持有锁、当前 emitter（带 `lastSentId` 游标）、delta 缓冲：

- `pushEvent`：里程碑事件先落库，再按游标推；
- `pushDelta`：只推不落库，同时累积进缓冲；
- `attach`：在同一把锁内「补发 `id > lastEventId` 的事件 → 发 `message_snapshot`（delta 缓冲）→ 换 emitter → 继续推」。

**为什么这样不会重、不会漏？** 事件有自增 id 做去重游标；delta 没有 id，用缓冲快照补偿。客户端断开不影响运行：运行线程只往当前 emitter 推，断了就标死、静默跳过，事件仍照常落库。

### 5.5 崩溃与超时

| 场景 | 机制 | 动作 |
|---|---|---|
| 进程崩溃/被杀 | `RunRecoveryScanner`（`ApplicationReadyEvent`） | 非终态 run → `SUSPENDED(SERVER_RESTART)`；无结果的副作用步 → `FAILED` |
| 中断后用户一直不恢复 | `RunTimeoutScanner`（每 10 分钟） | `SUSPENDED` 超过 24h → `CANCELLED`；半截文本 ≥10 字符且用户消息仍最新 → 落 `interrupted=1` 助手消息 |
| 等待确认时进程重启 | `scanZombieRuns` 也收敛 `WAITING_APPROVAL` | 重启后转 `SUSPENDED(SERVER_RESTART)`，用户可继续 |

**为什么启动扫描必须在 `ApplicationReadyEvent`？** 不能放 `@PostConstruct`——那时数据库可能还没就绪。且只在启动时跑一次。

### 5.6 怎么验证这套东西是对的

```
1. 用一个只读工具 + 一个「可控失败」的副作用测试替身（记录真实调用次数）
2. 不中断跑一遍 → 结果 A、副作用调用次数 n1
3. 在第 2 步中断再恢复 → 结果 B、副作用调用次数 n2
4. 断言：
     A == B                              ← 恢复后行为一致
     n1 == n2 == 1                       ← 不重复副作用
     ai_tool_log 里 replayed=1 的记录 > 0 ← 重放机制真的走了
     重复插入同一个 idempotencyKey 抛唯一冲突 ← 防线还在
```

最后一条看起来像在测数据库，但它其实是唯一能证明「防线没被人删掉」的方式。

---

## 6. 数据模型与事件契约

### 6.1 四张核心表

| 表 | 一行代表 | 关键字段 / 唯一键 |
|---|---|---|
| `agent_run` | 一次运行（用户提问 → 得到回答） | `status`、`interruptType`、`inputMessageId`、`outputMessageId`、`tokenIn/Out`、`stepNo`、`maxRounds` |
| `agent_step` | 一步（一次 LLM 调用或一次工具调用） | `stepNo`（第几轮）、`seq`（运行内自增，重放顺序）、`type`、`status`、`toolCallId`、`argumentsJson`、`toolResultJson`、`idempotencyKey`、`sideEffect`、`requireApproval`、`replayed`；唯一键 `uk_runId_seq`、`uk_idempotencyKey` |
| `agent_event` | 一个里程碑事件 | `eventType`、`dataJson`；自增 `id` 直接当 SSE 的 `id` |
| `conversation_message` | 一条用户/助手消息 | `role`、`content`、`promptVisible`、`interrupted`、`runId` |

工具结果**回填到 `TOOL_CALL` 同一行**（`toolResultJson`），不另起 `TOOL_RESULT` 行；`TOOL_RESULT` 是事件，不是表行。

### 6.2 事件与推流顺序

| `event` | 何时发 | 是否落库 | 是否有 `id` |
|---|---|---|---|
| `run_start` | 循环开始 / 续跑开始 | 是 | 是 |
| `message_delta` | 每个 token 增量 | **否** | 否 |
| `tool_call` | 意图行落库后 | 是 | 是 |
| `tool_result` | 工具结果回填后 | 是 | 是 |
| `interrupt` | 挂起 / 等待确认 / 拒绝 | 是 | 是 |
| `run_end` | 正常完成 | 是 | 是 |
| `error` | 不可恢复异常 | 是 | 是 |
| `message_snapshot` | 重连时补发累积文本 | 否 | 否 |
| `ping` | SSE 心跳（约 15s） | 否 | 否 |

**顺序铁律**：里程碑事件先 `agent_event` 落库拿到自增 id，再 `sink.onEvent` 推送。`message_delta` 频率太高不落库，靠 hub 的 delta 缓冲在重连时补 `message_snapshot`。

### 6.3 状态机（简表，详见 12）

```
PENDING → RUNNING → COMPLETED
              ├→ SUSPENDED（USER_STOP / NEW_MESSAGE / SERVER_RESTART）
              ├→ WAITING_APPROVAL（APPROVAL_REQUIRED）
              │     ├→ RUNNING（同意）
              │     └→ SUSPENDED（拒绝）
              └→ FAILED（不可恢复错误）
SUSPENDED → RUNNING（继续）
SUSPENDED → CANCELLED（超时封存）
```

终态：`COMPLETED` / `FAILED` / `CANCELLED`。

---

## 7. 决策记录：为什么不是别的方案

| 决策 | 备选 | 为什么选现在这个 | 代价 |
|---|---|---|---|
| 自研主循环 | LC4j `AiServices` 自动循环 | 黑盒循环没有「每次工具调用前后」的钩子，无法落意图行、发事件、查中断、做审批/幂等 | 自己维护循环与并发细节 |
| 自研状态机 | `langchain4j-agentic`（1.20.0-beta30） | `AgenticScopeStore` 解决通用状态，但待确认工具、幂等键等业务状态必须落自己的表；自研可直接 SQL 查、画状态图、测试覆盖；beta 版本风险 | 多写一批代码；需自己保证状态一致性 |
| 阻塞循环 + 专用线程池 | 响应式 / 事件循环 | 顺序循环可读、可调试；流式回调只需桥接一次 | 一次活跃运行占一个线程；池子小 |
| Redis 中断标志 | 内存 Map / MySQL 字段 | 高频读、无需持久、跨实例可见；内存 Map 在多实例/重连下失效；MySQL 每轮查太贵 | 多一个 Redis 依赖；标志必须显式清 |
| SP2 无条件检查 | 只在「有工具请求」时检查 | 最常见停止场景是最终回答流到一半；条件检查会让停止无效 | 丢弃一轮输出，token 双计 |
| 幂等键含 `runId/stepNo/toolName/canonical(args)` | 只 hash 参数 | 区分运行、让同一步重复插入撞唯一键、隔离工具、消除 JSON 键序不稳定 | 键的语义要解释清楚；`canonical` 有解析失败回退 |
| 结果未知转人工 | 无结果就重跑 | 「已发出、对方已处理、响应丢了」无法自动判定；重跑可能重复下单 | 需要用户确认；没有「核实后标记成功/失败」的专用接口 |
| 不做自动恢复 | 自动续跑 | 无法可靠判断「用户还想继续」；自动恢复可能替用户做了不想做的决定 | 用户必须手动点「继续」 |
| 不做 plan/todo | 计划 + `todo_update` | 单步工具循环不需要；先保证状态机正确 | `agent_run.planVersion` 恒 0，前端没有计划视图 |
| 自建 `MemoryBuilder` | LC4j `ChatMemory` / `ChatMemoryStore` | 消息本来就是业务数据（展示、审计、分页、截断重发），双写会不一致 | 每次调用前多一次 SELECT（可忽略） |

---

## 8. 面试问答库（20–30 分钟）

> 用法：不要按顺序背。先讲 0.1 + 0.2，然后根据面试官追问挑对应条目。每条答案都尽量带「为什么」和「代价」。

### 8.1 概念与架构

**Q1：这是 ReAct 吗？和普通 function calling 有什么区别？**

是。ReAct 是「推理—行动—观察」交替的范式；function calling 是实现协议。本项目的 `AiMessage.text` 是推理，`toolExecutionRequests` 是行动，`ToolExecutionResultMessage` 是观察，循环在 `AgentRunner`。没有解析 `Thought:/Action:/Observation:` 文本，因为原生协议更可靠。

**Q2：为什么不用 LangChain4j 的 `AiServices` 自动循环？**

它是内存里跑完就返回的黑盒，中间没有钩子。而本项目恰恰需要在「每次工具调用前后」落 `agent_step`、发 `tool_call`/`tool_result`、查中断标志、做审批/幂等。所以**借它的零件（`@Tool` schema、`DefaultToolExecutor`），不用它的循环**。

**Q3：为什么不用 `langchain4j-agentic`？**

1. 它是 `1.20.0-beta30`，核心执行路径不该押在 beta 上；
2. `AgenticScopeStore` 解决「状态存哪」，但我们的状态有业务语义（待确认工具、幂等键、计划版本），必须落自己的表；
3. 自研状态机可直接 SQL 查、画状态图、被测试覆盖，排查 Agent 行为时证据更多。

**Q4：入口和循环怎么分工？**

`ChatStreamServiceImpl` 只做接入：校验、归属、取模型、落用户消息、重建 prompt、`createRun`、建 SSE/hub 通道、提交线程池。**循环内**的状态落库、事件、助手消息、挂起与续跑执行在 `AgentRunner`；`resume` / `approve` 的入口编排在 `AgentRunServiceImpl`。这样传输和编排解耦，将来换 WebSocket 也不用动循环。

**Q5：一次运行几个线程？会不会阻塞？**

`agentExecutor` 上一个线程，从 `run` 到 `finish`/`suspend`。循环是阻塞式：LC4j 流式回调用 `CompletableFuture` 桥接成阻塞等待。请求线程建完 emitter 就释放。挂起/等审批时线程也释放。池子 core 2 / max 4 / queue 100 + `CallerRunsPolicy` 背压。代价是活跃运行一运行一线程。

### 8.2 主循环

**Q6：一轮里模型返回多个工具调用怎么办？**

顺序执行。每个工具 `seq++`、单独意图行、单独 `tool_call`/`tool_result`、单独结果消息。遇到 `requireApproval` 整批暂停；剩余未执行的由 approve 补齐，或拒绝/停止时写「未执行」。核心是保证消息里每个 `tool_call` 都有对应的 `tool_result`。

**Q7：`tool_call` 事件为什么不是「模型一开始决定调工具」就发？**

OpenAI 兼容协议里工具参数是分片拼装的，只有 `onCompleteResponse` 才拿到完整的 `toolExecutionRequests`；`onPartialResponse` 只有文本。要更早提示需要用 `onPartialToolCall`，本项目未用。所以事件在该轮响应结束后才发。

**Q8：为什么 LLM 步和工具步都要先落库？**

LLM 步：审计 + 续跑时重放 `AiMessage` 的来源。工具步：副作用证据 + 幂等认领行。尤其工具意图行，**必须先提交再执行**；反了的话进程崩溃就不知道副作用发生过没有。

**Q9：为什么主循环不能加 `@Transactional`？**

三个后果：长事务占着数据库连接几秒到几十秒，连接池耗尽；循环末尾异常回滚会把「副作用已发生」的证据抹掉；长事务持锁，和别的操作互相阻塞。正确做法是每一步一个短事务，独立提交，模型/工具调用在事务外。

**Q10：停止为什么不直接写 `SUSPENDED`？**

因为写状态的线程应该是拥有这次运行状态机的循环线程。HTTP 线程直接改状态会和循环线程的写入竞争；而且阻塞 IO 不一定能被打断。所以接口只登记 Redis 标志，循环在安全点自己收敛。用户感知的「字立刻停」由 `streamOnce` 抑制 delta 实现，和状态收敛解耦。

**Q11：安全点 2 为什么无条件检查？最终回答也会被丢。**

最常见的停止场景就是最终回答流到一半。如果只在「模型要调工具」时检查，停止在这类轮次下等于无效。代价是丢弃最多一轮已生成文字、续跑重生成、token 双计；但前端已收到 `interrupt` 并停止渲染，让这一轮完整跑完再当 `COMPLETED` 反而更怪。

### 8.3 状态与恢复

**Q12：续跑为什么只重放完整轮？什么算完整？**

完整 ⇔ 该轮 LLM `SUCCEEDED`，且模型请求的每个工具调用都有带结果的工具步（`SUCCEEDED` 或 `FAILED`）。否则整轮丢弃重生成。原因是 OpenAI 兼容协议不允许孤儿 `tool_call`，重放会 400。

**Q13：`maxSeq` 为什么要包含未完成步骤？**

因为未完成的 LLM 步也占了 `seq`。如果续跑新步骤从「已重放的最大 seq + 1」开始，就会撞 `uk_runId_seq`。所以取全部步骤的最大 `seq` 作为新步骤起点。

**Q14：为什么已完成的工具要重放而不是重跑？**

重跑可能得到不同结果（数据变了），破坏「恢复后行为一致」；重跑多花时间和钱；如果那个工具其实有副作用只是被错标为只读，重跑会出事故。重放从 `toolResultJson` 读，标 `replayed=1`，完全不发起新调用。

**Q15：断线重连和续跑有什么区别？**

重连是 `GET /stream?lastEventId=`，纯读、不改变状态，补发事件 + delta 快照；续跑是 `POST /resume`，`SUSPENDED → RUNNING`，提交循环。混用会导致「网络抖一下 Agent 从头跑一遍、工具被重复调用」。

**Q16：进程崩溃后怎么办？**

`RunRecoveryScanner` 在 `ApplicationReadyEvent` 扫描：非终态 run → `SUSPENDED(SERVER_RESTART)`；无结果的副作用步 → `FAILED`。用户手动 resume；遇到「已开始、结果未知」的副作用步转 `WAITING_APPROVAL`，同意只代表知晓，不重跑。

**Q17：中断后用户永远不恢复，会话会不会有问无答？**

会，所以 `RunTimeoutScanner` 每 10 分钟扫一次：`SUSPENDED` 超过 24h → `CANCELLED`。若有足够长（≥10 字符）的半截文本，且该 run 的用户消息仍是会话最新一条，落一条 `interrupted=1` 助手消息；否则不落，避免顺序错乱。

**Q18：怎么证明恢复后行为一致、不重复副作用？**

测试替身计数：不中断跑 A，中断续跑 B；断言 A == B、副作用调用次数 n1 == n2 == 1、`ai_tool_log` 有 `replayed=1`、重复插同一幂等键抛唯一冲突。最后一条是防线还在的证据。

### 8.4 副作用与可靠性

**Q19：幂等键为什么是这四个成分？规范化解决什么？**

`runId` 区分运行（合法的第二次相同操作不会被误拦）；`stepNo` 让同一步重复插入撞唯一键；`toolName` 隔离工具；`canonical(args)` 消除 JSON 键序/空白/数值格式不稳定。直接 hash 原始 JSON 会得到不稳定的键，唯一索引防线**静默失效**（不报错，只是偶尔重复执行）。

**Q20：撞键三分支？为什么「无结果」不能重跑？**

有结果 → 重放；无结果、已认领 → 结果未知 → 转人工，绝不重跑；`FAILED` 且从未认领（无幂等键）→ 可以重新认领执行，说明失败发生在副作用之前。因为「请求已发出、对方已处理、响应丢了」无法自动判定，重跑就是重复下单。这个模糊状态只能交给人工。

**Q21：approve 如何保证「同意多少次都只执行一次」？**

两层条件更新 + 一层唯一索引：`WAITING_APPROVAL → RUNNING` 条件翻转防双击；`claimToolStep` 条件更新（无键、无结果、状态 `PENDING/FAILED`）+ `uk_idempotencyKey` 防并发；幂等键在用户同意后才生成。真正执行的那次 `replayed=0`，重放的那次 `replayed=1`。

**Q22：拒绝/停止为什么要补齐整批工具？**

不补齐则该轮不完整，`ResumePlanner` 整轮丢弃，拒绝原因传不给模型；补齐后每个 `tool_call` 都有 `FAILED` 结果，续跑时模型能看到原因并换思路。停止同理，只是文本是「用户停止，该操作未执行」。

**Q23：工具失败、模型幻觉工具名怎么处理？**

`AgentToolRegistry#execute` 永不向上抛：未知工具返回可用清单；执行异常/参数绑定异常转成结果文本。模型拿到错误后可以改口。外层还有 `maxRounds` 护栏和 4000 字符结果截断。

### 8.5 工程化与边界

**Q24：多实例部署有什么问题？**

中断标志在 Redis 跨实例可用，状态在 MySQL 也可续跑；但 `AgentStreamHub` 是进程内 `Map`，SSE 必须粘到跑循环的实例，否则重连只能读库补发、收不到后续 delta。要真正支持多实例，需要 Redis Pub/Sub 或消息队列广播事件。当前是单实例，已知限制。

**Q25：模型不调工具怎么办？**

检索已从「自动」改为「工具」，召回依赖模型判断。当前靠工具 `description` + system prompt 引导，没有强制。M1.5 只评测检索本身，测不到「模型是否愿意调工具」，需要单独的 Agent 行为评测（**待验证**）。退路是加更强的系统提示，或对特定 `kbIds` 恢复强制检索。

**Q26：为什么没有 plan/todo？**

范围决策：单步工具循环不需要计划；先保证状态机正确。`agent_run.planVersion` 恒 0，`agent_todo` 未建表。将来要做，需要计划版本号、`todo_update` 事件和「计划步骤与工具步骤」的映射。

**Q27：这套系统的瓶颈在哪？**

活跃运行一运行一线程，池子只有 2/4；长 LLM 调用会占线程。其次是 SSE 通道是单实例内存态。再次是 SQL 持久化的写放大（每步至少一次写）。如果并发上量，优先考虑虚拟线程/更细的事件驱动、事件总线、以及把观测日志异步化。

---

## 9. 已知限制与下一步

| # | 限制 | 影响 | 可能的下一步 |
|---|---|---|---|
| 1 | `AgentStreamHub` 是进程内 Map | 多实例下 SSE 不跨实例 | Redis Pub/Sub / MQ 广播 |
| 2 | 没有自动恢复 | 挂起必须用户手动继续 | 可靠判断「用户还想继续」后再做 |
| 3 | 没有运行去重 | 用户连点两次「发送」会创建两个 run | 用 `inputMessageId` 做幂等 |
| 4 | 工具执行中途不可中断 | 工具要么不开始，要么执行完 | 有意的设计，避免「发出去了但不知道结果」 |
| 5 | 中断的 LLM 调用不能续写 | 必须整段重新生成，token 双计 | 模型 API 不支持，无解 |
| 6 | 没有 plan/todo | `agent_run.planVersion` 恒 0 | 计划版本 + `todo_update` 事件 |
| 7 | 「结果未知」没有核实接口 | 只能 approve 的「已知晓」语义收口 | 增加「人工核实后标记成功/失败」 |
| 8 | `createTicket` 是内存实现 | 重启即丢，仅用于演示状态机 | 换真实后端并尽量支持幂等键 |
| 9 | 模型是否愿意调工具未验证 | 检索召回可能不稳定 | 单独的 Agent 行为评测 |
| 10 | token 估算系数 `1.5` 未标定 | 预算裁剪是近似 | 按模型 tokenizer 实测 |
| 11 | 幂等键用 `stepNo`（轮次）而非 `seq` | 同一轮内相同工具+相同参数会撞键，按重复操作处理 | 演示工具不会主动重复调用；若模型在同一轮请求两个相同副作用调用，第二个会走撞键路径。如要改含 `seq`，需重新论证 |
| 12 | 流式中断不提前掐 HTTP | 停止后线程可能还要等本次调用结束 | 取决于 provider 的 cancel 语义 |
| 13 | 客户端断开后循环仍可能跑完 | 只有推流被跳过，状态照常落库 | 由中断标志在安全点收敛 |

---

## 10. 一页速查

### 核心类与方法

| 关注点 | 入口 |
|---|---|
| HTTP 新消息 | `ChatController#stream` → `ChatStreamServiceImpl#stream` |
| 主循环 | `AgentRunner#run` / `#resume` → `#drive` |
| 单轮流式 | `AgentRunner#streamOnce` |
| 工具批次 | `AgentRunner#executeToolBatch` |
| 副作用执行 | `AgentRunner#executeSideEffectTool` → `#replayResult` |
| 挂起/审批 | `AgentRunner#suspend` / `#suspendForApproval` |
| 正常收尾 | `AgentRunner#finish` |
| 续跑重建 | `ResumePlanner#reconstruct` |
| 中断/续跑/审批/重连 | `AgentRunServiceImpl#interrupt` / `#resume` / `#approve` / `#reconnect` |
| 状态落库 | `AgentStateService`（每个方法一个短事务；`claimToolStep` 刻意不加事务） |
| 事件推流 | `AgentStreamHub` + `AgentEventSink` |
| 记忆重建 | `MemoryBuilder#build` |
| 工具注册 | `AgentToolRegistry` 构造函数 / `#execute` |
| 崩溃扫描 | `RunRecoveryScanner#onReady` |
| 超时封存 | `RunTimeoutScanner#scan` |

### 表与事件

- 表：`agent_run`、`agent_step`、`agent_event`、`conversation_message`（另有 `ai_call_log`、`ai_tool_log` 观测表）
- 事件：`run_start`、`message_delta`、`tool_call`、`tool_result`、`interrupt`、`run_end`、`error`、`message_snapshot`、`ping`
- 状态：`PENDING`、`RUNNING`、`WAITING_APPROVAL`、`SUSPENDED`、`COMPLETED`、`FAILED`、`CANCELLED`

### 配置默认值

| 配置 | 默认 | 作用 |
|---|---|---|
| `ai.agent.max-rounds` | 8 | 主循环最大轮次 |
| `ai.agent.max-tool-result-chars` | 4000 | 单工具结果截断 |
| `ai.agent.sse-ping-interval-ms` | 15000 | SSE 心跳 |
| `ai.agent.suspend-retention-ms` | 86400000（24h） | 挂起超时封存 |
| `agentExecutor` | core 2 / max 4 / queue 100 | 循环线程池 |
| SSE timeout | 300000ms | `SseEmitter` 超时 |

### 最后三句话（收尾用）

1. **协议用官方，编排和状态自研**——因为可靠性不在 prompt 里，在状态机里。
2. **每一步先落库再执行**——崩溃后还能知道「本来要做什么」。
3. **宁可转人工，也不自动重跑未知副作用**——承认技术边界，比假装能自动处理更安全。
