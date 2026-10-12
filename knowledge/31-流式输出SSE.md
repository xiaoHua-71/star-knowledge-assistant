# 流式输出 SSE

> 状态：M6「最小版」已实现（2026-10-12）。断线回放 / 中断 / `agent_event` 持久化仍未做（属 M8）。
> 前端契约见 [frontend/02-SSE协议规范](../frontend/02-SSE协议规范.md) 与 [frontend/30-chat](../frontend/30-chat-对话与流式.md)。

## ① 这个模块治的是哪类病（通用问题定义）

大模型是**逐 token 生成**的：一次回答可能持续几秒到几十秒。如果等整段生成完再一次性返回，用户面对的是一段长时间的空白——**首字节时间（TTFT）体验差**，而且中途看不出模型有没有跑偏。

所以这个模块要解决的本质问题是：

> **把「服务端还在持续产生的输出」，以增量方式、及时地推给一个已经发完请求的浏览器；并且在这个长连接中途断开时，能恢复而不是从头再来。**

它在整条链路里的**位置**：上游是「模型 / Agent 循环」（负责产生输出），下游是「前端渲染」（负责消费输出）。它只管**传输**和**事件契约**，不管内容从哪来。

### 它的边界

| 归它管 | 不归它管 |
|---|---|
| 服务端→客户端的增量传输（选型、帧格式、事件类型） | 模型怎么生成（那是对话模型 / Agent 循环） |
| 一次运行的事件契约（有哪些事件、什么字段、什么顺序） | 运行状态的持久化与续跑（那是 [12-中断与恢复的状态机](12-中断与恢复的状态机.md)） |
| 断线后的事件补发（回放已持久化的事件） | 用户可见的消息落库（那是 [40-会话与消息管理](40-会话与消息管理.md)） |

### 三类典型失败模式

1. **建流失败与流内失败被混淆。** 请求可能「还没建立流」就失败（未登录 / 参数错），也可能「流已建立之后」才失败（模型报错）。前者必须是**普通 JSON 响应**，后者必须是**流里的 error 事件**。混在一起，前端要么把 JSON 当流解析（卡死），要么把 error 当 HTTP 错误（丢掉已生成的内容）。
2. **重连后文本重复或断掉。** 断线补发如果设计不当：重复推送已收到的事件 → 文本重复；漏掉增量 → 文本断掉。这是这类协议最常见的 bug。
3. **增量不落库导致无法回放。** 逐 token 的 delta 如果持久化，事件表会被撑爆；但完全不存，重连时又补不出来。二者的取舍是这一块的核心难点（见 ⑤ 坑 3）。

---

## ② LangChain4j 官方怎么做

官方文档：<https://docs.langchain4j.dev/tutorials/response-streaming/>（本项目用的是 `langchain4j-core 1.20.0`）

**核心类**（均在 `dev.langchain4j:langchain4j-core`）：

| 类 / 接口 | 作用 |
|---|---|
| `dev.langchain4j.model.chat.StreamingChatModel` | 流式模型**接口** |
| `dev.langchain4j.model.chat.response.StreamingChatResponseHandler` | 回调接口 |
| `dev.langchain4j.model.chat.response.ChatResponse` | `onCompleteResponse` 的入参 |

`StreamingChatModel#chat(List<ChatMessage>, StreamingChatResponseHandler)` 注册回调，三件套：

- `onPartialResponse(String)` —— 逐段文本（**逐 token 的落地形式**）
- `onCompleteResponse(ChatResponse)` —— 结束；取 `aiMessage().text()` 与 `metadata().tokenUsage().totalTokenCount()`
- `onError(Throwable)` —— 出错

> ⚠️ **类名在 1.20 改过**：旧名 `StreamingChatLanguageModel` 现在是 `StreamingChatModel`。查旧博客时注意对应关系。这段是升级时的改动清单。

**官方给什么**：模型侧的流式——你注册 handler，模型逐段回调。

**官方不给什么**：把回调转成 HTTP 上的 SSE、定义事件类型与字段、区分建流失败与流内失败、断线回放——**这些都要自己做**。官方另有 `langchain4j-reactor`（把流包成 `Publisher`），本项目引了依赖但**未用**。

> 「官方不做 SSE 传输」这句只对 1.20.0 成立，升级时复核是否出现新的 web 传输模块。

---

## ③ 本项目为什么这么落地

- **传输用 Spring MVC 的 `SseEmitter`，不用 WebFlux。** 本项目整体是 Spring MVC（非响应式），`CorsConfig` 等在 MVC 栈上；只为流式引入 WebFlux 不划算。`SseEmitter` 天然把请求保持为异步、可从任意线程 `send`。
- **模型侧用回调式** `StreamingChatModel#chat(List<ChatMessage>, handler)`，与 `ai/DeepSeekChatModelTest` 里已验证的路径一致（回调用在 HTTP 客户端线程，`chat()` 异步非阻塞、立即返回）。
- **不引入额外的执行器线程。** 控制器线程发 `run_start` 后调用 `model.chat(...)` 即返回；回调线程发 delta 与收尾。`SseEmitter` 的 `send`/`complete` 自带同步，且 `run_start` 在 `initialize()` 之前发送会被缓冲并按序回放，顺序有保证。
- **超时必须显式**：`new SseEmitter(300_000L)`。不传超时参数会退回容器默认（Tomcat ≈ 30s），长回答会被中途掐断。
- **事件契约自研**，以 [frontend/02](../frontend/02-SSE协议规范.md) 为准（那是前端唯一的契约来源）。
- **M6 先做最小可用版**：不建 `agent_run`、不做断线回放 / 中断 / 续跑（那些是 [12](12-中断与恢复的状态机.md) / M8）。事件 `id` 先用**内存自增**，不入库。

**落地位置**：`service/ChatStreamService` + `service/impl/ChatStreamServiceImpl`；路由 `controller/ChatController#stream`。复用 `MemoryBuilder#build`（`List<ChatMessage>` 直接喂流式模型）与 `ConversationMessageService#appendMessage` 的两段短事务（模型调用在事务之外）。

**关于 `runId`**：当前是占位 UUID。真正的 `runId` 来自 `agent_run` 表（M8），前端目前只用它做「以后可能有的停止 / 继续」，而那两个接口尚不存在，所以占位没有副作用。

---

## ④ 数据结构与流程时序

### 帧格式

一个事件 = 若干字段行 + 一个空行，以 `\n\n` 分块：

```
id:<字符串>
event:<事件名>
data:<单行 JSON>

```

`id` 与所有 `Long` 一样在 JSON 里是**字符串**（见 [frontend/01](../frontend/01-通用约定.md)）；`data` 是单行 JSON（换行已转义）。**流内数据不走 `BaseResponse` 信封**，唯一带 `code` 的是 `error` 事件。

### 本项目当前支持的事件（子集）

| `event` | `data` 字段 | 说明 |
|---|---|---|
| `run_start` | `runId`, `conversationId`, `messageId` | 建流成功后**第一条**；`messageId` = 用户消息 id |
| `message_delta` | `delta` | 逐段回答，前端**追加**；**不落库** |
| `run_end` | `runId`, `status`, `messageId` | 正常结束；`messageId` = 助手消息 id |
| `error` | `code`, `message`, `runId`, `retryable` | 流已建立后失败；协议里唯一带 `code` 的事件 |

**未实现**：`message_snapshot`（重连才用）、`ping`（心跳）、`todo_update` / `tool_call` / `tool_result` / `interrupt`（属工具调用 / 中断模块）。

### 时序

1. **控制器线程**：参数校验 → `ConversationService#getOwnedOrThrow`（会话归属）→ 取 `streamingChatModel`（缺则 50001）→ **事务 #1** 落用户消息 → `MemoryBuilder#build` 重建 prompt。
2. 建 `SseEmitter(300_000L)`，`send` `run_start`（此时未 `initialize`，事件被缓冲，返回后按序回放）。
3. `model.chat(prompt, handler)`（异步，立即返回 emitter）。
4. 回调线程 `onPartialResponse` → `send` `message_delta`。
5. `onCompleteResponse` → **事务 #2** 落助手消息 → `send` `run_end` → `complete()`；或 `onError` → `send` `error` → `complete()`。

> 与非流式 `chat` 同构：**事务绝不跨越模型调用**，一次对话仍是两个短事务。

### 涉及的表

| 表 | 用途 | 状态 |
|---|---|---|
| `conversation_message` | 用户 / 助手消息落库，见 [40](40-会话与消息管理.md) | 已存在 |
| `agent_event` | SSE 事件游标（自增 `id` 当 `lastEventId`），见 [10-数据模型总览](10-数据模型总览.md) | **未建**，M8 |

---

## ⑤ 边界、已知限制、踩过的坑

### 没做什么

- **断线回放 / 重连**（`GET /agent/run/{runId}/stream`）、**中断 / 续跑**、**事件持久化**（`agent_event`）——都属 M8。
- `message_snapshot`、`ping` 心跳未实现。
- 检索未接（`kbIds` 被忽略，与非流式一致）。
- 事件 `id` 是**进程内自增**，重启即重置、不可续传。前端目前也不做重连，所以无影响。

### 坑 1：建流失败回 JSON 会撞 406（已踩，已规避）

- **现象**：未登录 / 参数错时，前端拿到的是 **406 空响应**，而不是 `{"code":40100,...}`；`sseClient` 解析失败，页面显示兜底文案。
- **原因**：控制器方法声明返回 `SseEmitter`，而前端带 `Accept: text/event-stream`。建流前抛出的异常经全局 `@RestControllerAdvice` 返回普通 JSON，会走 **Accept 内容协商**，无匹配类型 → 406。
- **规避**：在 `ChatController` 加**控制器局部** `@ExceptionHandler`，返回 `ResponseEntity.status(200).contentType(MediaType.APPLICATION_JSON).body(...)`。Spring 的 `AbstractMessageConverterMethodProcessor#writeWithMessageConverters` 在响应 `Content-Type` 已具体时**跳过** Accept 协商。
- **验证注意**：验证这条路径必须显式带 `Accept: text/event-stream`——`curl` 默认 `Accept: */*` 会协商出 JSON，把 406 掩盖成假阳性。详见 [frontend/02](../frontend/02-SSE协议规范.md) 坑 2。

### 坑 2：`message_delta` vs `message_snapshot` 是「追加」vs「替换」

- **现象**：重连后文本出现大段重复。
- **原因**：把 `message_snapshot`（全量快照）当成 delta 追加。
- **规避**：`message_delta` 追加、`message_snapshot` 替换。当前未实现 snapshot（无重连），将来实现时务必按此语义。

### 坑 3（设计预告，未实现）：delta 不落库 ⇒ 重连靠 snapshot 补偿

逐 token 的 delta **不写** `agent_event`（否则 500 token = 500 行，纯粹冗余；完整文本已在 `conversation_message.content`）。代价是重连**补不出漏掉的 delta**——所以重连流程是：先补 `id > lastEventId` 的里程碑事件 → 再发一条 `message_snapshot`（当前已生成的部分文本）→ 然后继续推 delta。见 [frontend/02](../frontend/02-SSE协议规范.md)、[12-中断与恢复的状态机](12-中断与恢复的状态机.md)。
