# knowledge · 知识库管理

> ⬜ **状态：设计已定稿，接口尚未实现。** 文档解析走 LangChain4j 官方的 parser 模块（已验证可拉取）。

三层结构：

```
分类（category，树形，可选）
  └─ 知识库（knowledge_base）        ← 检索的独立单元，每个库一个向量集合
       └─ 文档（knowledge_document）  ← 用户上传的文件或网页
            └─ 切片（knowledge_chunk）← 检索的最小单位，前端一般不直接接触
```

## 数据结构

### `KnowledgeBaseVO`

```ts
interface KnowledgeBaseVO {
  id: string;
  name: string;                 // 1~80 字符
  description: string | null;
  categoryId: string | null;    // 所属分类，null 表示未分类
  status: 'EMPTY' | 'BUILDING' | 'READY' | 'FAILED';
  docCount: number;             // 文档数
  chunkCount: number;           // 切片数
  chunkSize: number;            // 切片长度参数（字符）
  chunkOverlap: number;         // 切片重叠参数
  createTime: string;
}
```

**关于 `status`**：

| 值 | 含义 |
|---|---|
| `EMPTY` | 还没有任何文档 |
| `BUILDING` | 正在建立索引（有文档在解析/向量化） |
| `READY` | 可正常检索 |
| `FAILED` | 建索引失败，看 `errorMsg` |

**`chunkSize` / `chunkOverlap` 为什么要返回给前端**：它们决定了切片粒度。改了这两个参数**必须重建整个知识库**（存量切片是用旧参数切的）。前端在「设置」界面展示这两个值，并在用户修改时提示「保存后需要重建索引」。

### `KnowledgeCategoryVO`

```ts
interface KnowledgeCategoryVO {
  id: string;
  name: string;
  parentId: string;        // '0' 表示根分类
  level: number;           // 从 1 开始，最深 3 层
  sortOrder: number;
  children?: KnowledgeCategoryVO[];  // 树形接口里才有
}
```

### `KnowledgeDocumentVO`

```ts
interface KnowledgeDocumentVO {
  id: string;
  kbId: string;
  name: string;              // 展示用名称
  fileType: 'PDF' | 'DOCX' | 'TXT' | 'MD' | 'HTML' | 'URL';
  fileSize: number | null;   // 字节
  sourceUrl: string | null;  // fileType=URL 时
  charCount: number | null;
  chunkCount: number;
  status: 'PENDING' | 'PARSING' | 'PARSED' | 'INDEXING'
        | 'INDEXED' | 'FAILED' | 'DELETING';
  errorMsg: string | null;
  createTime: string;
}
```

---

## 一、知识库

### 创建知识库

```
POST /api/kb/create
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 1~80 字符。同一用户下可重名 |
| `description` | string | 否 | 最长 512 字符 |
| `categoryId` | string | 否 | 放到哪个分类下 |

响应：`data` 是 `KnowledgeBaseVO`（`status` 为 `EMPTY`，`chunkCount` 为 0）

**创建是轻量操作**——不会立刻建立向量集合，等第一个文档上传时才建。

**⚠️ 知识库的「模型」是创建时确定的**：新建的知识库用当前默认的向量模型（bge-small-zh，512 维）。**换模型需要重建整个知识库**，不是改个配置就行的（因为维度可能变，且两个模型的向量空间不通用）。

---

### 知识库列表

```
GET /api/kb/list?current=1&pageSize=20&categoryId=xxx&keyword=xxx
```

| 参数 | 说明 |
|---|---|
| `categoryId` | 可选，只看某个分类下的 |
| `keyword` | 可选，按名称模糊搜索 |

响应：分页结构，`records` 是 `KnowledgeBaseVO` 数组

---

### 知识库详情

```
GET /api/kb/{id}
```

响应：`data` 是 `KnowledgeBaseVO`

---

### 修改知识库

```
POST /api/kb/update
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | |
| `name` | string | 否 | 传了才改 |
| `description` | string | 否 | |
| `categoryId` | string | 否 | |
| `chunkSize` | number | 否 | 100~1000 |
| `chunkOverlap` | number | 否 | 0~300，必须小于 `chunkSize` |

响应：`data` 是更新后的 `KnowledgeBaseVO`

**⚠️ 改了 `chunkSize` 或 `chunkOverlap` 之后必须重建索引**，否则新参数只对新上传的文档生效，库里会出现「两种切片粒度混在一起」的状态。响应里会有个标记提示需要重建，前端要引导用户去点重建。

---

### 删除知识库

```
POST /api/kb/delete
```

| 字段 | 类型 | 必填 |
|---|---|---|
| `id` | string | 是 |

响应：`data` 是 `true`

**这是重操作**：会删掉该库下所有文档、切片、以及整个向量集合。**不可恢复。** 前端务必二次确认，并提示「该知识库下的 N 篇文档将一并删除」。

---

### 重建索引

```
POST /api/kb/{id}/rebuild?full=false
```

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `full` | boolean | `false` | `false` 增量重建（只处理变过的文档）；`true` 全量重建 |

响应：`data` 是 `true`（**异步任务，接口立刻返回**）

**⚠️ 这是异步操作。** 返回成功只表示「任务已提交」。要轮询状态接口：

```
GET /api/kb/{id}/status
```

响应：

```ts
interface KbBuildStatusVO {
  status: 'EMPTY' | 'BUILDING' | 'READY' | 'FAILED';
  total: number;            // 需要处理的文档数
  processed: number;        // 已处理
  failedCount: number;
  currentDocName: string | null;  // 正在处理哪个文档
  errorMsg: string | null;
}
```

**前端做法**：提交重建后，每 2 秒轮询一次，直到 `status` 变成 `READY` 或 `FAILED`。展示进度条 `processed / total`。

**什么时候需要重建**：

| 场景 | 用全量还是增量 |
|---|---|
| 改了 `chunkSize` / `chunkOverlap` | **全量** |
| 换了向量模型 | **全量** |
| 有几个文档处理失败了、想重试 | 增量 |
| 向量库数据看起来不对（对账） | 全量 |
| 日常维护（没什么特别原因） | 增量（快得多） |

---

### 检索测试

```
POST /api/kb/{id}/search
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `query` | string | 是 | 查询语句 |
| `topK` | number | 否 | 默认 5，最大 20 |

响应：

```json
{
  "code": 0,
  "data": {
    "query": "年假怎么算",
    "elapsedMs": 186,
    "hits": [
      {
        "chunkId": "1024",
        "docId": "12",
        "docName": "员工手册.pdf",
        "chunkIndex": 7,
        "score": 0.83,
        "content": "员工入职满一年后可享受年假……"
      }
    ]
  }
}
```

**这是给用户「验证知识库有没有用」的入口**，很有价值——用户上传完文档后第一件事就是想试试能不能查到。

**`score` 是重排后的相关性得分**（0~1 之间，越大越相关）。**但不要直接展示成百分比**——这个分数的绝对值没有直觉意义，它只适合用来做相对比较。展示成「相关度较高 / 中等 / 较低」这类定性描述更合适。

**没查到结果时**：`hits` 是空数组，这是**正常情况**（不是错误）。前端要提示「没有找到相关内容」，并给出可能的原因（文档还没索引完 / 换种问法）。

---

## 二、分类

### 创建分类

```
POST /api/kb/category/create
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 1~80 字符。**同一父级下不能重名** |
| `parentId` | string | 否 | 不传或传 `"0"` 表示根分类 |

响应：`data` 是 `KnowledgeCategoryVO`

**⚠️ 层级限制 3 层。** 超过会返回 `40000`，`message` 为「分类层级不能超过 3 层」。

**⚠️ 同名校验是后端在应用层做的**（不是数据库唯一索引，原因见 [knowledge/10-数据模型总览](../knowledge/10-数据模型总览.md)）。所以重名时返回的是 `40000` + `message: "同级下已存在同名分类"`。

---

### 分类树

```
GET /api/kb/category/tree
```

响应：`data` 是一个**树形数组**（根节点数组，每个节点的 `children` 里是子节点）。按 `sortOrder` 升序，`sortOrder` 相同时按 `id` 升序（保证顺序稳定）。

```json
{
  "code": 0,
  "data": [
    {
      "id": "1", "name": "公司制度", "parentId": "0", "level": 1, "sortOrder": 0,
      "children": [
        { "id": "3", "name": "人事", "parentId": "1", "level": 2, "sortOrder": 0, "children": [] }
      ]
    },
    { "id": "2", "name": "技术文档", "parentId": "0", "level": 1, "sortOrder": 1, "children": [] }
  ]
}
```

**一次返回完整树**，不分页。分类数量通常很少（几十个），不需要分页。

---

### 修改 / 删除分类

```
POST /api/kb/category/update      { id, name?, parentId?, sortOrder? }
POST /api/kb/category/delete      { id }
```

**删除分类时的行为**（**待确认**，实现时定稿）：

- 该分类下的**知识库**：变成「未分类」（`categoryId = null`），**不会被删除**
- 该分类的**子分类**：一起删除（级联）

前端在删除前应该提示「该分类下有 N 个知识库，删除后它们会变成未分类」。

---

## 三、文档

### 上传文件

```
POST /api/kb/{kbId}/doc/upload
Content-Type: multipart/form-data
```

| 表单字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | File | 是 | 单个文件 |
| `categoryId` | string | 否 | 文档级分类（可选，一般不用） |

**限制**：

| 项 | 值 |
|---|---|
| 单文件大小 | **最大 10MB**（后端 `spring.servlet.multipart.max-file-size: 10MB`） |
| 支持格式 | PDF、DOCX、TXT、MD（**HTML 走「添加网页」接口**） |
| 一次一个文件 | 多文件请循环调用 |

**⚠️ 超过 10MB 时**，Spring 会在进入 Controller 之前就拒绝请求。这个异常（`MaxUploadSizeExceededException`）会被后端的全局异常处理器捕获，返回：

```json
{ "code": 50000, "data": null, "message": "系统错误" }
```

**HTTP 状态码仍是 200**（因为后端统一这样处理），但 `message` 是没营养的「系统错误」，用户看不懂发生了什么。

**所以前端必须在上传前做一次本地大小校验**，直接提示「文件超过 10MB 限制」，不要把这个请求发出去。**不要依赖后端的错误提示。**

（*待实测确认*：如果你实测发现响应形式和上面写的不一样——比如返回的是 HTTP 413 或非 JSON 内容——请反馈修正本节。这个行为取决于异常在 Spring 内部的抛出时机，不能只靠推断。）

**⚠️ 这是同步接口，但耗时可能较长**：上传后要解析、切片、向量化。**建议后端设计成「上传立刻返回 + 后台异步处理」**（返回 `status: PENDING`，前端轮询文档状态）。如果您希望上传接口直接等到索引完成再返回，请提出——这会显著影响前端交互设计。

响应：`data` 是 `KnowledgeDocumentVO`

---

### 添加网页

```
POST /api/kb/{kbId}/doc/url
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `url` | string | 是 | 网页地址，http/https |

响应：`data` 是 `KnowledgeDocumentVO`

后端会抓取网页正文（去掉导航、页脚等噪声）后入库。抓取失败会返回 `data.status = FAILED` 并在 `errorMsg` 里说明。

---

### 文档列表

```
GET /api/kb/{kbId}/doc/list?current=1&pageSize=20&status=INDEXED&keyword=xxx
```

| 参数 | 说明 |
|---|---|
| `status` | 可选，按状态筛选 |
| `keyword` | 可选，按名称搜索 |

响应：分页结构，`records` 是 `KnowledgeDocumentVO` 数组

**前端要展示 `status`**，因为文档处理是异步的，用户需要看到「这篇还在处理」「这篇失败了」。

---

### 文档重命名 / 删除

```
POST /api/kb/doc/update     { id, name }
POST /api/kb/doc/delete     { id }
```

响应：`data` 是 `true`

**删除文档**会删掉它的所有切片和向量。这是**不可恢复**的操作。

---

### 重新索引单个文档

```
POST /api/kb/doc/{id}/reindex
```

响应：`data` 是 `true`（异步）

**用途**：某个文档处理失败了，重试一下（不用重建整个库）。

---

## 四、前端要注意的几个点

### 1. 上传/重建都是异步的，界面要有进度

文档上传后 `status` 会经历 `PENDING → PARSING → PARSED → INDEXING → INDEXED`。前端**不要在上传成功后就把文档显示成「已完成」**，要按 `status` 展示真实进度。

建议的展示：

| `status` | 展示 |
|---|---|
| `PENDING` / `PARSING` / `PARSED` / `INDEXING` | 「处理中…」+ 转圈，不可用于检索 |
| `INDEXED` | 正常显示，可检索 |
| `FAILED` | 红色标记 + `errorMsg` + 「重试」按钮 |
| `DELETING` | 「删除中…」，不可操作 |

### 2. 知识库状态和文档状态是两回事

- `knowledge_base.status` 是**汇总状态**（只要有文档在处理，库就是 `BUILDING`）
- `knowledge_document.status` 是**单个文档的状态**

列表页展示库的汇总状态，详情页展示每个文档的状态。

### 3. 切片内容用户可以看

`chunkCount` 只是数字，但**用户往往想看「它到底把我的文档切成了什么」**——这能建立信任，也能帮助用户理解为什么某些问题查不到。

目前**没有**「查看切片列表」的接口。如果需要（强烈建议要），应该加：

```
GET /api/kb/doc/{docId}/chunks?current=1&pageSize=20
→ { chunkIndex, content, tokenCount }
```

这条**标记为待定**，请确认是否需要。

## 待定项

| 待定 | 说明 |
|---|---|
| ~~PDF / DOCX 解析依赖~~ | ✅ **已定**：用官方 `langchain4j-document-parser-apache-pdfbox` / `-apache-poi`（`1.20.0-beta30`，传递引入 PDFBox 3.0.7）。**不需要自己引裸依赖** |
| 上传是否同步 | 建议异步（上传即返回 + 轮询状态） |
| 查看切片列表的接口 | 强烈建议加，见上文 |
| 文档预览 | 是否能预览原始文件？需要文件服务。目前没有 |
| 单次上传多个文件 | 目前设计成一次一个 |
| 知识库容量上限（文档数 / 切片数） | 尚未限制 |
| 知识库共享 / 协作 | 目前是每个用户自己的知识库，不共享 |
