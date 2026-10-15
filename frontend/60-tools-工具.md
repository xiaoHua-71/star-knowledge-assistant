# tools · 工具

> 🟡 **状态：`GET /tools/list` 已实现（M7，2026-10-14）——返回下方「真实响应」；`GET /tools/logs` 尚未实现**（依赖的 `agent_run`/`agent_step` 表已在 M8 建好，但该接口未做）。工具清单会随业务需求增长。
>
> 当前**已上线**的工具：`getCurrentTime`、`searchKnowledge`，均为**只读**。`createTicket` 等**有副作用**工具属 M10，**目前不在清单里**。

## 工具是什么

「工具」（Tool）是**后端能力的封装**——把某个接口包装成模型能调用的形式。用户在对话里说「帮我建个工单」，模型自己判断该调 `createTicket` 这个工具，并填好参数。

**前端需要关心的只有两件事**：

1. **展示**：告诉用户「这次回答用了哪些工具」
2. **确认**：有副作用的工具执行前，需要用户点头（走 [40-agent](40-agent-中断与恢复.md) 的 `approve` 接口）

**工具的调用过程前端不用管**——它在流式事件里以 `tool_call` / `tool_result` 出现（见 [02-SSE协议规范](02-SSE协议规范.md)）。

## 工具的两个属性（前端必须理解）

### 1. 是否有副作用（`sideEffect`）

| 值 | 含义 | 例子 |
|---|---|---|
| `false` | **只读**。执行多少次都不改变系统状态 | 查知识库、查当前时间 |
| `true` | **有副作用**。会改变系统状态，重复执行有后果 | 建工单、发通知 |

### 2. 是否需要人工确认（`requireApproval`）

**`sideEffect = true` 的工具通常会要求用户确认。** 收到 `interrupt` 事件（`interruptType = "APPROVAL_REQUIRED"`）时，`data.pendingTool` 里就是待确认的操作。

**⚠️ 但不要假设「有副作用 = 一定需要确认」**：如果一个后端接口本身支持幂等（重复调用只会生效一次），它可以标记成「有副作用但不需要确认」。所以**判断依据永远是事件本身，不是你对工具属性的记忆**。

## 接口

### 1. 工具清单

```
GET /api/tools/list
```

响应：

**真实响应（2026-10-14 实测，可直接对照）**：

```json
{
  "code": 0,
  "data": [
    {
      "name": "getCurrentTime",
      "displayName": "获取当前时间",
      "description": "获取当前的日期和时间。当问题涉及『现在』『今天』『几号』『几点』等需要当前时刻的信息时调用。",
      "sideEffect": false,
      "requireApproval": false,
      "parameters": []
    },
    {
      "name": "searchKnowledge",
      "displayName": "检索知识库",
      "description": "在用户当前会话关联的知识库中检索相关内容。当问题可能与用户上传的资料/知识库有关时，调用它来获取可引用的依据，再据此回答。",
      "sideEffect": false,
      "requireApproval": false,
      "parameters": [
        { "name": "query", "type": "string", "required": true, "description": "检索关键词或问题，用自然语言描述要查找的内容" }
      ]
    }
  ],
  "message": "ok"
}
```

**字段说明**：

- **需登录**：未登录返回 `{"code": 40100, ...}`（普通信封）。
- `parameters` **总是存在**：无参工具是 `[]`，有参工具是数组（`type` 是 JSON schema 类型名 `string`/`integer`/`number`/`boolean`）。
- `sideEffect` / `requireApproval` 是 `boolean`。
- 清单**按工具名排序**，顺序稳定。

**用途**：

- **展示**：界面上有地方解释「这个助手能做什么」时，用它渲染一份能力清单
- **名称映射**：`tool_call` 事件里只给英文 `toolName`，用这里的 `displayName` 映射成中文再展示给用户

**⚠️ 关于 `displayName`**：后端必须给每个工具配中文名。让用户看到 `searchKnowledge` 这种英文标识符是很糟糕的体验。**前端如果拿到的 `displayName` 是空的，要自己有一份兜底映射表**，不要直接把 `name` 显示出来。

### 2. 工具调用记录

```
GET /api/tools/logs?runId={runId}&current=1&pageSize=20
```

| 参数 | 说明 |
|---|---|
| `runId` | 可选。不传则返回当前用户的全部调用记录 |
| `toolName` | 可选，按工具筛选 |
| `success` | 可选，只看成功的或只看失败的 |

响应：

```ts
interface ToolLogVO {
  id: string;
  runId: string | null;
  toolName: string;
  displayName: string;
  sideEffect: boolean;
  success: boolean;
  arguments: object | null;
  result: object | null;      // 可能被截断
  errorMsg: string | null;
  elapsedMs: number | null;
  replayed: boolean;          // ★ 结果来自中断恢复时的重放，不是重新执行
  createTime: string;
}
```

**用途**：做「执行详情」面板——用户能看到 Agent 具体调了什么、传了什么参数、结果是什么。

**`replayed` 字段**：`true` 表示这一步是从中断点恢复时**直接读出来的历史结果**，没有真的再调一次后端接口。界面上标注「已恢复」有两个好处：

1. 解释为什么这次响应特别快
2. 让用户确认「系统没有重复执行我的操作」——这对有副作用的操作尤其重要

## 初始工具清单

以下是规划中的工具（**会以业务需求为准增删**）：

### 只读工具

| `name` | 中文名 | 说明 |
|---|---|---|
| `searchKnowledge` | 检索知识库 | 在当前会话关联的知识库里检索。**这是 RAG 的入口**，每次对话都可能被调用 |
| `getCurrentTime` | 获取当前时间 | 模型不知道自己现在是几点，需要这类工具补充 |

### 有副作用的工具

| `name` | 中文名 | 需要确认 | 说明 |
|---|---|---|---|
| `createTicket` | 创建工单 | 是 | 为指定人员建工单 |

**这份清单目前只是一个起点。** 真正的工具集应该由业务场景决定——「用户会要求助手帮他办什么事」是设计工具的唯一依据。

## 待定项

| 待定 | 说明 |
|---|---|
| **具体有哪些工具** | 取决于业务需求。需要明确「用户会要求助手做哪些事」 |
| `displayName` 的维护方式 | 是硬编码在代码里，还是可配置（数据库/配置文件）？如果工具会频繁增删，建议可配置 |
| 是否允许用户**自定义工具** | 比如「把这个 HTTP 接口接进来」。这是 MCP 那一层的事，建议后置 |
| 工具执行**超时时间** | 目前未定，估计单个工具 10~30 秒 |
| 工具结果的**展示形式** | 现在 `result` 是原始 JSON。前端直接展示 JSON 对用户不友好，可能需要每个工具定义自己的「结果展示模板」 |
| 是否支持**取消正在执行的工具** | 不支持。工具要么没开始，要么执行完（见 [knowledge/12](../knowledge/12-中断与恢复的状态机.md) 的安全点说明） |

## 前端展示建议

### 工具调用过程怎么展示

流式过程中收到 `tool_call` 事件时，**不要把原始参数糊到界面上**。建议展示成一行简短的进度提示：

```
🔍 正在检索知识库…                    ← tool_call(searchKnowledge)
✓ 检索完成，找到 3 篇相关内容           ← tool_result(success=true)
📝 正在创建工单…                       ← tool_call(createTicket)
```

**有副作用的工具要视觉上区分**（比如换个颜色、加个图标），让用户意识到「这个操作会改数据」。

### 确认框怎么设计

收到 `APPROVAL_REQUIRED` 时的确认框，**必须把参数展示清楚**：

```
┌─────────────────────────────────────────┐
│  需要你的确认                             │
│                                          │
│  助手想要 创建工单                        │
│                                          │
│    工单标题：年假政策咨询                  │
│    处理人：  hr                           │
│                                          │
│         [ 拒绝 ]      [ 同意执行 ]        │
└─────────────────────────────────────────┘
```

**几个关键点**：

1. **用中文名**，不要显示 `createTicket`
2. **参数必须展示**（`pendingTool.arguments`）——这是用户判断的依据。把参数藏起来只问「是否同意」，等于让用户盲签
3. **「拒绝」不要做得太隐蔽**。用户有权拒绝，而且拒绝之后能继续对话（可以说明原因）
4. **参数里如果有敏感信息**（身份证、手机号），展示时可以部分打码，但**要让用户知道有这个值**
