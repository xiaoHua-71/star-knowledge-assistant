# chat · 对话与流式

> ✅ **状态：`POST /chat/message`（非流式）M4；`POST /chat/stream`（流式）M6「最小版」+ M7 工具调用循环（检索改为「工具」）+ M8 运行状态落库 + **M9 断线回放/中断/续跑** + **M10 人工确认** 均已实现**（见 [40-agent](40-agent-中断与恢复.md) 与 [02-SSE协议规范](02-SSE协议规范.md)）。

## 这个模块只有一个核心接口

对话是**流式**的，所以这个模块的主体是 `POST /api/chat/stream`。

**协议细节（事件类型、重连、解析代码）全部在 [02-SSE协议规范](02-SSE协议规范.md) 里，本文不重复。** 这里只讲接口本身的入参出参和业务行为。

## 接口总览

所有路径都要加 `/api` 前缀（见 [01-通用约定](01-通用约定.md)）。**两个接口都需要登录**，未登录返回 `40100`。

| 方法 | 路径 | 说明 | 响应 `Content-Type` |
|---|---|---|---|
| POST | `/chat/stream` | 发消息 + **流式**接收回答（★ 主交互） | 成功 `text/event-stream`；建流失败 `application/json`（普通信封） |
| POST | `/chat/message` | 发消息 + **一次性**接收回答（简化路径，非流式、无检索） | `application/json` |

> ⚠️ `/chat/stream` 的「建流失败回 JSON 信封」是整个对接最容易翻车的地方——**必须先看 `Content-Type` 再决定怎么解析**。见 [02-SSE协议规范](02-SSE协议规范.md) 坑 2。

## 接口

### 发消息并接收流式回答

```
POST /api/chat/stream
Content-Type: application/json
Accept: text/event-stream
```

#### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `conversationId` | string | 是 | 会话 id |
| `content` | string | 是 | 用户消息，1~4000 字符（**按字符数算，不是字节**） |
| `kbIds` | string[] | 否 | 本次要用哪些知识库检索。**不传则用会话上已关联的**；传空数组 `[]` 表示本次不用 RAG |

**关于 `kbIds`**：它允许临时选择知识库，而不改变会话的长期关联。这在「这个会话平时关联 A 库，但这次想查一下 B 库」时有用。如果不需要这个能力，前端可以不传。

#### 响应

**成功时**：`Content-Type: text/event-stream`，开始推事件，第一批事件一定是：

```
id: 9001
event: run_start
data: {"runId":"100","conversationId":"7","messageId":"55"}

```

**失败时**：`Content-Type: application/json`，普通信封。**前端必须先判断 Content-Type**，见 [02-SSE协议规范](02-SSE协议规范.md) 的「致命陷阱」。

#### 可能的失败（建流阶段）

| `code` | 场景 |
|---|---|
| `40000` | `content` 为空 / 超长 / `conversationId` 缺失 |
| `40100` | 未登录 |
| `40101` | 这个会话不属于当前用户 |
| `40400` | 会话不存在（已被删） |
| `50001` | 该会话当前已有正在进行的运行（用户连点两次发送，或上一个回答还没结束） |

**⚠️ 最后一条 `50001` 需要前端配合**：用户在回答生成中又点了一次发送时，不要静默失败。建议的做法是**在回答生成中禁用发送按钮**，或者提示「当前回答还没结束，请先停止」。

### 发消息并一次性接收回答（非流式）

```
POST /api/chat/message
Content-Type: application/json
```

> ⚠️ 这是**简化路径**，不是主要交互方式。它**无检索**（不会去知识库找资料）、**无 run**（不创建运行记录，因此没有中断 / 重连 / 事件流）。主要交互请用上面的 `/chat/stream`。它存在的意义是：在流式（M6）落地前，让「一问一答落库」这条主链路端到端可验证，也方便脚本 / 冒烟测试。

#### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `conversationId` | string | 是 | 会话 id |
| `content` | string | 是 | 用户消息，1~4000 字符（**按字符数算**） |
| `kbIds` | string[] | 否 | **当前版本忽略**（无检索）。保留该字段是为了与 `/chat/stream` 形状一致 |

#### 响应

成功时 HTTP 200，`data` 是**落库后的助手消息**（`MessageVO`，结构见 [20-conversation](20-conversation-会话与消息.md)）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": "56", "seq": 2, "role": "ASSISTANT", "content": "根据《员工手册》…",
    "toolCalls": null, "interrupted": false, "tokenCount": 486,
    "createTime": "2026-10-11T10:00:08"
  }
}
```

#### 可能的失败

| `code` | 场景 |
|---|---|
| `40000` | `content` 为空 / 超 4000 字符 / `conversationId` 缺失 |
| `40100` | 未登录 |
| `40101` | 这个会话不属于当前用户 |
| `40400` | 会话不存在（已被删） |
| `50001` | 对话模型未配置（缺 `DEEPSEEK_API_KEY`），或模型调用失败 |

#### 业务行为

- **用户消息立刻落库**，助手消息在模型返回后落库。两条都进 `conversation_message`，可用 `GET /conversation/{id}/messages` 取回。
- 模型调用失败时，**用户消息已落库、助手消息不落库**——补救用「截断重发」。

## 业务行为

### 一次请求会创建一条用户消息

调这个接口**不需要**先单独调「保存消息」的接口。后端会在建流成功后立刻把用户消息落库，并在 `run_start` 事件里返回 `messageId`。

### 助手消息什么时候落库

**只在运行正常结束时落库一条助手消息。**

这意味着：

- 运行中**不能**通过消息列表接口看到正在生成的回答（要等结束，或者用重连机制拿 `message_snapshot`）
- 运行被中断时，半截的回答**不作为消息保存**（否则用户继续之后会看到两条回复）

### 回答里可能带工具调用（M7，2026-10-14）⚠️ 行为变化

**M7 起，流式对话不再「自动检索」。** 单次模型调用被换成 **ReAct 工具循环**（「模型 → 工具 → 模型…」），检索改由模型按需调用 `searchKnowledge` 工具完成（见 [60-tools](60-tools-工具.md)、[knowledge/30](../knowledge/30-工具调用.md)）。

- 调用了工具时，前端会收到 `tool_call`（开始）与 `tool_result`（结束）事件——**工具调用过程现在是可见的**。
- 用哪些库仍由 `kbIds` 决定（语义不变）：请求带了用请求的，没带用会话关联的，`[]` 表示本次不关联任何库（此时 `searchKnowledge` 返回「没有关联知识库」给模型）。
- **与 M6 的行为差异**：以前是「**必检索**」（每次对话都自动找资料），现在是「**择机检索**」——**模型不调就不召回**。这是刻意取舍（理由见 [knowledge/30](../knowledge/30-工具调用.md) 3.3），效果待评测集验证。
- 检索失败（库被删 / 越权 / 向量模型不匹配）仍**不会让请求失败**：`searchKnowledge` 逐库吞异常，返回「未找到」文本给模型。
- 循环有**轮次护栏**（`ai.agent.max-rounds`，默认 8）。撞护栏时后端仍会推 `run_end`（`status=COMPLETED`），回答可能是一条兜底提示。

> 想展示「引用了哪几篇文档」：`tool_result`（`toolName=searchKnowledge`）的 `result` 里已含命中片段/来源，可从中提取。一个更规范的 `references` 事件仍是**待定功能**。

## 与其它接口的关系

一次完整的对话流程涉及三个模块：

```
1. （可选）POST /api/conversation/create      新建会话，拿 conversationId
2. POST /api/chat/stream                      发消息 + 收流式回答
      └─ 期间可能收到 interrupt 事件 → 见 40-agent
   （简化路径：POST /api/chat/message          非流式，一次性返回，无检索 / 无 run）
3. GET  /api/conversation/{id}/messages       拉历史消息（重新进页面时）
   GET  /api/agent/run/{runId}/stream         断线后重连（见 02-SSE）
```

## 前端要处理的三种「流结束」

流的结束方式有三种，**要分别处理**：

| 结束方式 | 标志 | 前端动作 |
|---|---|---|
| **正常结束** | 收到 `run_end` 事件，`status=COMPLETED` | 收尾：清「生成中」状态、刷新会话列表（标题可能变了、消息数变了） |
| **被中断** | 收到 `interrupt` 事件 | 展示「已停止 + 继续」按钮；**保留已生成的文本** |
| **异常断开** | 连接断了，还没收到 `run_end` 或 `interrupt` | **不要立刻报错**。先尝试重连（`GET /agent/run/{runId}/stream`），因为服务端可能还在跑。重连也失败才提示用户 |

**第 3 种情况是最容易做错的**：网络抖动导致流断开时，服务端往往还在正常生成答案。如果前端直接提示「请求失败」，用户会以为白等了——而实际上答案马上就出来了。

**建议的重连策略**：

```
断线 → 等 1 秒重连 → 失败 → 等 2 秒再试 → 失败 → 等 4 秒再试（最多 3 次）
三次都失败 → 提示「连接已断开」，并提供「重新连接」按钮
```

重连时**必须带上最后收到的事件 id**（`lastEventId`），否则会收到重复的事件。

## 落地记录（M6 最小版，2026-10-12；M7 工具调用，2026-10-14）

`POST /api/chat/stream` 已实现**最小可用版**（`service/ChatStreamService` + `impl/ChatStreamServiceImpl`，`ChatController#stream`），前端一字未改即可逐字收答案。

### 工具调用（M7，2026-10-14）

`ChatStreamServiceImpl` 把单次模型调用换成 `agent/AgentLoop` 的 **ReAct 循环**（跑在专用 `agentExecutor` 线程池上）：每轮带工具清单调模型，模型要求调工具就执行、把结果回填历史、再循环，直到产出最终回答或撞 `ai.agent.max-rounds` 护栏（默认 8）。**自动检索已移除**，检索改由 `searchKnowledge` 工具承担。

- 关键实现：`agent/AgentToolRegistry`（`@Tool` 扫描 + schema 生成 + 执行 + 启动校验）、`agent/AgentLoop`（循环 + 护栏 + 结果截断）、`agent/tool/{SystemTimeTool,KnowledgeTool}`、`agent/RunContext`（经 `@ToolMemoryId` 注入）。
- 工具集（**只读**）：`getCurrentTime`、`searchKnowledge`。副作用工具 / 人工确认属 M10。
- （M7 当时**不落库**：`runId` 是占位 UUID、`stepId` 是每 run 内存自增——**M8 已补**，见下。）
- 新增清单接口 `GET /tools/list`（见 [60-tools](60-tools-工具.md)）。
- 边界：客户端断开后循环仍会把剩余轮次跑完（`sink` 只是停止推流），见 [knowledge/30](../knowledge/30-工具调用.md) 坑 6。

### 运行状态落库（M8，2026-10-14）

`ChatStreamServiceImpl` 不再自己造 `runId`、不再自己发 `run_start`/`run_end`——运行状态、事件、助手消息的落库全部交由 `agent/AgentRunner`（`AgentLoop` 的落库版）。前端**行为不变**，但两处数据更实在了：

- **`runId` 变真实**：是 `agent_run.id`（不再是占位 UUID）。`run_end` 的 `messageId` = 助手消息 id。
- **里程碑事件带 `id`**：`run_start`/`tool_call`/`tool_result`/`run_end` 的 SSE `id` 就是 `agent_event.id`（数据库自增）。**`message_delta` 不带 `id`**——重连游标取最后一个里程碑事件的 `id`。
- 落库轨迹：每轮 `agent_step(LLM_CALL)`、每个工具 `agent_step(TOOL_CALL)`（**先落意图再执行**）、事件进 `agent_event`；助手消息带 `runId`。审计链可查。
- **边界**：客户端断开后循环**仍会跑完并落库**（只有推流被跳过）——取消逻辑属 M9。`truncateFrom`（截断重发）新增「该会话有未结束运行时拒绝（`50001`）」。
- 设计细节见 [knowledge/12](../knowledge/12-中断与恢复的状态机.md) ⑥。

### 已实现的事件（可直接联调）

| 事件 | 状态 | `data` 字段 | 与 [02-SSE协议规范](02-SSE协议规范.md) 事件类型表的差异 |
|---|---|---|---|
| `run_start` | ✅ | `{runId, conversationId, messageId}` | `runId` = 真实 `agent_run.id`（M8 起，不再是占位 UUID）；`messageId` = 用户消息 id |
| `message_delta` | ✅ | `{delta}` | **只有 `delta`、没有 `id`、没有 `messageId`**（不落库，不可重放） |
| `tool_call` | ✅（M7） | `{stepId, toolCallId, toolName, arguments, sideEffect}` | `stepId` = `agent_step.id`（M8 起）；`arguments` 是**解析后的对象** |
| `tool_result` | ✅（M7） | `{stepId, toolCallId, success, elapsedMs, result, truncated}` | `result` 是**解析后的对象**（非 JSON 文本会包成 `{"text": ...}`）；`elapsedMs` 是数字 |
| `run_end` | ✅ | `{runId, status:"COMPLETED", messageId, elapsedMs, rounds}` | **没有 `usage`**（token 用量未推）；M7 起新增 `elapsedMs` / `rounds` |
| `error` | ✅ | `{code, message, runId, retryable}` | 一致（模型调用失败时推） |
| `todo_update` | ❌ 未实现 | — | 无计划功能 |
| `message_snapshot` | ✅（M9） | `{messageId(可空), text}` | 重连补发的**当前全文**，**替换**语义 |
| `interrupt` | ✅（M9/M10） | `{runId, status, interruptType, pendingTool?}` | M10 人工确认时 `status=WAITING_APPROVAL`、`interruptType=APPROVAL_REQUIRED` |
| `ping` | ✅（M13） | `{}` | 约每 15 秒一次、无 `id`、不落库、不参与补发；忽略即可，也可用来判断连接存活 |

### 前端当前「可以做」与「别做」

**可以做**：发消息 → 逐字**追加**显示 → 收到 `run_end` 收尾（清「生成中」、刷新会话列表）→ 拉历史消息；渲染 `tool_call` / `tool_result`（中文名用 `GET /tools/list` 映射）；**停止 / 继续 / 断线重连 / 人工确认**（见 [40-agent](40-agent-中断与恢复.md)）；用 `ping` 做心跳 / 连接存活判断（M13）。

**别实现（依赖的事件 / 接口未落地）**：
- **50001 并发护栏**：后端**没有**校验「同一会话连点两次发送」；第二次会启动新运行并把旧运行置 `NEW_MESSAGE` 挂起——前端仍应在「生成中」**禁用发送按钮**。
- **规范化的引用来源事件**：`tool_result`（`toolName=searchKnowledge`）的 `result` 里已含检索片段/来源，可临时提取；专门的 `references` 事件仍未做。

### 其它取巧点 / 边界

- 复用现有两段短事务（`ConversationMessageService#appendMessage`）与 `MemoryBuilder.build()` 的 `List<ChatMessage>`，直接喂已装配的 `streamingChatModel` Bean。
- 建流前失败（40100 / 40000 / 40400 / 50001）必须回 **JSON 信封**而不是 event-stream —— 后端有个 406 陷阱，见 [02-SSE协议规范](02-SSE协议规范.md) 坑 2 的「后端实现注意」。
- **已接入工具调用循环**（`kbIds` 语义仍生效，但检索改为 `searchKnowledge` 工具，见 §回答里可能带工具调用）；中断 / 重连 / 续跑 / 人工确认均已接入。
- **前置条件**：环境变量 `DEEPSEEK_API_KEY` 必须设，否则 `streamingChatModel` Bean 不装配（`ChatModelConfig`），建流直接回 50001（模型未配置）。

## 前端类型定义（可直接复制）

```ts
// 请求体：/chat/stream 与 /chat/message 形状一致
export interface ChatRequest {
  conversationId: string;   // 会话 id
  content: string;          // 用户消息，1~4000 字符
  kbIds?: string[];         // 本次检索用哪些库；不传用会话关联；[] 表示本次不用 RAG
                            // ⚠️ /chat/message 目前忽略该字段（无检索）
}

// /chat/message 的响应 data 是落库后的助手消息 → 类型见 MessageVO
// （在 [20-conversation](20-conversation-会话与消息.md)）

// ---- /chat/stream 已实现的事件 payload（完整协议见 [02-SSE协议规范](02-SSE协议规范.md)）----
export interface RunStartPayload {
  runId: string;            // = agent_run.id（M8 起是真实值，不再是占位 UUID）
  conversationId: string;
  messageId: string;        // = 用户消息 id
}
export interface MessageDeltaPayload {
  delta: string;            // ⚠️ 只有 delta，没有 messageId（与 02 的规范略有出入）
}
export interface RunEndPayload {
  runId: string;
  status: 'COMPLETED';      // ⚠️ 无 usage（token 用量未推）
  messageId: string;        // = 助手消息 id
  elapsedMs: number;        // 本次运行耗时（M7 起新增）
  rounds: number;           // 工具循环跑了多少轮（M7 起新增）
}
export interface ToolCallPayload {          // M7
  stepId: string;
  toolCallId: string;
  toolName: string;
  arguments: unknown;       // 解析后的对象（一般是 {参数名: 值}）
  sideEffect: boolean;
}
export interface ToolResultPayload {        // M7
  stepId: string;
  toolCallId: string;
  success: boolean;
  elapsedMs: number;        // 数字
  result: unknown;          // 解析后的对象；非 JSON 文本为 { text: string }
  truncated: boolean;       // 结果被截断过
}
export interface ErrorPayload {
  code: number;
  message: string;
  runId: string;
  retryable: boolean;
}
```

> `todo_update` 仍未实现；`message_snapshot` / `interrupt` 已随 M9/M10 实现，`ping` 已随 M13 实现（见上方「已实现的事件」表）。`tool_call` / `tool_result` 已在 M7 实现。事件类型在 [02-SSE协议规范](02-SSE协议规范.md) 第五节有完整定义。

## 待定项

- 是否推送**检索引用来源**事件（让前端能展示「本回答参考了这两篇文档」）
- **非流式**接口（`POST /chat/message`）**已在 M4 实现为简化路径**（无检索、无 run，见上面的「发消息并一次性接收回答」）。它比流式少一大块错误处理，定位是脚本 / 冒烟测试的轻量入口——**主要交互仍是流式**
- 用户消息发送后**能否撤回**（目前只能靠「截断重发」实现类似效果）
- 单用户**并发会话数**限制（目前没有）
