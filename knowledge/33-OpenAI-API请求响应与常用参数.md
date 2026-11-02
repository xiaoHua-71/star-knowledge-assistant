# OpenAI API 请求/响应格式与常用参数

> **定位**：给「知道怎么调 SDK、但没仔细看过线上报文」的人补协议层。只回答四件事——**请求长什么样**、**响应长什么样**、**常用参数各自改变什么**、**失败时怎么判断和重试**。
>
> 本文以 **Chat Completions（`/chat/completions`）** 为主，因为本项目通过 `langchain4j-open-ai` 直连 DeepSeek 的 OpenAI 兼容端点。工具调用的深度机制见 [32-工具调用机制与面试拷打](32-工具调用机制与面试拷打.md)，SSE 推流到前端见 [31-流式输出SSE](31-流式输出SSE.md)，ReAct 全链路见 [04-ReAct主循环全链路与面试讲解](04-ReAct主循环全链路与面试讲解.md)。
>
> 代码基线：LangChain4j `1.20.0`、`deepseek-flash`（OpenAI 兼容协议）。**协议字段以 OpenAI 官方文档为准；各兼容厂商可能只实现子集，参数支持情况必须实测。**

---

## 0. 先背这十二句

1. **Chat Completions 是无状态的**：服务端不记得上一轮；每次都要把 system、历史、当前问题一起重发。
2. **`messages` 是唯一必填的语义输入**，`model` 是唯一必填的配置。其余全是可选项。
3. **`temperature` 调「随机程度」，`top_p` 调「候选池大小」**；一般只调一个，两个一起调会让效果难以解释。
4. **`temperature=0` 也不保证完全确定**：浮点、批处理、后端版本都会造成差异；要排查用 `system_fingerprint`。
5. **`max_tokens` 限制的是输出，不是输入**。输入超上下文窗口是另一回事，会直接 400。
6. **`finish_reason` 比 `content` 更能说明「这句回答完整不完整」**：`length` 是截断，`tool_calls` 是还没说完。
7. **`usage` 才是真实 token 账单**；按字符估只能做预算，不能做结算。
8. **流式返回的是一串 SSE `data:` 事件，最后以 `data: [DONE]` 结束**；`[DONE]` 不是 JSON。
9. **流式下 `content` 是增量，`tool_calls` 也是分片**；参数要按 `index` 拼完整、等 `finish_reason=tool_calls` 才能执行。
10. **工具调用不是 `response_format`**：前者是「模型要求宿主做事」的协议，后者是「模型直接输出结构化数据」的约束。
11. **「OpenAI 兼容」兼容的是接口形状，不是行为细节**：默认值、支持的参数、报错体、限流策略都可能不同。
12. **重试只对「没产生副作用、也没产生半截输出」的失败安全**；429/5xx 可退避重试，400/401/403/404 重试没有意义。

---

## 1. 先建立地图：你调的到底是什么

### 1.1 这是一类「无状态远程推理」接口

大模型 API 的本质是：你发一段**消息数组**，它返回下一段**助手消息**。它不保存会话、不记得你上次说过什么、也不知道你的数据库。

这带来三个直接后果：

- **每轮都要重发历史**：历史越长，`prompt_tokens` 越大，成本和延迟越高；
- **上下文有上限**：输入 + 输出不能超过模型的上下文窗口，超了是请求失败，不是自动截断；
- **服务端可水平扩容**：无状态才好做多副本，所以「记忆」天然属于应用层。

本项目把「存什么历史、丢什么历史」放在 `MemoryBuilder` 里做，见 [11-对话记忆](11-对话记忆.md)。

### 1.2 Chat Completions 是事实标准

OpenAI 的 `POST /v1/chat/completions` 已经成了行业事实标准。DeepSeek、通义、Kimi、智谱、vLLM、Ollama、LM Studio 等大量服务都提供「OpenAI 兼容」端点，字段名和语义大体一致。

本项目的选型写在 `ChatModelProperties.java:12-14`：用官方 `langchain4j-open-ai` 模块直连 DeepSeek，所以「换模型 / 换端点」通常只改 `baseUrl` + `modelName`，业务代码不动。

### 1.3 边界：本文管什么、不管什么

| 归本文管 | 不归本文管 |
|---|---|
| 请求/响应的 JSON 字段 | 模型怎么训练、怎么选型 |
| 常用采样、输出、工具参数 | 工具编排、审批、幂等（见 32） |
| 流式 SSE 报文与拼装 | 后端怎么把事件推给前端（见 31） |
| 错误码、超时、重试 | 业务侧的重试与状态机（见 12） |
| token、上下文、成本口径 | 具体计费价格（以 provider 价目表为准） |

### 1.4 一次调用的生命周期

```
构造 messages + 参数
        │
        ▼
POST /chat/completions（Authorization: Bearer <key>）
        │
        ├─ 非流式：等完整 JSON → choices[0].message + usage
        └─ 流式：逐个 SSE data: chunk → 增量拼装 → [DONE]
        │
        ▼
读 finish_reason 判断：说完了 / 被截断 / 要调工具 / 被过滤
        │
        ▼
读 usage 记账；读错误体决定是否重试
```

---

## 2. 请求格式

### 2.1 端点与请求头

```
POST https://api.openai.com/v1/chat/completions       # OpenAI
POST https://api.deepseek.com/chat/completions        # DeepSeek 官方（本项目）

Authorization: Bearer <API_KEY>
Content-Type: application/json
```

说明：

- `baseUrl` 是否带 `/v1` 由 provider 决定；OpenAI 兼容服务的常见写法是 `https://host/v1`，SDK 再拼 `/chat/completions`。
- **API Key 只能放在后端**。浏览器直连会把它暴露给任何打开 DevTools 的人；本项目通过环境变量 `DEEPSEEK_API_KEY` 注入（`ChatModelProperties.java:30-36`）。
- 有些 provider 还要求 `api-version`（Azure）或自定义 header，兼容不代表完全一样。

### 2.2 最小请求体

```json
{
  "model": "deepseek-flash",
  "messages": [
    {"role": "system", "content": "你是「智能星」知识助手，用简洁、准确的中文回答用户问题；不确定时如实说明，不要编造。"},
    {"role": "user", "content": "什么是 ReAct？"}
  ]
}
```

这两个字段就能跑。其余参数都是「控制输出的旋钮」。

### 2.3 `messages` 的消息模型

`messages` 是一个有序数组，每条消息至少包含 `role` 和 `content`（assistant 可以没有 `content`，但有 `tool_calls`）。

| `role` | 谁说的 | `content` | 关键约束 |
|---|---|---|---|
| `system` | 开发者给模型的全局指令 | 字符串或内容块数组 | 通常放第一条；新模型可能用 `developer` 替代 |
| `developer` | 同上，OpenAI 新角色 | 同上 | 兼容服务未必支持，跨厂商慎用 |
| `user` | 用户 | 字符串或内容块数组 | 可带 `name` 区分说话人 |
| `assistant` | 模型的历史回复 | 字符串 / `null` / 内容块 | 带 `tool_calls` 时 `content` 常为 `null` |
| `tool` | 宿主回填的工具结果 | 字符串或内容块数组 | **必须带 `tool_call_id`**，与上一条 assistant 的 `tool_calls` 配对 |
| `function` | 旧版函数结果 | 字符串 | 已被 `tool` 取代，仅兼容旧接口 |

几条容易被忽略的规则：

1. **历史里 assistant 的 `tool_calls` 必须保留**。只回填 `role=tool` 而删掉发起调用那条 assistant 消息，模型就看不到「这个结果对应哪个动作」。
2. **每个 `tool_call.id` 必须有且只有一条 `role=tool` 消息**，顺序紧随其后。缺失、孤儿或 id 对不上，OpenAI 兼容服务端通常直接 400。
3. **`system` 不是「更高级的 user」**，它是模型优先遵循的开发者指令；把大量业务数据塞进 system 会挤占预算，也更容易被后续上下文干扰。
4. **`content` 可以是数组**，用于多模态：文本、图片、音频、文件。不是所有兼容服务都支持，DeepSeek 的兼容端点对多模态的支持也以文档为准。

### 2.4 多轮 + 工具消息的完整形状

```json
[
  {"role": "system", "content": "你是「智能星」知识助手……"},
  {"role": "user", "content": "查一下年假政策"},
  {
    "role": "assistant",
    "content": null,
    "tool_calls": [
      {
        "id": "call_abc",
        "type": "function",
        "function": {
          "name": "searchKnowledge",
          "arguments": "{\"query\":\"年假政策\"}"
        }
      }
    ]
  },
  {
    "role": "tool",
    "tool_call_id": "call_abc",
    "content": "根据《员工手册》第 3 章：入职满一年享有 5 天年假……"
  }
]
```

下一轮模型看到「动作 + 观察」，才可能给出最终回答。工具调用的完整语义与不变量见 [32 §2](32-工具调用机制与面试拷打.md)。

### 2.5 常用请求头与可选字段

| 字段/头 | 作用 | 备注 |
|---|---|---|
| `Authorization: Bearer <key>` | 认证 | 必须在服务端使用 |
| `Content-Type: application/json` | 请求体类型 | 流式响应是 `text/event-stream` |
| `Idempotency-Key` | 请求幂等键 | **部分 provider 支持**；本项目未用，副作用幂等靠业务键 |
| `OpenAI-Beta` / 厂商自定义头 | 启用特性 | 以 provider 文档为准 |

---

## 3. 常用参数

> 下面给的是 **OpenAI Chat Completions 的常见语义**。范围、默认值、是否支持都可能因模型和兼容厂商而异；凡是影响正确性的参数，接新 provider 时都要实测。

### 3.1 必填与输出控制

| 参数 | 类型 | 常见默认 | 作用 | 坑 |
|---|---|---|---|---|
| `model` | string | 无（必填） | 选择模型 | **模型 ID 由 provider 定义**，不同厂商的同一个模型可能叫不同名字 |
| `messages` | array | 无（必填） | 对话历史 | 无状态，每轮重发 |
| `stream` | bool | `false` | 是否 SSE 流式返回 | 流式要处理分片、`[DONE]`、中途断开 |
| `stream_options.include_usage` | bool | `false` | 流式末尾附带 `usage` | 不是所有兼容服务都支持 |
| `max_tokens` | int | 模型相关 | 限制**输出** token 数（旧字段） | 新模型可能改用 `max_completion_tokens`；兼容服务可能不认新字段 |
| `max_completion_tokens` | int | 模型相关 | 限制输出 token，**含推理 token** | 旧模型/旧服务可能不认，直接 400 |
| `stop` | string / string[] | `null` | 命中即停止生成 | 停止序列本身不出现在结果里；OpenAI 最多 4 个；部分推理模型不支持 |
| `n` | int | `1` | 一次生成几个候选 | 成本 × n；流式下用 `choices[].index` 区分 |

**`max_tokens` vs 上下文窗口**：

- `max_tokens` 只限制**输出**，不会阻止输入过长；
- 上下文窗口限制的是 **输入 + 输出**；
- 输入超限：HTTP 400，错误码常见 `context_length_exceeded`；
- 输出撞顶：正常返回，但 `finish_reason = "length"`，回答被截断。

本项目**没有显式设置 `max_tokens`**（`ChatModelConfig.java:39-62` 只传了 model/temperature/timeout），所以输出上限走 provider 默认值。好处是简单，代价是「默认值可能很大」，成本和延迟不可控——这是一个已知的待补项。

### 3.2 采样参数（控制「怎么选下一个 token」）

| 参数 | 范围（常见） | 默认 | 作用 | 使用建议 |
|---|---|---|---|---|
| `temperature` | 0–2 | 1 | 缩放 logits，越高越随机 | RAG/事实问答用低温；创意写作用高温 |
| `top_p` | 0–1 | 1 | 核采样：只从累计概率达到 p 的最小集合里选 | 与 temperature 二选一调；p 越小越保守 |
| `presence_penalty` | -2–2 | 0 | 对「已出现过」的 token 惩罚，鼓励新话题 | 闲聊可用；RAG 里可能导致偏离原文 |
| `frequency_penalty` | -2–2 | 0 | 按出现次数惩罚，抑制复读 | 长文重复时再考虑 |
| `logit_bias` | map<token_id, -100–100> | null | 给指定 token 加权/封禁 | token id 与分词器绑定，**不可跨模型移植** |
| `logprobs` | bool | false | 返回每个 token 的对数概率 | 置信度分析/分类时用 |
| `top_logprobs` | 0–20 | null | 返回 top-N 候选 token | 需 `logprobs=true`；兼容服务常不支持 |
| `seed` | int | null | 尽力让同一请求可复现 | **best effort**；要结合 `system_fingerprint` 看 |

**temperature 和 top_p 的区别（面试常问）**：

- `temperature` 是「把概率分布压平或拉尖」：T 高 → 低概率 token 也有机会；T 低 → 更接近贪心。
- `top_p` 是「先截断候选池」：只保留累计概率到 p 的那批 token，再在池子里采样。
- 两者都在改变分布，**同时调会让效果难以归因**；OpenAI 的建议是只调其中一个。
- `temperature=0` 近似贪心，但**不是确定性保证**：浮点误差、批处理、后端版本都可能让结果不同。

### 3.3 工具与结构化输出

| 参数 | 作用 | 关键点 |
|---|---|---|
| `tools` | 告诉模型有哪些函数可调 | 数组元素形如 `{"type":"function","function":{name,description,parameters}}`；**每轮都要重发** |
| `tool_choice` | 控制调不调、调哪个 | `auto`（默认）/ `none` / `required` / 指定函数；兼容性差异大 |
| `parallel_tool_calls` | 是否允许一次返回多个工具调用 | OpenAI 默认 `true`；宿主必须能处理 0/1/N 个 |
| `response_format` | 要求模型输出结构化内容 | `text`（默认）/ `json_object` / `json_schema`；详见下 |

**`response_format` 三档**：

```json
{"type": "text"}
{"type": "json_object"}
{"type": "json_schema", "json_schema": {"name": "answer", "strict": true, "schema": {...}}}
```

- `json_object`：只保证「输出是合法 JSON」，**不保证符合你的字段**；OpenAI 还要求消息里出现 "JSON" 字样，否则可能报错。
- `json_schema` + `strict`：用约束解码尽量保证符合 schema；但 **schema 合法 ≠ 语义正确**，而且 strict 模式对 schema 有子集限制（常见要求 `additionalProperties: false`、所有字段都进 `required`）。
- 兼容厂商对 `json_schema` / `strict` 的支持差异很大，不能用「OpenAI 支持」推断所有 provider 都支持。

**工具调用和 `response_format` 不是一回事**：

- `tools` 是「模型请求宿主执行动作」，有 `tool_call_id`、有回填、有循环；
- `response_format` 是「模型直接吐一段结构化文本」，没有执行、没有回填；
- 本项目用前者做检索/建单，没有用后者做最终答案的结构化。

### 3.4 其他常用字段

| 字段 | 作用 | 注意 |
|---|---|---|
| `user` | 标记最终用户，便于滥用追踪 | 不要放手机号/邮箱等敏感信息；新接口可能改用 `safety_identifier` |
| `metadata` | 自定义键值对 | 支持度因 provider 而异 |
| `service_tier` | 选择服务档位（如弹性/优先级） | 新特性，兼容服务通常不支持 |
| `reasoning_effort` | 推理模型的思考强度（low/medium/high 等） | **仅部分推理模型支持**；这些模型可能不支持 `temperature`/`top_p` |

### 3.5 参数速查总表

| 想控制什么 | 用哪个参数 | 一句话 |
|---|---|---|
| 用哪个模型 | `model` | 必填，ID 由 provider 定义 |
| 给模型什么上下文 | `messages` | 必填，包含 system/历史/当前问题 |
| 输出多长 | `max_tokens` / `max_completion_tokens` | 只限输出，不限输入 |
| 随机性 | `temperature` | 越高越发散 |
| 候选池 | `top_p` | 越小越保守 |
| 复读/新话题 | `frequency_penalty` / `presence_penalty` | 按词频/是否出现过惩罚 |
| 可复现 | `seed` | 尽力而为，不是保证 |
| 结构化输出 | `response_format` | JSON 模式或 schema |
| 让模型调用函数 | `tools` + `tool_choice` | 协议见 32 |
| 流式 | `stream` + `stream_options` | SSE 分片 |
| 多个候选 | `n` | 成本 × n |
| 停止词 | `stop` | 结果不含停止序列 |

### 3.6 本项目怎么选参数

| 参数 | 本项目值 | 依据/理由 |
|---|---|---|
| `model` | `deepseek-flash` | `ChatModelProperties.java:51`；DeepSeek 官方 ID，换 provider 要一起改 |
| `baseUrl` | `https://api.deepseek.com` | `ChatModelProperties.java:27` |
| `temperature` | `0.3` | `ChatModelProperties.java:56`；RAG 问答偏忠实复述，低温起步，**未标定** |
| `timeout` | `60s` | `ChatModelProperties.java:61`；远端调用必须有超时 |
| `top_p` | 未设置 | 走 provider 默认；没有和 temperature 同时调 |
| `max_tokens` | 未设置 | 走 provider 默认；**已知缺口** |
| `response_format` | 未设置 | 不要求结构化输出 |
| `stream` | 非流式接口 false；流式接口 true | `/chat/message` 走 `model.chat`；`/chat/stream` 走 `OpenAiStreamingChatModel` |
| `tools` | 仅流式 Agent 路径传 | `AgentRunner.java:192-193`；非流式简化路径不传（`ChatServiceImpl.java:91`） |

---

## 4. 响应格式

### 4.1 非流式完整响应

```json
{
  "id": "chatcmpl-9xY...",
  "object": "chat.completion",
  "created": 1761800000,
  "model": "deepseek-flash",
  "system_fingerprint": "fp_abc123",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "ReAct 是一种「推理 + 行动」交替的 Agent 范式……",
        "refusal": null
      },
      "logprobs": null,
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 128,
    "completion_tokens": 96,
    "total_tokens": 224
  }
}
```

字段说明：

| 字段 | 含义 |
|---|---|
| `id` / `created` / `model` | 请求标识、创建时间、实际使用的模型（可能和请求值不同，provider 可能重定向） |
| `system_fingerprint` | 后端配置/版本的指纹；配合 `seed` 排查「为什么同样的请求结果不同」 |
| `choices[].index` | 候选序号，`n>1` 时从 0 开始 |
| `choices[].message` | 助手消息：`content`、`tool_calls`、`refusal` 等 |
| `choices[].finish_reason` | 为什么停（见 4.2） |
| `usage` | token 账单（见 4.3） |

### 4.2 `finish_reason`：回答完没完

| 值 | 含义 | 宿主该做什么 |
|---|---|---|
| `stop` | 正常结束（自然结束或命中 `stop` 序列） | 正常收尾 |
| `length` | 达到 `max_tokens` 或输出长度上限，**被截断** | 提示用户/续写/调大上限；不要当完整答案 |
| `tool_calls` | 模型要求调用工具，本轮没说完 | 执行工具、回填、再调模型 |
| `content_filter` | 被内容安全策略拦截 | 展示拒绝/兜底话术，不要重试同一请求 |
| `function_call` | 旧版函数调用格式 | 兼容旧接口时才会见 |
| `null` | 流式中间 chunk 还没结束 | 继续拼装，等最终 chunk |

**最常见的误判**：看到 `content` 非空就以为回答完整。实际上 `finish_reason=length` 时内容是被砍断的；`finish_reason=tool_calls` 时那段文字通常只是「我先查一下」，不是最终答案。

### 4.3 `usage` 与 token 口径

| 字段 | 包含什么 |
|---|---|
| `prompt_tokens` | 输入：system + 历史 + 当前用户消息 + **工具 schema 文本** |
| `completion_tokens` | 输出：正文 + 工具调用参数 JSON + 推理模型的思维链 token（按输出计费） |
| `total_tokens` | 两者之和 |
| `prompt_tokens_details.cached_tokens` | 命中前缀缓存的输入 token（部分 provider 才有） |
| `completion_tokens_details.reasoning_tokens` | 推理模型内部思考消耗的 token（部分 provider 才有） |

两个容易踩的点：

1. **工具 schema 也算输入 token**。工具越多、描述越长，每轮都在为此付费；这就是为什么「只下发相关工具」能省钱。
2. **流式默认不返回 `usage`**。OpenAI 需要显式设置 `stream_options: {"include_usage": true}`，并且在 `[DONE]` 前多给一个 `choices: []` 的 chunk 携带 `usage`；兼容服务可能默认带，也可能完全不支持。

### 4.4 错误响应

OpenAI 风格的错误体：

```json
{
  "error": {
    "message": "Invalid value for 'temperature': expected a number <= 2.",
    "type": "invalid_request_error",
    "param": "temperature",
    "code": "invalid_value"
  }
}
```

| HTTP | 常见类型/错误码 | 含义 | 能否重试 |
|---|---|---|---|
| 400 | `invalid_request_error`、`context_length_exceeded`、`invalid_value`、`missing_required_parameter` | 请求不合法 | **否**，改请求 |
| 401 | `authentication_error` | key 缺失/无效 | 否 |
| 403 | `permission_error` | 无权限、地区限制、模型未开通 | 否 |
| 404 | `not_found_error`、`model_not_found` | 路径或模型名错 | 否 |
| 413 | `request_too_large` | 请求体过大 | 否，拆小 |
| 422 | `unprocessable_entity` | 参数语义不合法 | 否 |
| 429 | `rate_limit_error` / 配额不足 | 限流或余额/配额问题 | **限流可退避重试；配额不足不能** |
| 500 | `server_error` | 服务端内部错误 | 可退避重试 |
| 502/503/504 | 网关/过载/超时 | 上游不可用 | 可退避重试 |
| 超时/连接断开 | 无统一错误体 | 网络问题 | 可重试，但流式要处理重复输出 |

注意：**兼容厂商的错误体不保证长这样**。有的直接返回 `{"message": "..."}`，有的把错误塞进 200 响应的 body。接入新 provider 时，第一件事是拿一个坏请求把错误体打出来。

---

## 5. 流式响应（SSE）

### 5.1 报文长什么样

请求：

```json
{
  "model": "deepseek-flash",
  "messages": [{"role": "user", "content": "用一句话解释 ReAct"}],
  "stream": true,
  "stream_options": {"include_usage": true}
}
```

响应头：

```
HTTP/1.1 200 OK
Content-Type: text/event-stream; charset=utf-8
Cache-Control: no-cache
Connection: keep-alive
```

响应体（每个事件以空行结束）：

```
data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"ReAct"},"finish_reason":null}]}

data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":" 是推理与行动交替的范式。"},"finish_reason":null}]}

data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"id":"chatcmpl-1","choices":[],"usage":{"prompt_tokens":18,"completion_tokens":14,"total_tokens":32}}

data: [DONE]
```

关键点：

- **每个事件是 `data:` 开头的行，空行表示事件结束**；OpenAI 风格不用 `event:` 字段（Anthropic 等原生协议会用）。
- **`[DONE]` 是字面量哨兵，不是 JSON**。把它丢进 `JSON.parse` 会抛异常。
- **`finish_reason` 在最后一个有内容的 chunk 上给出**；中间 chunk 是 `null`。
- **`usage` 默认不出现**，需要 `stream_options.include_usage`；出现时通常 `choices` 为空。
- 有些服务会发 `: keep-alive` 注释行或心跳，解析时要忽略非 `data:` 行。

### 5.2 增量拼装规则

| 内容 | 怎么拼 |
|---|---|
| 文本 | `content` 直接字符串拼接 |
| 工具调用 | 按 `tool_calls[].index` 分组；`id`/`type`/`function.name` 通常只在第一片出现，后续只有 `function.arguments` 分片；最后把 arguments 字符串拼成完整 JSON |
| 多个候选 | 按 `choices[].index` 分开拼 |
| 结束 | 收到 `finish_reason` 且非 null；再等 `[DONE]` 收尾 |

**为什么不能一收到工具调用就执行**：

1. 参数是分片到达的，第一片往往只有工具名，`arguments` 还是空串；
2. 必须等 `finish_reason=tool_calls` 且 JSON 拼完整，才能知道模型到底要调什么、参数是什么；
3. 过早执行可能拿到坏 JSON，也可能漏掉同一批里的其它工具调用。

本项目的 `streamOnce`（`AgentRunner.java:514`）就是把这些分片交给 LC4j 的 OpenAI 适配器拼装，在 `onCompleteResponse` 拿到完整的 `aiMessage().toolExecutionRequests()` 后，才进入 `executeToolBatch`。所以 `tool_call` 事件是在**该轮响应结束后**才发，不是模型一开始想调就发（详见 [32 §2.6](32-工具调用机制与面试拷打.md)）。

### 5.3 流式下工具调用的分片示意

```
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":null,
       "tool_calls":[{"index":0,"id":"call_abc","type":"function",
       "function":{"name":"searchKnowledge","arguments":""}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,
       "function":{"arguments":"{\"query\":"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,
       "function":{"arguments":"\"年假政策\"}"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
```

拼装后等价于：

```json
{"id":"call_abc","type":"function","function":{"name":"searchKnowledge","arguments":"{\"query\":\"年假政策\"}"}}
```

### 5.4 浏览器与代理的坑

1. **浏览器原生的 `EventSource` 只支持 GET**，而 Chat Completions 是 POST。浏览器直连 OpenAI 流式要用 `fetch` + `ReadableStream` 手动解析 SSE；`EventSource` 适合「后端推给前端」的 GET 流。
2. **反向代理可能缓冲响应**：Nginx 默认可能等整个响应结束才转发，看起来像「不流式」。要关 buffering（如 `proxy_buffering off`），并配置足够长的读超时。
3. **压缩可能破坏增量**：某些中间件对 `text/event-stream` 做 gzip 后仍能流，但会增加不确定性；排障时先关。
4. **必须有心跳**：长思考/长工具调用期间没有字节，连接可能被 LB 或浏览器掐断。本项目在前端 SSE 侧有 `SseHeartbeat`，OpenAI 请求侧则依赖 provider 的心跳。
5. **客户端断开 ≠ 模型停止**：断的是连接，服务端可能继续生成并计费。要真正停止，需要用协议支持的中断/取消能力，或接受浪费。

### 5.5 本项目映射

| 协议层 | 本项目位置 |
|---|---|
| 发起流式请求 | `AgentRunner.streamOnce`（`AgentRunner.java:514`） |
| 文本增量回调 | `StreamingChatResponseHandler.onPartialResponse` → `sink.onDelta` |
| 完整响应 | `onCompleteResponse` → `CompletableFuture` 完成 |
| 工具调用拼装 | LC4j OpenAI 适配器完成；本项目只读 `aiMessage()` |
| 推给前端 | `AgentStreamHub` + SSE 事件（见 31） |

---

## 6. 工具调用字段速览

只列字段，不展开机制；完整版见 [32](32-工具调用机制与面试拷打.md)。

请求侧：

```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "searchKnowledge",
        "description": "在用户当前会话关联的知识库中检索相关内容。当问题可能与用户上传的资料/知识库有关时调用。",
        "parameters": {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "检索关键词或问题"}
          },
          "required": ["query"],
          "additionalProperties": false
        }
      }
    }
  ],
  "tool_choice": "auto"
}
```

响应侧：

```json
{
  "role": "assistant",
  "content": null,
  "tool_calls": [
    {
      "id": "call_abc",
      "type": "function",
      "function": {"name": "searchKnowledge", "arguments": "{\"query\":\"年假\"}"}
    }
  ]
}
```

回填侧：

```json
{"role": "tool", "tool_call_id": "call_abc", "content": "检索结果文本……"}
```

三条硬规则：

1. `tools` 每轮重发，服务端不记忆；
2. `arguments` 是 **JSON 字符串**，不是对象；坏 JSON 由宿主兜底；
3. `tool_calls[].id` 与 `role=tool.tool_call_id` 必须一一配对，否则 400。

---

## 7. token、上下文与成本

### 7.1 三种 token 口径

| 口径 | 来源 | 用途 |
|---|---|---|
| 真实 token | 响应里的 `usage` | 结算、监控、成本分析 |
| 估算 token | 字符数 × 系数 | 构造 prompt 前的预算裁剪 |
| 模型上限 | provider 文档 | 判断请求是否可能超窗 |

**估算不能替代结算**：不同 tokenizer 对中文、英文、代码、JSON 的切分差异很大。本项目的 `tokenPerChar=1.5` 是**偏保守的估算系数**（`MemoryProperties.java:41`），只用于预算，不用于账单。

### 7.2 上下文窗口 ≠ 输出上限

```
模型上下文窗口 = 输入 token + 输出 token 的硬上限
max_tokens     = 只限制输出
```

- 输入超窗：400 `context_length_exceeded`；
- 输出撞 `max_tokens`：200 + `finish_reason=length`；
- **模型上下文很大不代表可以乱塞**：1M 上下文也按 token 收费，长历史就是线性成本。

本项目的做法：即使模型上下文很大，也把可用预算压到 16K（`MemoryProperties.java:25`），故意让裁剪发生，控制成本。预算公式见 `MemoryProperties.java:47-53`：`(16000 − 2048 − 系统提示词) × 0.8`。

### 7.3 成本公式

```
成本 ≈ (prompt_tokens − cached_tokens) × 输入单价
     + cached_tokens × 缓存输入单价
     + completion_tokens × 输出单价
```

单价按「每 1M token」报价，具体数值以 provider 价目表为准。两个常见误区：

- **只算 `content`**：工具调用参数、思维链 token 都算输出；
- **忽略缓存**：命中前缀缓存时，输入单价可能显著更低；是否命中看 `prompt_tokens_details.cached_tokens`。

### 7.4 prompt caching 的命中条件

通用规则（各 provider 细节不同）：

- **前缀必须完全一致**：system、工具 schema、历史消息的顺序和内容都不能变；
- **动态内容放后面**：把每轮都会变的用户消息放在最后，前面保持稳定；
- **缓存有 TTL**：隔太久会失效；
- **缓存读写可能分别计价**，不一定「免费」。

本项目每轮的 `messages` 是「历史 + 新消息」追加式，前缀相对稳定，**有条件命中**；但项目没有单独统计 `cached_tokens`，也没有为缓存做显式布局，这是可观测性上的缺口。

### 7.5 工具 schema 的隐性成本

每个工具的名称、描述、JSON Schema 都会作为输入 token 每轮发送。三个工具看起来不多，但如果描述写成长篇说明、参数很多，成本会持续累积。工程上的取舍：

- 工具描述要写清触发场景，但不要写成教程；
- 工具很多时做路由，只下发相关工具；
- 用离线评测验证「减工具」是否损失准确率。

---

## 8. 错误、限流与重试

### 8.1 重试矩阵

| 情况 | 能重试吗 | 怎么做 |
|---|---|---|
| 400 参数错 / 上下文超限 | 否 | 改请求；截断历史或调大窗口 |
| 401/403 | 否 | 修 key/权限 |
| 404 模型不存在 | 否 | 修 model 名或 baseUrl |
| 429 限流 | 是 | 指数退避 + 抖动；优先遵守 `Retry-After` / `x-ratelimit-*` |
| 429 配额不足 | 否 | 充值/换 key；重试无用 |
| 500/502/503/504 | 是 | 退避重试；设最大次数 |
| 连接超时/读超时 | 是 | 退避重试；流式要防重复输出 |
| 流式中途断开 | 谨慎 | 重新发起会重新生成；已输出部分要丢弃或去重 |
| 工具调用已执行后失败 | **禁止盲目重跑** | 用幂等键/审批；绝不能因为重试把副作用做两遍 |

通用退避策略：`delay = min(base × 2^attempt, maxDelay) + randomJitter`，并尊重服务端的 `Retry-After`。不要固定隔 1 秒猛冲。

### 8.2 流式重试的特殊问题

非流式失败：没有输出，重试成本只是钱。
流式失败：可能已经给用户显示了半句话，重试会**从头生成**，直接拼接会出现重复内容。

正确做法：

- 重试前丢弃未完成的输出（或标记为「重新生成」）；
- 如果这一轮已经执行过工具，**不能**简单重放整个 run；要靠幂等键/审批/状态机收敛（见 [12-中断与恢复的状态机](12-中断与恢复的状态机.md)）；
- 记录 `request id`，方便和服务商对账。

### 8.3 本项目现状

- **没有自动重试**。模型调用抛异常时，`AgentRunner.java:248-256` 会 `failRun`、清中断标志、推 `ERROR` 事件，并带 `retryable=false`；
- 单次 LLM 超时 60s（`ChatModelProperties.java:61`）；
- 工具执行没有统一超时（见 [32 §5.6](32-工具调用机制与面试拷打.md)）；
- 副作用安全不靠 HTTP 重试，靠审批 + 幂等键（`agent_step.idempotencyKey`）。

这是有意取舍：**先保证「不会重复产生副作用」，再考虑「自动恢复体验」**。

---

## 9. 兼容性与厂商差异

### 9.1 「OpenAI 兼容」兼容了什么

通常兼容：

- 端点路径 `/chat/completions`；
- 请求体形状：`model` / `messages` / `stream` / `tools`；
- 响应体形状：`choices[].message` / `usage` / `finish_reason`；
- Bearer 认证。

**不保证兼容**：

| 维度 | 差异 |
|---|---|
| 参数支持 | 有的模型不支持 `temperature`/`top_p`/`stop`；有的不认 `max_completion_tokens` |
| 默认值 | `temperature`、`max_tokens`、`tool_choice` 的默认值可能不同 |
| 工具能力 | `strict`、`parallel_tool_calls`、`tool_choice=required` 支持不一 |
| 流式 usage | 有的默认带，有的要 `stream_options`，有的不支持 |
| 错误体 | 字段名、HTTP 状态码、是否包一层都可能不同 |
| 推理字段 | `reasoning_content`、`reasoning_tokens` 命名和出现位置不同 |
| 限流 | 配额口径、`Retry-After` 头、并发限制不同 |
| 模型 ID | 同一个模型在不同平台可能叫不同名字 |

### 9.2 OpenAI / Anthropic / Gemini 报文对照

| 维度 | OpenAI Chat Completions | Anthropic Messages | Gemini |
|---|---|---|---|
| 端点 | `/v1/chat/completions` | `/v1/messages` | `generateContent` |
| system | `role: system/developer` | 顶层 `system` | `systemInstruction` |
| 助手工具调用 | `message.tool_calls[]` | `content[]` 里的 `tool_use` 块 | `parts[].functionCall` |
| 工具参数 | `arguments` 是 **JSON 字符串** | `input` 是 **对象** | `args` 是 **对象** |
| 工具结果 | `role: tool` + `tool_call_id` | `user` 消息里的 `tool_result` 块 | `functionResponse` |
| 流式 | SSE `data:` + `[DONE]` | SSE `event:` + `data:` | 流式 JSON/SSE |
| usage | `prompt_tokens` / `completion_tokens` | `input_tokens` / `output_tokens` | `usageMetadata` |
| 结束原因 | `finish_reason` | `stop_reason` | `finishReason` |

**注意**：Anthropic 等厂商也提供「OpenAI 兼容层」，但通常只覆盖子集，复杂特性（多模态、工具细节、缓存）仍要看原生协议。LC4j 的做法是每个 provider 一个模块，把这些差异翻译成统一的 `AiMessage` / `ToolExecutionRequest`。

### 9.3 本项目映射

| 行为 | 位置 |
|---|---|
| 用 OpenAI 兼容模块直连 DeepSeek | `ChatModelConfig.java:39-62` |
| `baseUrl` / `modelName` 可换 | `ChatModelProperties.java:27`、`:51` |
| 同一模型跨平台不同 ID 的坑 | `ChatModelProperties.java:45-51`（官方 `deepseek-flash` vs 百炼 `deepseek-v4.1-flash`） |
| provider 字段只用于日志 | `ChatModelProperties.java:42` |
| 非流式路径不传 tools | `ChatServiceImpl.java:91` |
| 流式 Agent 路径传 tools | `AgentRunner.java:192-193` |
| token 用量记录 | `AgentRunner.java:205-213` → `ai_call_log` |
| 模型调用失败 | `AgentRunner.java:248-256` → `FAILED` + `ERROR(retryable=false)` |

---

## 10. 本项目配置速查

| 项 | 值 | 来源 |
|---|---|---|
| baseUrl | `https://api.deepseek.com` | `ChatModelProperties.java:27` |
| modelName | `deepseek-flash` | `ChatModelProperties.java:51` |
| API key | 环境变量 `DEEPSEEK_API_KEY`，缺失则不装配 Bean | `ChatModelProperties.java:30-36`、`ChatModelConfig.java:32-36` |
| temperature | `0.3`（未标定） | `ChatModelProperties.java:56` |
| timeout | `60s` | `ChatModelProperties.java:61` |
| top_p / max_tokens / stop / response_format | 未设置，走 provider 默认 | `ChatModelConfig.java:39-62` |
| 上下文预算 | 16000 token | `MemoryProperties.java:25` |
| 输出预留 | 2048 token | `MemoryProperties.java:30` |
| 安全系数 | 0.8 | `MemoryProperties.java:35` |
| token 估算 | 1.5 token/字符（偏保守，未标定） | `MemoryProperties.java:41` |
| 工具 schema 下发 | 仅 `/chat/stream` Agent 路径 | `AgentRunner.java:164`、`:192-193` |
| 模型调用失败 | run `FAILED`，`ERROR.retryable=false` | `AgentRunner.java:248-256` |
| 自动重试 | **无** | 同上 |

---

## 11. 面试题（26 问）

> 用法：先背第 0 节的十二句，再按岗位挑。每题都尽量带「为什么」和「代价」，不要只背字段名。

### 11.1 协议与请求（Q1–Q8）

**Q1：调用 Chat Completions 最少要传什么？为什么每次都要把历史重发一遍？**

最少 `model` + `messages`。服务端是无状态的，不保存会话；每个请求自带全部上下文，这样服务端才能水平扩容、任意副本都能处理。代价是历史越长、token 成本和延迟越高，所以应用层必须做记忆裁剪（本项目的 `MemoryBuilder`）。

**追问：为什么不把会话存在服务端？**

provider 的核心职责是推理，不承担你的业务存储；会话归属、租户隔离、审计、删除权都应掌握在应用侧。

**Q2：`system`、`user`、`assistant`、`tool` 四种 role 分别干什么？为什么 assistant 的 `content` 可以是 null？**

`system` 是开发者全局指令；`user` 是用户输入；`assistant` 是模型历史回复；`tool` 是宿主回填的工具结果，必须带 `tool_call_id`。当模型这一轮只想调工具、不对用户说话时，它输出 `content: null` + `tool_calls`，所以 `content` 可能为空。**注意 `developer` 是 OpenAI 新角色，兼容服务未必支持。**

**追问：能把工具结果直接拼进 user 消息吗？**

不能。`role=tool` + `tool_call_id` 是协议要求的配对结构；拼进 user 会破坏「动作—观察」的对应关系，模型难以区分用户输入和工具返回值，续跑重放也会拼错。

**Q3：`temperature` 和 `top_p` 有什么区别？为什么不建议同时调？**

`temperature` 缩放 logits，改变分布的陡峭程度；`top_p` 先按累计概率截断候选池，再在池内采样。两者都在改变分布，同时调会让「效果变化到底是谁造成的」无法归因；工程上一般固定一个、调另一个。RAG 事实问答用低温（如本项目的 0.3），创意场景才用高温。

**追问：`temperature=0` 是不是就完全确定了？**

不是。浮点运算、批处理、后端版本/硬件、`system_fingerprint` 变化都可能让输出不同。`seed` 也只是 best effort。需要可复现时，记录 `seed` + `system_fingerprint` + 完整请求，而不是假设 0 就确定。

**Q4：`max_tokens` 和上下文窗口是什么关系？输出被截断时怎么发现？**

上下文窗口限制「输入 + 输出」的总量；`max_tokens` 只限制输出。输入超窗会 400（常见 `context_length_exceeded`）；输出撞顶会正常返回，但 `finish_reason=length`。宿主必须检查 `finish_reason`，不能只看 `content` 非空就当回答完整。

**追问：本项目为什么有 `reserveOutput=2048`，却没设 `max_tokens`？**

`reserveOutput` 是**预算裁剪**用的估算值，用来给历史留空间；`max_tokens` 是实际发给 API 的硬限制。本项目没设后者，所以输出上限走 provider 默认值。这是两个不同层面的东西，也是当前的成本缺口。

**Q5：`presence_penalty` 和 `frequency_penalty` 有什么区别？**

`presence_penalty` 对「至少出现过一次」的 token 惩罚，鼓励模型换话题/换表达；`frequency_penalty` 按出现次数惩罚，主要抑制复读。两者范围常见 -2 到 2。RAG 场景要谨慎：惩罚可能让模型偏离原文用词，影响忠实度。

**Q6：`response_format` 的 `json_object` 和 `json_schema` 有什么区别？`strict` 能保证什么？**

`json_object` 只保证输出是合法 JSON，不保证字段符合你的业务结构；`json_schema` + `strict` 用约束解码尽量保证符合 schema。但 strict 只保证**语法/结构**，不保证**语义正确**（模型仍可能填错值）；而且 strict 对 schema 有子集限制，各 provider 支持程度不同。

**追问：为什么不用 `response_format` 代替工具调用？**

工具调用是「模型请求宿主执行动作」的协议，有 id、有回填、有循环；`response_format` 只是让模型输出结构化文本，没有执行环节。两者解决的问题不同。

**Q7：`seed` 和 `system_fingerprint` 是干什么的？**

`seed` 是请求可复现的「尽力」提示；`system_fingerprint` 是后端配置/版本指纹。同样的 `seed` + 同样的请求，如果 `system_fingerprint` 变了，输出仍可能不同。它们用于排障和 A/B，不是确定性保证。

**Q8：请求里的 `tools[].function.parameters` 是 Java 类型还是 JSON Schema？谁生成的？**

是 JSON Schema。本项目的 `AgentToolRegistry` 用 `ToolSpecifications.toolSpecificationFrom(method)` 从 `@Tool` 方法反射生成；无参工具会省略整个 `parameters`。工具 schema 每轮随请求重发，服务端不记忆。

### 11.2 响应与流式（Q9–Q14）

**Q9：非流式响应的结构是什么？`finish_reason` 有哪些值？**

顶层有 `id` / `object` / `created` / `model` / `choices` / `usage`；`choices[].message` 是助手消息，`choices[].finish_reason` 说明为什么停。常见值：`stop`（正常）、`length`（截断）、`tool_calls`（要调工具）、`content_filter`（被过滤）、`function_call`（旧版）、`null`（流式中间态）。

**追问：`finish_reason=content_filter` 怎么办？**

展示拒绝/兜底话术，记录日志；不要原样重试同一请求，通常结果一样。要改的是输入内容或产品策略。

**Q10：`usage` 里的 `prompt_tokens` 和 `completion_tokens` 分别包含什么？流式怎么拿？**

`prompt_tokens` 包含 system、历史、当前消息，以及**工具 schema 文本**；`completion_tokens` 包含正文、工具调用参数 JSON，推理模型还包括思维链 token。流式默认不带 `usage`，OpenAI 需要 `stream_options.include_usage=true`，并在 `[DONE]` 前多一个 `choices: []` 的 chunk 携带 `usage`；兼容服务支持情况不一。

**追问：为什么不能只按字符数算钱？**

tokenizer 对中文、英文、代码、JSON 的切分不同；同一个字符数对应的 token 数差很多。字符估算只适合做预算，结算必须用 `usage`。本项目的 1.5 token/字符是保守估算，不是账单口径。

**Q11：SSE 流式响应长什么样？`[DONE]` 是什么？浏览器为什么不能用 `EventSource` 调 OpenAI？**

每个事件以 `data: <json>` 开头、空行结束，最后 `data: [DONE]` 是哨兵。`EventSource` 只支持 GET，而 Chat Completions 是 POST；浏览器直连要用 `fetch` + `ReadableStream` 自己解析 SSE。`EventSource` 适合「后端 GET 推给前端」的场景。

**追问：为什么线上看到「不流式」？**

常见是反向代理缓冲（Nginx `proxy_buffering`）、压缩中间件、或客户端一次性读完 `response.text()`。要关 buffering、用增量读取，并配置长超时。

**Q12：流式下文本和工具调用怎么拼装？为什么不能一收到 `tool_calls` 就执行？**

文本直接拼 `delta.content`；工具调用按 `tool_calls[].index` 分组，`id`/`name` 通常首片出现，`arguments` 是后续分片，要拼成完整 JSON。只有 `finish_reason=tool_calls` 且参数完整时才能执行；提前执行会拿到半截 JSON，也可能漏掉同批其它调用。本项目在 `onCompleteResponse` 后才进入 `executeToolBatch`。

**Q13：流式中断了怎么重试？**

流式重试是「重新生成」，已经展示的半句话会重复。重试前要丢弃未完成输出或标记重新生成；如果这一轮已经执行过工具，绝不能简单重放整个 run，要靠幂等键/审批/状态机收敛。非流式重试相对简单，但也要防重复成本。

**Q14：为什么用流式？代价是什么？**

收益是首 token 延迟低、用户能尽早看到反馈，长回答体验更好。代价是实现复杂：分片拼装、工具调用延迟可见、流中错误处理、代理缓冲、usage 获取、连接生命周期管理。本项目的取舍见 [31-流式输出SSE](31-流式输出SSE.md)。

### 11.3 工具与结构化（Q15–Q18）

**Q15：`tools`、`tool_choice`、`parallel_tool_calls` 怎么配合？**

`tools` 声明可用函数；`tool_choice` 控制策略：`auto`（模型自由）、`none`（禁止）、`required`（至少调一个）、指定函数（强制某个）；`parallel_tool_calls` 控制是否允许多个工具调用同时出现。本项目用 `auto`，因为同一入口要兼容闲聊和工具场景；副作用安全靠审批，不靠强制调用。

**追问：为什么不用 `required`？**

`required` 会把「你好」这种闲聊也变成工具调用，模型还可能乱填参数；它适合「明确知道这一轮必须调工具」的场景，不适合开放对话入口。

**Q16：`assistant.tool_calls` 和 `role=tool` 的配对为什么是硬约束？**

每个 `tool_call.id` 必须有且只有一条 `role=tool` 消息，顺序紧随其后。缺失、孤儿或 id 不匹配，OpenAI 兼容服务端通常直接 400；续跑重放时也会拼出非法请求。本项目 `MemoryBuilder` 因此不把工具消息放进跨 run 的历史，`ResumePlanner` 只重放完整轮（见 12）。

**Q17：工具 schema 开 `strict` 能保证参数正确吗？**

只保证结构/类型尽量符合 schema，不保证语义正确。模型仍可能选错工具、填错业务值。宿主必须做业务校验；敏感参数（userId、知识库范围）不要进 schema，用 `@ToolMemoryId` 从 `RunContext` 注入。

**Q18：模型一次返回多个工具调用，宿主怎么处理？**

本项目顺序执行：每个工具独立 `seq`、独立意图行、独立 `tool_call`/`tool_result`；遇到需审批的工具整批暂停；无论成功失败，每个 `tool_call` 都必须有对应结果，否则下一轮请求不合法。不做并行的原因是副作用顺序、幂等认领和状态机复杂度；只读工具理论上可并行，但要引入批次屏障。

### 11.4 工程与兼容（Q19–Q26）

**Q19：「OpenAI 兼容」到底兼容了什么？最容易踩的不兼容点是什么？**

兼容的是端点形状、请求/响应字段和 Bearer 认证；不保证参数支持、默认值、strict/parallel 能力、流式 usage、错误体、推理字段、限流策略一致。接新 provider 时，先跑一个最小请求 + 一个坏请求 + 一个流式请求 + 一个工具请求，把行为差异摸清。

**追问：换 provider 要改什么？**

兼容端点通常只改 `baseUrl` + `modelName`（本项目就是这么设计的）；非兼容协议（Anthropic/Gemini 原生）要换 provider 模块，由 LC4j 翻译成统一 `AiMessage`。模型 ID 不能混用，例如官方 `deepseek-flash` 和百炼 `deepseek-v4.1-flash`。

**Q20：OpenAI、Anthropic、Gemini 的报文主要差在哪？**

system 位置、工具调用字段名、参数是字符串还是对象、工具结果的角色、流式事件格式、usage 字段名、finish reason 名称都不同（见 9.2 对照表）。不要在业务代码里直接解析某家的原始 JSON；用适配层统一成内部模型。

**Q21：429、5xx、400 分别怎么处理？**

429：区分限流和配额不足；限流可指数退避 + 抖动，遵守 `Retry-After`；配额不足重试无意义。5xx/超时：可退避重试，设最大次数。400/401/403/404：不重试，改请求或配置。流式重试还要处理半截输出和已执行的工具。

**Q22：`context_length_exceeded` 是谁的责任？怎么防？**

是客户端的责任：服务端只告诉你超了。要防：按预算裁剪历史、限制工具结果长度、必要时摘要、减少工具 schema、给输出留 `max_tokens`。本项目把上下文压到 16K（`MemoryProperties.java:25`），工具结果截断到 4000 字符（`AgentRunner.java:587`）；但 run 内新增的工具结果不再按 16K 预算裁剪，这是已知边界（见 32 §7）。

**Q23：prompt caching 的命中条件是什么？对成本有什么影响？**

前缀必须完全一致（system、工具 schema、历史顺序和内容稳定），动态内容放后面；缓存有 TTL，可能读写分别计价。命中后看 `usage.prompt_tokens_details.cached_tokens`，输入单价通常更低。本项目前缀是追加式，有条件命中，但没有单独统计，也没有为缓存做显式布局。

**Q24：推理模型（o 系列 / DeepSeek Reasoner）有哪些参数坑？**

常见坑：`temperature`/`top_p` 可能不支持或被忽略；输出上限要用 `max_completion_tokens`，且包含思维链 token；思维链 token 按输出计费；响应里可能出现 `reasoning_content`，不要直接展示给用户；延迟和成本更高。本项目的模型可能返回 `reasoning_content`（`ChatModelProperties.java:16-17` 标注为待验证），当前链路没有消费它。

**Q25：API Key 应该怎么管？**

只放后端，用环境变量/密钥管理服务注入，不进版本库、不进前端、不进日志。本项目 `DEEPSEEK_API_KEY` 由环境变量注入，缺失时不装配模型 Bean（`ChatModelConfig.java:32-36`）；`logRequests` 默认关闭，因为开了会把 prompt 写进日志。还要支持轮换、最小权限、按环境隔离。

**Q26：怎么监控一次模型调用的成本和质量？**

成本：记录 `usage`（prompt/completion/cached/reasoning）、模型名、耗时、首 token 延迟，按 run/用户/模型聚合。质量：离线评估集 + 线上抽样（人工或 LLM-as-judge），关注截断率、工具调用成功率、重试率、429 比例。本项目 `ai_call_log` 记 token 和请求/响应快照（`AgentRunner.java:205-213`），见 [50-可观测性](50-可观测性.md)；检索质量见 [60-离线评测](60-离线评测.md)。

---

## 12. 边界与坑

| # | 坑 | 现象/后果 | 应对 |
|---|---|---|---|
| 1 | 把 `messages` 当有状态 | 第二轮模型「失忆」 | 每轮重发历史；记忆裁剪在应用层 |
| 2 | 只传 user，不传 system | 模型风格/边界不稳定 | system 放全局约束；别把业务数据全塞进去 |
| 3 | `arguments` 当对象用 | JSON 解析/绑定失败 | 它是字符串；宿主解析并兜底坏 JSON |
| 4 | 删掉 assistant 的 `tool_calls`，只回填 `role=tool` | 400；语义断裂 | 配对保留，id 一一对应 |
| 5 | 把 `[DONE]` 当 JSON | 解析异常 | 先判字面量再 parse |
| 6 | 流式一收到工具分片就执行 | 半截 JSON / 漏调用 | 等 `finish_reason=tool_calls` + 拼完整 |
| 7 | 以为 `temperature=0` 确定 | 复现失败 | 记录 `seed` + `system_fingerprint`；接受概率性 |
| 8 | 以为 `max_tokens` 能防输入超窗 | 仍然 400 | 输入侧单独裁剪；输出/输入分开治理 |
| 9 | 只看 `content` 不看 `finish_reason` | 截断/工具调用被当最终答案 | 必查 `finish_reason` |
| 10 | 流式不取 `usage` | 成本无法核算 | `stream_options.include_usage`；不支持就非流式补采或估算 |
| 11 | `json_object` 当 schema 用 | 字段缺/类型错 | 用 `json_schema` 或宿主校验 |
| 12 | 以为 `strict` 保证业务正确 | 参数合法但语义错 | 业务校验；敏感参数不进 schema |
| 13 | `max_tokens` / `max_completion_tokens` 混用 | 新模型 400 或旧服务 400 | 按模型/ provider 文档选，接新端点先实测 |
| 14 | 在推理模型上传 `temperature` | 400 或被忽略 | 查模型支持矩阵；用 `reasoning_effort` 等替代 |
| 15 | 忽略工具 schema 的输入成本 | 每轮都在为工具描述付费 | 精简描述；工具多时路由 |
| 16 | 代理缓冲 SSE | 看起来不流式 | 关 `proxy_buffering`；增量读取 |
| 17 | 流式断开后自动重试 | 输出重复、工具重复执行 | 丢弃半截输出；副作用走幂等/审批 |
| 18 | 429 无脑重试 | 越重试越限流 | 退避 + 抖动 + `Retry-After`；区分配额不足 |
| 19 | 兼容厂商错误体不同 | 解析崩溃/掩盖真实错误 | 接新 provider 先打错误体；统一适配 |
| 20 | 把 key 写进前端/仓库/日志 | 泄露、被盗刷 | 环境变量/密钥管理；`logRequests` 默认关 |
| 21 | 本项目未设 `max_tokens` | 输出成本/延迟不可控 | 已知缺口，按场景补 |
| 22 | 本项目无自动重试 | 远端抖动直接失败 | `retryable=false` 是提示；需要时在上层加退避 |
| 23 | 本项目不统计缓存命中 | 成本优化无依据 | 采 `cached_tokens`；稳定前缀布局 |
| 24 | 本项目未消费 `reasoning_content` | 思维链成本已付但未利用/未展示 | 明确产品策略：不展示、不入库或单独存 |

---

## 13. 一页速查

### 13.1 最小请求

```json
{
  "model": "deepseek-flash",
  "messages": [
    {"role": "system", "content": "你是……"},
    {"role": "user", "content": "……"}
  ]
}
```

### 13.2 参数速查

| 参数 | 一句话 |
|---|---|
| `temperature` | 随机性；越高越发散 |
| `top_p` | 候选池；越小越保守 |
| `max_tokens` / `max_completion_tokens` | 只限输出；后者含推理 token |
| `stop` | 命中即停；结果不含停止序列 |
| `n` | 候选数；成本 × n |
| `presence_penalty` / `frequency_penalty` | 鼓励新话题 / 抑制复读 |
| `seed` | 尽力可复现，非保证 |
| `response_format` | text / json_object / json_schema |
| `tools` / `tool_choice` | 工具清单 / 调用策略 |
| `parallel_tool_calls` | 是否允许多个工具调用 |
| `stream` / `stream_options` | SSE 流式 / 流式带 usage |

### 13.3 响应速查

| 字段 | 看什么 |
|---|---|
| `choices[0].message.content` | 回答正文；可能为 null |
| `choices[0].message.tool_calls` | 要调用的工具；有它就不是最终答案 |
| `choices[0].finish_reason` | `stop` / `length` / `tool_calls` / `content_filter` |
| `usage.prompt_tokens` | 输入账单（含工具 schema） |
| `usage.completion_tokens` | 输出账单（含工具参数/思维链） |
| `usage.prompt_tokens_details.cached_tokens` | 缓存命中（若有） |
| `system_fingerprint` | 后端版本指纹，排障用 |

### 13.4 错误速查

| 码 | 含义 | 重试 |
|---|---|---|
| 400 | 参数错/上下文超限 | 否 |
| 401/403 | 认证/权限 | 否 |
| 404 | 路径/模型名错 | 否 |
| 429 | 限流/配额 | 限流可退避；配额不可 |
| 500/502/503/504 | 服务端/网关 | 可退避 |
| 超时 | 网络/长生成 | 可重试，流式防重复 |

### 13.5 本项目关键数字

| 项 | 值 |
|---|---|
| 模型 / 端点 | `deepseek-flash` / `https://api.deepseek.com` |
| temperature | 0.3（未标定） |
| timeout | 60s |
| 上下文预算 | 16000 token |
| 输出预留 | 2048 token |
| 安全系数 | 0.8 |
| token 估算 | 1.5 token/字符（未标定） |
| 工具结果截断 | 4000 字符 |
| 自动重试 | 无 |

### 13.6 最后三句（收尾用）

1. **Chat Completions 是「无状态消息数组进、助手消息出」的协议；记忆、成本、重试都在宿主侧。**
2. **先看 `finish_reason` 和 `usage`，再看 `content`；只看内容最容易把截断和工具调用误判成最终答案。**
3. **「OpenAI 兼容」只保证形状，不保证行为；参数支持、默认值、错误体必须按 provider 实测。**

---

## 14. 自测：能脱稿答完再进面试

1. 写出一个最小 Chat Completions 请求，并说明为什么每轮都要重发历史。
2. 说出 `system` / `user` / `assistant` / `tool` 的职责，以及 assistant `content` 为 null 的场景。
3. 解释 `temperature` 与 `top_p` 的区别，并说明为什么 `temperature=0` 不保证确定。
4. 区分上下文窗口、`max_tokens`、`max_completion_tokens`，并说出各自撞顶后的表现。
5. 说出 `finish_reason` 的五个常见值及宿主应对。
6. 解释 `usage` 三个 token 字段各含什么，流式下怎么拿到 usage。
7. 画出 SSE 流式报文骨架，说明 `[DONE]`、`finish_reason`、`tool_calls` 分片的位置。
8. 解释为什么工具调用要等响应结束才能执行。
9. 说出 429/5xx/400 的重试策略，以及流式重试的两个额外风险。
10. 解释「OpenAI 兼容」兼容了什么、不保证什么，并说出至少四个差异点。
11. 说出 prompt caching 的命中条件和成本影响。
12. 说出本项目 `temperature`、`timeout`、上下文预算、token 估算系数的值及来源。
