# chat · 对话与流式

> ✅ **状态：`POST /chat/message`（非流式）M4 已实现；`POST /chat/stream`（流式）M6「最小版」已实现（2026-10-12，见文末「落地记录」）。断线回放 / 中断 / run 持久化仍未实现（属 M8）。**

## 这个模块只有一个核心接口

对话是**流式**的，所以这个模块的主体是 `POST /api/chat/stream`。

**协议细节（事件类型、重连、解析代码）全部在 [02-SSE协议规范](02-SSE协议规范.md) 里，本文不重复。** 这里只讲接口本身的入参出参和业务行为。

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

### 检索是自动的

如果会话关联了知识库，后端会在调模型之前自动检索，并把检索到的片段拼进 prompt。**这些过程前端看不到**（不推事件）——用户只看到答案。

**为什么不做成可见的**：检索是「怎么找到答案」的过程，不是用户关心的内容。用户关心的是答案本身和它引用了什么。

**但如果你想在界面上展示引用来源**：目前协议里没有这个事件。可以加一个 `references` 事件（列出用到的文档名和片段），这是**待定功能**，需要的话提出来。

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

## 落地记录（M6 最小版，2026-10-12）

`POST /api/chat/stream` 已实现**最小可用版**（`service/ChatStreamService` + `impl/ChatStreamServiceImpl`，`ChatController#stream`），前端一字未改即可逐字收答案。

### 已实现的事件（可直接联调）

| 事件 | 状态 | `data` 字段 | 与 [02-SSE协议规范](02-SSE协议规范.md) 事件类型表的差异 |
|---|---|---|---|
| `run_start` | ✅ | `{runId, conversationId, messageId}` | `runId` 是**占位 UUID**（无 `agent_run` 表）；`messageId` = 用户消息 id |
| `message_delta` | ✅ | `{delta}` | **只有 `delta`，没有 `messageId`**（规范里是 `{messageId, delta}`） |
| `run_end` | ✅ | `{runId, status:"COMPLETED", messageId}` | **没有 `usage` / `elapsedMs`**；`messageId` = 助手消息 id |
| `error` | ✅ | `{code, message, runId, retryable}` | 一致（模型调用失败时推） |
| `todo_update` / `tool_call` / `tool_result` | ❌ 未实现 | — | 无 Agent、无工具 |
| `message_snapshot` | ❌ 未实现 | — | 无持久化事件，不可续传 |
| `interrupt` | ❌ 未实现 | — | 也无 `interrupt` / `resume` / `approve` 接口 |
| `ping` | ❌ 未实现 | — | 无心跳保活 |

### 前端当前「可以做」与「别做」

**可以做**：发消息 → 逐字**追加**显示 → 收到 `run_end` 收尾（清「生成中」、刷新会话列表）→ 拉历史消息。这条主链路端到端可联调。

**别实现（依赖的事件 / 接口未落地，属 M8）**：
- **停止 / 继续 / 人工确认**：无 `interrupt` 事件，也没有 `POST /agent/run/{runId}/interrupt|resume|approve`。
- **断线重连**：事件 `id` 是内存自增、不落库、不可续传，`GET /agent/run/{runId}/stream` 不存在。断线只能**重新拉历史消息**恢复界面。
- **基于 `ping` 的心跳 / 连接存活判断**：无 `ping`。
- **50001 并发护栏**：后端**没有**校验「该会话已有正在进行的运行」，同一会话连点两次发送不会拦——前端需自己在「生成中」**禁用发送按钮**。
- **引用来源展示**：无检索、无 `references` 事件。

### 其它取巧点 / 边界

- 复用现有两段短事务（`ConversationMessageService#appendMessage`）与 `MemoryBuilder.build()` 的 `List<ChatMessage>`，直接喂已装配的 `streamingChatModel` Bean。
- 建流前失败（40100 / 40000 / 40400 / 50001）必须回 **JSON 信封**而不是 event-stream —— 后端有个 406 陷阱，见 [02-SSE协议规范](02-SSE协议规范.md) 坑 2 的「后端实现注意」。
- 无检索（`kbIds` 忽略）、无中断 / 重连 / 续跑。
- **前置条件**：环境变量 `DEEPSEEK_API_KEY` 必须设，否则 `streamingChatModel` Bean 不装配（`ChatModelConfig`），建流直接回 50001（模型未配置）。

## 待定项

- 是否推送**检索引用来源**事件（让前端能展示「本回答参考了这两篇文档」）
- **非流式**接口（`POST /chat/message`）**已在 M4 实现为简化路径**（无检索、无 run，见上面的「发消息并一次性接收回答」）。它比流式少一大块错误处理，定位是脚本 / 冒烟测试的轻量入口——**主要交互仍是流式**
- 用户消息发送后**能否撤回**（目前只能靠「截断重发」实现类似效果）
- 单用户**并发会话数**限制（目前没有）
