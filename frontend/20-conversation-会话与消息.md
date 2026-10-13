# conversation · 会话与消息

> ✅ **状态：已在 M4 实现**（`POST /chat/message` 非流式对话见 [30-chat](30-chat-对话与流式.md)）。字段名以本文档为准。

会话（`conversation`）是用户可见的「一次对话」。一个会话里有很多条消息（`conversation_message`）。

**「会话」和「一次运行」的区别**（容易混）：用户每次发一条消息，都会触发一次 **运行**（run）。所以一个会话里有多次运行。**「中断对话」实际上是中断当前这次运行，会话本身不受影响。** 运行相关的接口在 [40-agent-中断与恢复](40-agent-中断与恢复.md)。

## 接口总览

所有路径都要加 `/api` 前缀（见 [01-通用约定](01-通用约定.md)）。**全部需要登录**，未登录返回 `40100`，操作他人会话返回 `40101`。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/conversation/create` | 创建会话（可同时关联知识库 `kbIds`） |
| GET | `/conversation/list` | 会话列表（分页，置顶在前） |
| GET | `/conversation/{id}` | 会话详情（**不含消息**） |
| POST | `/conversation/update` | 重命名 / 置顶 |
| POST | `/conversation/delete` | **删除会话**（逻辑删） |
| GET | `/conversation/{id}/messages` | 拉取消息历史（分页） |
| POST | `/conversation/message/delete` | 删除消息（**截断重发**，不是删单条） |

## 数据结构

### `ConversationVO`

```ts
interface ConversationVO {
  id: string;               // 会话 id（字符串）
  title: string;            // 标题，默认「新会话」
  pinned: boolean;          // 是否置顶
  messageCount: number;     // 消息数（number，不是字符串）
  kbIds: string[];          // 关联的知识库 id 列表，空数组表示不用 RAG
  lastMessageTime: string | null;  // 最后一条消息时间，用于列表排序
  createTime: string;
}
```

### `MessageVO`

```ts
interface MessageVO {
  id: string;               // 消息 id
  seq: number;              // 会话内序号，从 1 开始。★ 排序用这个，不要用时间
  role: 'USER' | 'ASSISTANT';
  content: string;          // 正文
  toolCalls: string[] | null;  // 这条回答用过的工具名（仅供展示）
  interrupted: boolean;     // 是否被中断截断过
  tokenCount: number | null;
  createTime: string;
}
```

**关于 `role`**：只会出现 `USER` 和 `ASSISTANT` 两种。工具调用的中间过程**不作为消息返回**——它属于「执行细节」，走 [40-agent](40-agent-中断与恢复.md) 的执行链接口拿。

**关于 `seq`**：**排序必须用 `seq`，不要用 `createTime`。** 流式输出时助手消息和用户消息可能在同一毫秒内落库，用时间排序结果不确定。`seq` 是权威顺序。

## 接口

### 创建会话

```
POST /api/conversation/create
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 否 | 不传则默认「新会话」 |
| `kbIds` | string[] | 否 | 关联的知识库，不传表示不用 RAG |

响应：`data` 是 `ConversationVO`

**⚠️ `kbIds` 只能在创建时设定。** 会话建好后**没有**「修改关联知识库」的接口——`POST /conversation/update` 只支持改标题和置顶。若某次要临时用别的库，靠发消息时在请求里带 `kbIds` 覆盖（见 [30-chat](30-chat-对话与流式.md)），那只影响**单次请求**，不改会话的长期关联。

**使用建议**：用户在输入框里打完字点发送时，如果当前没有会话，可以先调这个接口拿 `id`，再调 `POST /chat/stream`。**不要把创建会话和发消息合成一个接口**——那样「新建会话但一句话都没发」的场景就没法表达了。

---

### 会话列表

```
GET /api/conversation/list?current=1&pageSize=20
```

分页参数见 [01-通用约定](01-通用约定.md)。

**排序规则**：置顶的在前，然后按 `lastMessageTime` 倒序（最近说话的在前）。列表页不需要传 `sortField` / `sortOrder`。

响应：

```json
{
  "code": 0,
  "data": {
    "records": [
      {
        "id": "7",
        "title": "年假政策咨询",
        "pinned": false,
        "messageCount": 6,
        "kbIds": ["1"],
        "lastMessageTime": "2026-09-21T10:30:00",
        "createTime": "2026-09-21T09:00:00"
      }
    ],
    "total": "12",
    "size": "20",
    "current": "1",
    "pages": "1"
  }
}
```

---

### 会话详情

```
GET /api/conversation/{id}
```

响应：`data` 是 `ConversationVO`

**用于**：进入会话时拿元信息（标题、关联的知识库）。

**注意**：只返回会话本身，**不返回消息**。消息要单独调下面的接口（因为消息可能很多，要分页）。

---

### 重命名 / 置顶

```
POST /api/conversation/update
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | 会话 id |
| `title` | string | 否 | 新标题（1~255 字符） |
| `pinned` | boolean | 否 | 是否置顶 |

**传了才改**，不传的字段保持不变。

响应：`data` 是更新后的 `ConversationVO`

**越权**：操作不属于自己的会话，返回 `40101`（无权限）。

---

### 删除会话

```
POST /api/conversation/delete
```

| 字段 | 类型 | 必填 |
|---|---|---|
| `id` | string | 是 |

响应：`data` 是 `true`

**删除是逻辑删除**，用户不可见，但后台的运行记录（`agent_run`）会保留用于审计。

---

### 拉取消息历史

```
GET /api/conversation/{id}/messages?current=1&pageSize=50
```

**默认返回最近的消息**（按 `seq` 倒序取，返回时正序排列），这样用户一进来看到的是最新的对话。

响应：

```json
{
  "code": 0,
  "data": {
    "records": [
      { "id": "55", "seq": 1, "role": "USER", "content": "帮我查下年假政策",
        "toolCalls": null, "interrupted": false, "tokenCount": 12,
        "createTime": "2026-09-21T10:00:00" },
      { "id": "56", "seq": 2, "role": "ASSISTANT", "content": "根据《员工手册》…",
        "toolCalls": ["searchKnowledge"], "interrupted": false, "tokenCount": 486,
        "createTime": "2026-09-21T10:00:08" }
    ],
    "total": "6",
    "size": "50",
    "current": "1",
    "pages": "1"
  }
}
```

**前端加载更多历史的做法**：`current` 递增拉取更早的消息（因为接口是「从最新往回翻」，所以 `current=2` 拿到的是**更早**的一批）。拿到后**插到列表头部**，不要替换。

---

### 删除消息（截断重发）

```
POST /api/conversation/message/delete
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `conversationId` | string | 是 | |
| `fromSeq` | number | 是 | 从这个序号起（含）全部删除 |

响应：`data` 是被删除的消息条数（number）

**这个接口的语义是「截断」，不是「删除单条」。** 删掉 `seq >= fromSeq` 的所有消息。

**为什么这样设计**：对应 ChatGPT 那种「编辑我自己的消息然后重新发送」。用户改了第 3 条消息，那么第 3 条之后的所有内容（包括 AI 基于旧问题给出的回答）都必须一起作废——否则对话历史会出现「答非所问」的错位。

**前端使用流程**：

```js
// 用户点了「编辑」并提交新内容
await api.post('/api/conversation/message/delete', { conversationId, fromSeq: 3 });
await api.post('/api/chat/stream', { conversationId, content: newContent });
```

**运行中的会话不能截断**：如果当前有 `RUNNING` 状态的运行，返回 `50001`，`message` 为「当前有正在进行的回答，请先停止」。前端要先调停止接口（见 [40-agent](40-agent-中断与恢复.md)）。

> ⚠️ **该拦截暂未生效**：`agent_run` 表到 M8 才建，M4 的实现里还没有「运行中」这个概念，所以现在截断不会因为「运行中」被拒。到 M8 补上校验。

## 前端类型定义（可直接复制）

> **所有 id 一律 `string`**（后端 `Long` 全局序列化成字符串，见 [01-通用约定](01-通用约定.md) 陷阱 1）；`seq`、`messageCount`、`tokenCount` 是 `number`。

```ts
export interface ConversationVO {
  id: string;
  title: string;                    // 默认「新会话」
  pinned: boolean;
  messageCount: number;
  kbIds: string[];                  // 关联知识库 id，空数组 = 不用 RAG
  lastMessageTime: string | null;   // 列表排序用
  createTime: string;
}

export interface MessageVO {
  id: string;
  seq: number;                      // ★ 排序用这个，不要用 createTime
  role: 'USER' | 'ASSISTANT';
  content: string;
  toolCalls: string[] | null;       // 仅供展示的工具名
  interrupted: boolean;
  tokenCount: number | null;
  createTime: string;
}

export interface ConversationCreateRequest {
  title?: string;
  kbIds?: string[];
}

export interface ConversationUpdateRequest {
  id: string;
  title?: string;                   // 1~255 字符
  pinned?: boolean;
}

export interface ConversationDeleteRequest {
  id: string;
}

export interface MessageTruncateRequest {
  conversationId: string;
  fromSeq: number;                  // 删 seq >= fromSeq 的全部消息
}
```

## 待定项

以下还没最终确定，实现前会补充到本文档：

- 会话标题是否**自动生成**（用模型根据第一条消息起标题）。如果要，会在 `run_end` 事件里带上新标题，或者前端轮询
- `messageCount` 是只算用户消息，还是用户+助手都算（倾向都算）
- 历史消息是否需要**全文搜索**（目前没有）
- 删除会话时，已上传的知识库不受影响（知识库是独立的，不随会话删除）
