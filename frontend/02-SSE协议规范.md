# SSE 协议规范（流式对话）

> ✅ **状态（2026-10-16，M9 完成）：** `run_start` / `message_delta` / `tool_call` / `tool_result` / `run_end` / `error` 已实现；
> **M8** 落地事件持久化（里程碑事件写入 `agent_event`、其自增 id 即 SSE 的 `id`，`message_delta` 不落库、不带 `id`）；
> **M9** 落地 `interrupt`（挂起）、`message_snapshot`（重连全文替换）、重连补发（`GET /agent/run/{runId}/stream?lastEventId=`）、
> 停止（`POST /agent/run/{runId}/interrupt`）与续跑（`POST /agent/run/{runId}/resume`）。见 [40-agent-中断与恢复](40-agent-中断与恢复.md)、[knowledge/12](../knowledge/12-中断与恢复的状态机.md) ⑦。
> 人工确认（`WAITING_APPROVAL` / `approve`）仍未实现，属 M10。**

## 一、为什么是 SSE，不是 WebSocket

| | SSE | WebSocket |
|---|---|---|
| 方向 | **单向**（服务端 → 客户端） | 双向 |
| 协议 | 就是 HTTP，`text/event-stream` | 独立协议，需要升级握手 |
| 断线重连 | **协议自带**（`Last-Event-ID`） | 要自己实现 |
| 代理/网关兼容 | 好（就是一个长连接 HTTP） | 有些代理会拦 |
| 复杂度 | 低 | 高 |

**选 SSE 的决定因素**：对话场景的数据流是**单向的**——用户一次性把消息发上去，之后全是服务端往下推。用户「发消息」这个动作走普通 POST 就够了，不需要 WebSocket 的双向通道。

而「断线重连」是这个项目明确需要的功能（回答可能持续几十秒，网络抖动很常见），SSE 协议里 `Last-Event-ID` 这个原生机制正好对上。

## 二、⚠️ 三个必须先知道的坑

### 坑 1：不能用浏览器原生的 `EventSource`

`EventSource` 有两个硬限制：

1. **只能发 GET，不能发 POST** —— 但「发消息」需要 POST 请求体
2. **不能自定义请求头** —— 也没法控制 `credentials`（虽然它其实会带 cookie，但不可控）

**所以必须用 `fetch` 手动处理流**（或用 `@microsoft/fetch-event-source` 这类封装）。

### 坑 2（致命）：建流失败时返回的是**普通 JSON**，不是 SSE

**这是整个流式对接里最容易翻车的地方。**

流程是这样的：前端发 `POST /api/chat/stream` → 后端先做鉴权和参数校验 → **通过了才**把响应的 `Content-Type` 设为 `text/event-stream` 并开始推事件。

也就是说：

| 情况 | 响应 `Content-Type` | 响应体 |
|---|---|---|
| 建流成功 | `text/event-stream` | SSE 事件流 |
| **未登录** | `application/json` | `{"code":40100,"data":null,"message":"未登录"}` |
| 参数错误 | `application/json` | `{"code":40000,...}` |
| 会话不存在 | `application/json` | `{"code":40400,...}` |

**前端必须先检查 `Content-Type`，不能直接当成流来解析。**

```js
const res = await fetch('/api/chat/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  credentials: 'include',
  body: JSON.stringify({ conversationId, content }),
});

// ★ 第一步：判断是不是建流失败了
const contentType = res.headers.get('content-type') || '';
if (contentType.includes('application/json')) {
  const body = await res.json();          // 这里不是 SSE，是普通信封
  if (body.code === 40100) {
    router.push('/login');
  } else {
    toast(body.message);
  }
  return;                                  // 不要继续往下当流处理
}

// 第二步：确认是流
if (!contentType.includes('text/event-stream')) {
  toast('响应格式异常');
  return;
}

// 第三步：开始读流
const reader = res.body.getReader();
// ...
```

**没有这个判断的后果**：`reader.read()` 会拿到一段 JSON 文本，你的 SSE 解析器解析不出任何事件，表现为「连接建立了但什么都没发生」——非常难排查。

#### 后端实现注意（M6 落地时踩到的 406 陷阱）

这个「建流失败回 JSON」的要求在后端有个**隐蔽陷阱**：控制器方法声明返回 `SseEmitter` 时，如果建流前的校验失败、异常经全局 `@RestControllerAdvice` 返回普通 JSON 信封，会走 **Accept 内容协商**——而前端带的是 `Accept: text/event-stream`，`BaseResponse` 产出不了这个类型 → **HTTP 406，响应体不是信封**（前端拿到的是空/错误页，`response.json()` 解析失败）。

修法：在该控制器加**控制器局部** `@ExceptionHandler`，返回 `ResponseEntity.status(200).contentType(MediaType.APPLICATION_JSON).body(...)`。依据 Spring 的 `AbstractMessageConverterMethodProcessor#writeWithMessageConverters`：响应 `Content-Type` 已是具体值时**跳过** Accept 协商，故不会 406。

⚠️ **验证这个路径时必须显式带 `Accept: text/event-stream`**——`curl` 默认 `Accept: */*` 会协商出 JSON，把 406 掩盖掉，得到**假阳性**。

### 坑 3：流**内部**的数据不走 `BaseResponse` 信封

建成流之后，每个事件推的就是事件自己的数据，**没有 `{code, data, message}` 这层包装**。

```js
// ❌ 错误：以为流里也是信封
const body = JSON.parse(event.data);
if (body.code !== 0) { ... }   // body.code 永远是 undefined

// ✅ 正确：流里的事件数据就是业务数据本身
const payload = JSON.parse(event.data);
// payload 直接就是 {runId, status, ...}
```

**唯一需要判断 `code` 的地方是 `error` 事件**（它的事件体里带 `code` 字段），见第四节。

## 三、事件类型

### 事件线格式

标准 SSE 格式，一个事件是一个「字段行 + 空行」的块：

```
id: 9007199254741001
event: run_start
data: {"runId":"100","conversationId":"7","messageId":"55"}

```

- **`id`**：事件游标，**是字符串**（后端的 `Long` 都输出成字符串，见 [01-通用约定](01-通用约定.md)）。重连时要把它带回去。⚠️ **只有里程碑事件带 `id`**（=`agent_event.id`：`run_start`/`tool_call`/`tool_result`/`run_end`/`error`）；**`message_delta` 不带 `id`**（它不落库）。重连游标应取**最后一个里程碑事件**的 `id`
- **`event`**：事件类型，见下表
- **`data`**：JSON，**单行**（换行已被转义为 `\n`，不需要处理跨行拼接）

### 事件类型表

| `event` | 什么时候发 | `data` 内容 | 前端该做什么 |
|---|---|---|---|
| `run_start` | 流建立后立刻 | `{runId, conversationId, messageId}` | 记录 `runId`（停止/继续都要用它）；进入「生成中」状态 |
| `todo_update` | 模型更新计划时 | `{planVersion, items:[{itemIndex, title, status}]}` | 更新计划列表展示。**这是全量快照**，不是增量，直接替换即可 |
| `tool_call` | 开始调工具时 | `{stepId, toolCallId, toolName, arguments, sideEffect}` | 展示「正在调用 xxx」。`sideEffect=true` 时提示用户这个操作会改数据 |
| `tool_result` | 工具返回时 | `{stepId, toolCallId, success, elapsedMs, result, truncated}` | 展示工具结果摘要（`truncated=true` 表示结果被截断过） |
| `message_delta` | 模型每产出一段文本 | `{messageId, delta}` | **追加**到当前回答的末尾 |
| `message_snapshot` | 断线重连后 | `{messageId, text}` | **替换**当前回答的全文（不是追加！） |
| `interrupt` | 运行挂起 | `{runId, status, interruptType, pendingTool?}` | 见第五节 |
| `run_end` | 运行正常结束 | `{runId, status, messageId, usage, elapsedMs}` | 收尾：关闭连接、刷新会话列表、清「生成中」状态 |
| `error` | 流中断（建流之后出错） | `{code, message, runId, retryable}` | 见下文 |
| `ping` | 心跳，约每 15 秒 | `{}` | 忽略（用来保活，也用来确认连接还活着） |

### 关于 `message_delta` 和 `message_snapshot`

**这是流式设计里唯一的「必须理解原理才能正确处理」的地方。**

后端**不持久化**逐 token 的 `message_delta`（一次回答 500 个 token 就是 500 条数据库记录，而这些数据除了推给当前连接之外没有别的用途）。

带来的后果是：**断线重连时，后端无法把「漏掉的 delta」补发给你**。

所以协议里加了一个 `message_snapshot` 事件：

| 场景 | 事件序列 |
|---|---|
| 正常 | `message_delta` × N → `run_end` |
| 断线重连 | `...` →（重连）→ **`message_snapshot`（当前已生成的全文）** → `message_delta` × 剩余 → `run_end` |

**前端必须区分这两个事件的语义**：

```js
case 'message_delta':
  currentText += payload.delta;          // 追加
  break;
case 'message_snapshot':
  currentText = payload.text;            // 替换（重新对齐）
  break;
```

**把 `message_snapshot` 当成 `delta` 会怎样**：重连后文本会出现重复的一大段（比如「好的，我帮你查一下好的，我帮你查一下」）。这是这类协议最常见的 bug。

### `error` 事件与「建流失败」的区别

这是两个不同的阶段：

| | 建流失败（坑 2） | `error` 事件 |
|---|---|---|
| 时机 | 还没建立流 | 流已经建立，中途出错 |
| 形式 | HTTP 响应体是 JSON 信封 | SSE 事件 |
| `code` 在哪 | 响应体的 `code` | 事件 `data` 里的 `code` |
| 前端处理 | 按信封处理（跳登录页等） | 展示错误 + 保留已生成的内容 |

```js
case 'error':
  // 注意：HTTP 已经是 200 了，这里只能通过事件判断
  showError(payload.message);
  if (payload.retryable) showRetryButton();
  // 已生成的文本保留着，不要清空
  break;
```

`retryable: true` 表示这是可重试的临时错误（如模型限流、超时）。**重试的正确做法是 `POST /api/agent/run/{runId}/resume`，不是重新发一条消息**——后者会创建一次全新的运行，把已经花掉的 token 浪费掉。

## 四、端点

### 4.1 发消息并建立流

```
POST /api/chat/stream
Content-Type: application/json
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `conversationId` | string | 是 | 会话 id。**新会话需要先调创建接口拿 id** |
| `content` | string | 是 | 用户消息内容，1~4000 字符 |
| `kbIds` | string[] | 否 | 本次对话要检索的知识库 id 列表。不传则用会话上已关联的 |

响应：`text/event-stream`（或失败时的 JSON 信封，见坑 2）

### 4.2 重连

```
GET /api/agent/run/{runId}/stream?lastEventId={最后收到的事件id}
```

- 后端把 `id > lastEventId` 的**已持久化事件**按顺序补发
- 然后补发一条 `message_snapshot`（当前已生成的部分文本）
- 然后继续推新事件

**注意这是 `GET`**，所以它**可以用原生 `EventSource`**（不需要请求体、不需要自定义头）。这也是唯一可以用 `EventSource` 的接口。

**补发窗口有限**（默认最近 1000 条事件）。超过窗口会返回 `40400`，此时前端应该**放弃这条流，改为重新拉取会话的消息历史**（`GET /api/conversation/{id}/messages`）来恢复界面——因为事件回放已经不是恢复界面的最佳途径了。

### 4.3 停止

```
POST /api/agent/run/{runId}/interrupt
```

请求体：无（`runId` 在路径里）

响应：普通 JSON 信封，`data: true`

**⚠️ 关键语义：这个接口返回成功 ≠ 运行已经停了。**

它是「把停止请求登记下来」，后端会在**下一个安全点**真正挂起运行。中间可能有最多一轮 LLM 调用的延迟。

**前端的正确做法**：

1. 用户点「停止」→ 调这个接口 → **UI 上立刻把按钮切成「已停止」状态**（乐观更新），同时**停止渲染后续的 `message_delta`**
2. 继续读流，**等服务端推来 `interrupt` 事件**
3. 收到 `interrupt` 事件后，关闭连接，展示「已停止 / 点击继续」的按钮

**不要以「接口返回成功」或「连接关闭」作为停止完成的标志**——前者太早，后者可能是网络问题。**只有 `interrupt` 事件是权威信号。**

### 4.4 继续

```
POST /api/agent/run/{runId}/resume
```

响应：普通 JSON 信封，`data` 是运行状态对象

**调用后需要重新建立流**：

```js
await api.post(`/api/agent/run/${runId}/resume`);
// 然后重新连流
const es = new EventSource(`/api/agent/run/${runId}/stream`);  // 不带 lastEventId，从头补
```

### 4.5 人工确认

副作用工具（比如「建工单」）执行前需要用户确认时，会收到 `interrupt` 事件且 `interruptType = "APPROVAL_REQUIRED"`，同时 `data.pendingTool` 里有待确认的工具信息。

```
POST /api/agent/run/{runId}/approve
Content-Type: application/json
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `approve` | boolean | 是 | `true` 同意执行，`false` 拒绝 |
| `reason` | string | 否 | 拒绝原因（`approve=false` 时可用） |

响应：普通 JSON 信封，`data` 是运行状态对象。**之后同样需要重新建流**。

## 五、完整事件序列示例

以下是一段真实场景的事件流（省略了心跳）：

```
id: 9001
event: run_start
data: {"runId":"100","conversationId":"7","messageId":"55"}

id: 9002
event: todo_update
data: {"planVersion":1,"items":[{"itemIndex":1,"title":"查询年假政策","status":"IN_PROGRESS"},{"itemIndex":2,"title":"创建工单","status":"PENDING"},{"itemIndex":3,"title":"汇总回复","status":"PENDING"}]}

id: 9003
event: tool_call
data: {"stepId":"2","toolCallId":"call_a","toolName":"searchKnowledge","arguments":{"query":"年假政策"},"sideEffect":false}

id: 9005
event: tool_result
data: {"stepId":"2","toolCallId":"call_a","success":true,"elapsedMs":286,"result":{"hits":[{"docId":"12","docName":"员工手册.pdf","score":0.83}]},"truncated":false}

id: 9006
event: message_delta
data: {"messageId":"56","delta":"根据"}

id: 9007
event: message_delta
data: {"messageId":"56","delta":"《员工手册》第 4 章，"}

... 用户在此刻点了「停止」...

id: 9010
event: interrupt
data: {"runId":"100","status":"SUSPENDED","interruptType":"USER_STOP"}

... 用户点了「继续」，前端重新建流 ...

id: 9011
event: run_start
data: {"runId":"100","conversationId":"7"}

id: 9012
event: message_snapshot
data: {"messageId":"56","text":"根据《员工手册》第 4 章，"}

id: 9013
event: message_delta
data: {"messageId":"56","delta":"员工每年可享受"}

... 中间模型要求调 createTicket，需要人工确认 ...

id: 9020
event: interrupt
data: {"runId":"100","status":"WAITING_APPROVAL","interruptType":"APPROVAL_REQUIRED","pendingTool":{"toolName":"createTicket","arguments":{"title":"年假政策咨询","assignee":"hr"}}}

... 用户点「同意」，前端调 approve 后重新建流 ...

id: 9021
event: tool_call
data: {"stepId":"4","toolCallId":"call_b","toolName":"createTicket","arguments":{"title":"年假政策咨询"},"sideEffect":true}

id: 9023
event: tool_result
data: {"stepId":"4","toolCallId":"call_b","success":true,"elapsedMs":412,"result":{"ticketId":"T-8891"}}

id: 9024
event: message_delta
data: {"messageId":"56","delta":"已为你建好工单 T-8891。"}

id: 9025
event: run_end
data: {"runId":"100","status":"COMPLETED","messageId":"56","usage":{"tokenIn":1234,"tokenOut":567},"elapsedMs":8420}
```

## 六、`interruptType` 一览

收到 `interrupt` 事件时，`data.interruptType` 告诉你为什么停了：

| 值 | 含义 | 前端表现 |
|---|---|---|
| `USER_STOP` | 用户自己点的停止 | 显示「已停止」+「继续」按钮 |
| `NEW_MESSAGE` | 用户在运行中又发了一条消息 | 提示「上一条已停止」，并开始处理新消息 |
| `SERVER_RESTART` | 后端重启了 | 显示「服务重启导致中断」+「继续」按钮 |
| `APPROVAL_REQUIRED` | 有副作用操作需要确认 | 弹出确认框，展示 `pendingTool` 的内容，调 `POST /approve` |

## 七、前端参考实现

```js
/**
 * 建立一次流式对话
 * @returns {Function} 关闭连接的函数
 */
export function chatStream({ conversationId, content, kbIds }, handlers) {
  const controller = new AbortController();
  let lastEventId = null;

  (async () => {
    const res = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ conversationId, content, kbIds }),
      signal: controller.signal,
    });

    // ★ 坑 2：建流失败返回的是 JSON
    const ct = res.headers.get('content-type') || '';
    if (!ct.includes('text/event-stream')) {
      const body = await res.json().catch(() => ({}));
      handlers.onBuildFailed?.(body);   // {code, data, message}
      return;
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // SSE 以空行分隔事件块
      let idx;
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        dispatch(block, handlers, (id) => { lastEventId = id; });
      }
    }
    // ★ 坑 3：流里的事件数据不是信封，直接就是业务数据
  })().catch((e) => {
    if (e.name !== 'AbortError') handlers.onNetworkError?.(e);
  });

  return () => controller.abort();
}

function dispatch(block, handlers, setId) {
  let event = 'message';
  let data = '';
  for (const line of block.split('\n')) {
    if (line.startsWith(':')) continue;              // 注释行 / 心跳
    if (line.startsWith('id:')) setId(line.slice(3).trim());
    else if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data += line.slice(5).trim();
  }
  if (!data) return;
  let payload;
  try { payload = JSON.parse(data); } catch { return; }
  handlers.onEvent?.(event, payload);
}
```

**几个实现要点**：

1. **必须用 `TextDecoder` 的 `stream: true`**，否则多字节的中文字符在 chunk 边界处会被切坏（出现乱码）。这是中文场景下比英文更常见的 bug
2. **必须缓冲不完整的块**（`buffer`）。网络返回的 chunk 边界和事件边界无关，一个事件可能被切成两个 chunk
3. **用 `AbortController` 关闭连接**。用户离开页面、点停止、组件卸载时都要调
4. **`event:` 字段可能缺失**（协议默认是 `message`），解析要容错

### 备选方案：`@microsoft/fetch-event-source`

如果不想手写解析，这个库提供了基于 `fetch` 的 SSE 客户端，支持 POST 和自定义头：

```js
import { fetchEventSource } from '@microsoft/fetch-event-source';

await fetchEventSource('/api/chat/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  credentials: 'include',
  body: JSON.stringify({ conversationId, content }),
  onmessage(ev) {
    // ev.event / ev.data / ev.id
  },
  onerror(err) { /* 抛错则停止重连 */ },
});
```

**注意它的自动重连行为**：默认会在出错后自动重试，而这个场景下重试可能会触发重复的对话请求。**要用 `onerror` 里 `throw` 来关掉自动重连**，或者自己控制。

## 八、常见问题排查

| 现象 | 最可能的原因 |
|---|---|
| 连接建立了，但什么都没收到 | 建流失败返回了 JSON，被当成了流（坑 2） |
| 收到事件但解析不出内容 | 流里没有信封，代码里还在判断 `data.code`（坑 3） |
| 中文字符偶尔乱码 | `TextDecoder` 没加 `{ stream: true }` |
| 偶尔丢事件 | 没有缓冲不完整的 chunk，跨 chunk 的事件被丢了 |
| 重连后文本出现重复的一大段 | 把 `message_snapshot` 当成 `delta` 追加了 |
| 点了停止，界面一直卡在「生成中」 | 用接口返回值判断停止，而不是等 `interrupt` 事件 |
| 重连后 Agent 从头又跑了一遍 | 把「重连」（`GET .../stream`）和「继续」（`POST .../resume`）搞混了，见 [40-agent-中断与恢复](40-agent-中断与恢复.md) |
| 一直不结束、也不报错 | 检查 `maxRounds` 护栏是否生效；正常运行时每轮都会有事件，长时间完全无事件说明卡住了 |
