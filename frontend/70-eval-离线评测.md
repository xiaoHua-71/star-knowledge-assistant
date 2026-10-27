# 70 · 离线评测（dev-only）

> **这不是产品功能，不建议做进用户界面。** 它给开发者/管理员在本地触发一次检索质量评估，
> 由后端写一份报告文件。生产环境应关闭。

## 一、开关与鉴权

| 项 | 值 |
|---|---|
| 装配开关 | `rag.eval.http-enabled=true`（默认 true，生产必须改 false） |
| 鉴权 | 必须登录（未登录 `40100`） |
| 数据范围 | 使用**评测专用用户/知识库**，不读取调用者的任何数据 |
| 前缀 | `/api/rag/eval` |

> 首次触发会同步导入 94 篇语料（本地 ONNX，本机实测约 75 秒），HTTP 客户端要放宽超时。

## 二、接口

### 1. 触发评估

`POST /rag/eval`

```json
{
  "variants": ["all-on", "no-hybrid"],
  "caseIds": ["java-proxy-01"]
}
```

两个字段都可省略：

- `variants` 为空 → 用 `rag.eval.default-variants`
  （`all-on`、`no-hybrid`、`no-diversity`、`no-rerank`、`topk-10`）
- `caseIds` 为空 → 跑评估集里全部启用用例

返回**当前状态**（异步执行，不是最终报告）：

```json
{
  "code": 0,
  "data": {
    "state": "PREPARING",
    "message": "已提交，等待执行",
    "startTime": "1761283200000",
    "totalVariants": 2,
    "doneVariants": 0,
    "totalCases": 0,
    "doneCases": 0,
    "reportPath": null
  }
}
```

> `startTime` 等 Long 字段是**字符串**（全局 Jackson 配置），前端不要直接做数值运算。

**错误**：

| code | 场景 |
|---|---|
| `40000` | 变体 id 写错（返回可用 id 列表） |
| `40100` | 未登录 |
| `50001` | 已有评估在运行 |

### 2. 查询状态

`GET /rag/eval/status`

`state` 取值：`IDLE` / `PREPARING`（准备语料）/ `RUNNING` / `SUCCESS` / `FAILED`。
`doneCases` / `totalCases` 可用于进度条；`message` 可原样展示。

### 3. 最近一次报告

`GET /rag/eval/report/latest`

没有报告时返回 `40400`。正常返回 `RagEvalReport`：

```json
{
  "runId": "20261024-153000-123",
  "startTime": 1761283200000,
  "costMillis": 42000,
  "kbId": "9",
  "collectionName": "star_kb_9",
  "collectionChanged": false,
  "evalSetFile": "docs/rag-eval/eval-set.json",
  "corpusDocs": 94,
  "corpusChunks": 1800,
  "corpusComplete": true,
  "staleDocs": 0,
  "rerankAvailable": false,
  "caseCount": 30,
  "caseIds": ["java-passing-01", "java-bigdecimal-01", "..."],
  "previousRunId": "20261024-120000-000",
  "variants": [
    {
      "variantId": "all-on",
      "label": "全开（生产基线）",
      "options": "hybrid=true, vectorRecall=15, minScore=0.8, ..., topK=5",
      "metrics": {
        "total": 30, "failed": 0,
        "hitRate": 0.9, "mrr": 0.81, "recall": 0.78,
        "precision": 0.42, "diversity": 0.73,
        "avgReturned": 5.0, "emptyRate": 0.0,
        "p50Millis": 120, "p95Millis": 260
      },
      "cases": [ /* 逐条明细，见下 */ ],
      "deltaVsPrevious": { "hitRate": 0.03, "mrr": 0.01 }
    }
  ]
}
```

`cases[]` 每条：

```json
{
  "caseId": "java-proxy-01",
  "query": "JDK 动态代理和 CGLIB 有什么区别？",
  "expectedSources": ["java/basis/proxy.html"],
  "returnedSources": ["https://javaguide.cn/java/basis/proxy.html", "..."],
  "hitExpected": ["java/basis/proxy.html"],
  "notInIndex": [],
  "missed": [],
  "hitRank": 1,
  "hitCount": 1,
  "returnedCount": 5,
  "distinctSources": 4,
  "costMillis": 110,
  "error": null
}
```

### 4. 导入 / 重建语料

`POST /rag/eval/corpus/rebuild?full=true`

- `full=false`（默认）：只导入缺失/失败的文档，已索引的跳过。
- `full=true`：删除评测知识库（含 Milvus 集合）后整库重建。**耗时数分钟**。

返回 `RagCorpusSummaryVO`：

```json
{
  "kbId": "9",
  "collectionName": "star_kb_9",
  "kbName": "rag-eval-corpus",
  "corpusFiles": 94,
  "indexedDocs": 94,
  "chunks": 1800,
  "imported": 94,
  "skipped": 0,
  "staleDocs": 0,
  "complete": true,
  "failures": []
}
```

`complete=false` 时先看 `failures`，修好再触发评估；不完整语料上的命中率会低估真实水平。

## 三、给前端的最小建议

如果要做内部调试页：

1. 顶部放「触发评估」按钮 + 变体多选（默认全选 5 个）。
2. 触发后每 2 秒轮询 `/status`，展示 `state` 与 `doneCases/totalCases`。
3. 成功后拉 `/report/latest`，先渲染变体对比表，再折叠展示未命中明细。
4. `corpusComplete=false`、`emptyRate>0.2`、`collectionChanged=true` 时给黄色警告。

不建议把评测接口暴露给普通用户，也不要在生产打开 `rag.eval.http-enabled`。
