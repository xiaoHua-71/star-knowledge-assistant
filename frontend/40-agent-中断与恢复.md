# agent · 中断与恢复

> ⬜ **状态：设计已定稿，接口尚未实现。**

这是这个项目最有特色的功能：**回答生成到一半可以停下来，之后接着继续。** 内部实现见 [knowledge/12-中断与恢复的状态机](../knowledge/12-中断与恢复的状态机.md)；这里只讲前端怎么用。

## ⚠️ 先分清两个完全不同的动作

**这是对接这个模块最容易出错的地方。**

| | **重连** | **继续（续跑）** |
|---|---|---|
| 什么时候用 | 网络断了 / 用户刷新了页面，但**服务端还在跑**（或已经跑完） | 运行**已经挂起**了（收到过 `interrupt` 事件），用户想接着干 |
| 接口 | `GET /api/agent/run/{runId}/stream?lastEventId=` | `POST /api/agent/run/{runId}/resume` |
| 会重复执行工具吗 | **不会**。只是补发漏掉的事件 | 会继续执行**还没执行**的步骤（已经执行过的会重放结果，不会重复执行） |
| 会改变运行状态吗 | **不会**。纯粹是读 | 会把状态从 `SUSPENDED` 改成 `RUNNING` |
| 幂等吗 | 是，可以随便调 | 否，调一次就重启一次执行 |

**搞混的后果**：把「重连」写成「继续」，会导致用户每次刷新页面都触发一次新的执行——更糟的是工具（比如建工单）可能被重复调用。

## 数据结构

### `AgentRunVO`

```ts
interface AgentRunVO {
  id: string;                    // 运行 id
  conversationId: string;
  status: 'PENDING' | 'RUNNING' | 'SUSPENDED'
        | 'WAITING_APPROVAL' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  interruptType: 'USER_STOP' | 'NEW_MESSAGE'
               | 'SERVER_RESTART' | 'APPROVAL_REQUIRED' | null;
  stepNo: number;                // 当前执行到第几轮
  maxRounds: number;             // 最大轮次上限
  inputMessageId: string | null; // 触发这次运行的用户消息
  outputMessageId: string | null;// 最终回答的消息 id（仅 COMPLETED 时非空）
  pendingTool: PendingTool | null;   // 仅 WAITING_APPROVAL 时非空
  errorMsg: string | null;
  tokenIn: number;
  tokenOut: number;
  startTime: string | null;
  endTime: string | null;
  createTime: string;
}

interface PendingTool {
  stepId: string;
  toolName: string;
  toolDescription: string;       // 工具的说明，可以直接展示给用户
  arguments: Record<string, unknown>;  // 待执行的参数，展示给用户确认
}
```

### 状态的含义

| `status` | 含义 | 前端表现 |
|---|---|---|
| `PENDING` | 刚创建，还没开始 | 短暂状态，前端基本看不到 |
| `RUNNING` | 正在执行 | 「生成中」，显示停止按钮 |
| `SUSPENDED` | 已挂起，可以继续 | 显示「已停止」+「继续」按钮 |
| `WAITING_APPROVAL` | 等用户确认一个有副作用的操作 | 弹确认框，展示 `pendingTool` |
| `COMPLETED` | 正常结束 | 正常展示回答 |
| `FAILED` | 出错了 | 展示 `errorMsg` + 重试按钮 |
| `CANCELLED` | 被放弃或超时回收 | 展示「已取消」 |

## 接口

### 1. 停止

```
POST /api/agent/run/{runId}/interrupt
```

无请求体。响应：`data` 是 `true`

**⚠️ 返回成功 ≠ 已经停了。** 这个接口只是「登记停止请求」，后端会在**下一个安全点**真正挂起。中间可能有最多一轮 LLM 调用的延迟（通常几百毫秒到几秒）。

**正确的前端处理**：

```js
async function stop(runId) {
  // 1. 立刻给用户反馈（乐观更新）
  stopRenderingDeltas();        // 停止把后续 message_delta 追加到界面
  setUiState('stopping');       // 按钮变成「停止中…」

  // 2. 登记停止请求
  await api.post(`/api/agent/run/${runId}/interrupt`);

  // 3. 等 interrupt 事件（在流的 onEvent 里处理）
  //    收到后：setUiState('stopped')，closeStream()
}
```

**不要**：
- 不要以「接口返回成功」为停止完成的标志
- 不要以「连接关闭」为停止完成的标志（连接关闭也可能是网络问题）
- 不要在调完接口后立刻重新建流（会打断正在收敛的状态）

**唯一权威的停止完成信号是 `interrupt` 事件。**

**另外**：用户点停止后，**已经生成的那部分文本要保留在界面上**，不要清空。后端也会把它存下来（`message_snapshot` 能拿回来）。

---

### 2. 继续

```
POST /api/agent/run/{runId}/resume
```

无请求体。响应：`data` 是 `AgentRunVO`（新的状态，通常是 `RUNNING`）

**调用后必须重新建流**：

```js
async function resume(runId) {
  const res = await api.post(`/api/agent/run/${runId}/resume`);
  if (res.code !== 0) { toast(res.message); return; }
  setUiState('running');
  // 重新建流，不带 lastEventId（从头补事件）
  connectStream(`/api/agent/run/${runId}/stream`);
}
```

**继续之后会发生什么**：

- 后端从**断点**继续，不会从头重跑
- 已经执行过的工具**不会重新执行**（直接用之前的结果继续推理）
- **但被中断的那次模型调用会重新调用一次**（模型不支持「从半句话接着写」）。这是这个功能固有的成本，用户会感觉到「继续之后回答是从头开始写的」——**这是正常的，界面不要试图接着半截文本往下拼**

**所以前端要注意**：继续后会收到 `message_snapshot`，它给的是**重新生成的完整文本**。要用它**替换**当前显示的文本，而不是追加。

---

### 3. 同意 / 拒绝副作用操作

```
POST /api/agent/run/{runId}/approve
Content-Type: application/json
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `approve` | boolean | 是 | `true` 同意执行，`false` 拒绝 |
| `reason` | string | 否 | 拒绝原因，最长 500 字符 |

响应：`data` 是 `AgentRunVO`

**什么时候会用到**：收到 `interrupt` 事件且 `interruptType === 'APPROVAL_REQUIRED'` 时，`data.pendingTool` 里带着待执行的操作。模型想做一个**会改变系统状态的操作**（建工单、发通知、改数据），需要用户点头。

**前端要展示什么**：

- `pendingTool.toolName` —— 但**不要直接显示英文工具名**，要有一份 `工具名 → 中文名称` 的映射（见 [60-tools-工具](60-tools-工具.md)）
- `pendingTool.toolDescription` —— 后端给的自然语言说明，可以直接展示
- `pendingTool.arguments` —— **必须把参数原样展示给用户**，这是让用户判断「要不要点同意」的全部依据

**⚠️ 拒绝之后的语义**：运行会回到 `SUSPENDED`，**不会**自动尝试别的办法。用户可以选择「继续」（让模型换个思路，但模型并不天然知道被拒绝了的原因——`reason` 会作为工具错误信息喂给模型，所以它能知道）或者放弃。

**同意之后**：工具执行**恰好一次**。即使用户快速点了两次「同意」，后端也有幂等保护，不会重复创建。前端不需要做防抖（但做了也无害）。

---

### 4. 查询运行状态

```
GET /api/agent/run/{runId}
```

响应：`data` 是 `AgentRunVO`

**用途**：

- 页面刷新后，前端只知道 `runId`（存在 URL 里），用它查询当前状态，决定是「重连流」还是「显示继续按钮」
- 定时兜底轮询（**不建议**频繁轮询，重连流是更好的方式）

---

### 5. 查询执行链（可选，用于展示「它干了什么」）

```
GET /api/agent/run/{runId}/steps
```

响应：`data` 是一个数组，元素结构：

```ts
interface AgentStepVO {
  id: string;
  seq: number;                       // 执行顺序
  type: 'LLM_CALL' | 'TOOL_CALL';
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'INTERRUPTED';
  toolName: string | null;
  arguments: object | null;
  result: object | null;             // 可能被截断
  replayed: boolean;                 // ★ 这个结果是从缓存重放的，不是重新执行的
  errorMsg: string | null;
  elapsedMs: number | null;
  startTime: string | null;
}
```

**用途**：做一个「执行详情」面板，让用户看到 Agent 到底调了哪些工具、传了什么、花了多久。这是 Agent 类产品建立信任感的重要部分。

**`replayed` 字段**：`true` 表示这一步的结果是**从中断点恢复时直接读出来的**，没有重新执行。界面上可以标注「已恢复」——这既解释了为什么这次没花时间，也证明了系统没有重复执行副作用。

### 6. 查询会话下的运行列表（可选）

```
GET /api/agent/run/list?conversationId=7&current=1&pageSize=20
```

响应：分页结构，`records` 是 `AgentRunVO` 数组。

**用途**：一个会话里每次提问都是一次运行。做「查看历史运行」「找出失败的运行」这类功能时用得上。

## 完整的交互流程图

```
用户发消息
    │
    ▼
POST /api/chat/stream  ──► 收到 run_start，记录 runId
    │
    ├─ 正常生成 ──► run_end(COMPLETED) ──► 结束
    │
    ├─ 用户点停止
    │      ├─ 立刻：停止渲染新文本、按钮变「停止中」
    │      ├─ POST /agent/run/{id}/interrupt
    │      ├─ 等 interrupt 事件（USER_STOP）
    │      ├─ 关闭流，显示「已停止 + 继续」
    │      └─ 用户点继续 ──► POST /agent/run/{id}/resume ──► 重新建流 ──┐
    │                                                                    │
    ├─ 模型要调有副作用的工具                                              │
    │      ├─ interrupt 事件(APPROVAL_REQUIRED, pendingTool)             │
    │      ├─ 弹确认框，展示 pendingTool.arguments                        │
    │      └─ 用户选择                                                   │
    │            ├─ 同意 ──► POST /agent/run/{id}/approve{approve:true} ──┤
    │            └─ 拒绝 ──► POST /agent/run/{id}/approve{approve:false}  │
    │                        └─ 回到 SUSPENDED，显示继续按钮             │
    │                                                                    │
    ├─ 网络断了（没收到 run_end / interrupt）                             │
    │      ├─ 等 1 秒，GET /agent/run/{id}/stream?lastEventId=xxx ────────┤
    │      └─ 重试 3 次仍失败 ──► 提示「连接断开」+ 手动重连按钮          │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
                          继续执行，直到 run_end
```

## 前端状态机建议

界面上的「对话状态」其实只需要几个状态，别做复杂了：

```ts
type ChatUiState =
  | { kind: 'idle' }                                        // 空闲，可以发消息
  | { kind: 'running'; runId: string }                       // 生成中，显示停止按钮
  | { kind: 'stopping'; runId: string }                      // 已请求停止，等 interrupt
  | { kind: 'stopped'; runId: string }                       // 已挂起，显示继续按钮
  | { kind: 'awaitingApproval'; runId: string; tool: PendingTool }  // 等用户确认
  | { kind: 'reconnecting'; runId: string; attempts: number }       // 断线重连中
  | { kind: 'error'; message: string; retryable: boolean };
```

**关键点**：把 `runId` 存进 URL（比如 `/chat/{conversationId}?run={runId}`）。这样用户刷新页面后，前端能知道「上次有一次运行」，可以查它的状态并决定重连还是显示继续。**不存的话，刷新页面后正在生成的回答就丢了。**

## 待定项

- 是否支持**自动继续**（挂起后系统自动恢复，不需要用户点）。目前不做——自动恢复的前提是能可靠判断「用户还想继续」，这个判断很容易做错
- **撤回已执行的副作用**（比如撤销刚才建的工单）。这是业务补偿逻辑，不在状态机里
- 挂起的运行**保留多久**（超过期限会被回收成 `CANCELLED`）。暂定 24 小时，实现时确认
- 是否允许用户**跳过**某个工具（让它直接继续）。目前只能同意或拒绝
