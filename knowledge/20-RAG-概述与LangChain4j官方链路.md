# RAG 概述与 LangChain4j 官方链路

> 本篇是 RAG 组的总纲：讲清楚 RAG 治什么病、官方提供了哪些零件、本项目用哪些、自研哪些。
> 具体实现分散在 [21 本地Embedding与Rerank](21-本地Embedding与Rerank.md)、
> [22 知识库与文档生命周期](22-知识库与文档生命周期.md)、[23 混合检索与融合](23-混合检索与融合.md)、
> [24 增量索引](24-增量索引.md)。**先读这篇建立地图，再进各分篇。**

## ① 这个模块治的是哪类病（通用问题定义）

### 问题定义

大模型有一个绕不开的性质：**它的全部知识在训练时就固定了**。由此产生三类症状：

| 症状 | 表现 |
|---|---|
| **知识截止** | 训练数据之后的、或训练时没有的事件，它一概不知道 |
| **不懂私有数据** | 你公司内部的制度、文档、代码，它没见过，问它只能编 |
| **幻觉** | 它对不知道的事**不会说「不知道」**，而是编一个看起来很可信的答案 |

这三类的共同点是：**模型缺的是「事实」，不是「能力」**。让它读一段正确事实，它就能正确作答。

于是 RAG（Retrieval-Augmented Generation，检索增强生成）的做法是：**回答之前，先从你自己的资料里检索出最相关的几段，作为「参考资料」一起塞进 prompt**。模型基于这些资料作答，而不是凭记忆。

```
用户问题 ──► 检索自己的资料 ──► 命中的几段文本 ──┐
                                                ├─► 拼进 prompt ──► 模型作答
                        （提问本身）─────────────┘
```

一句话：**RAG 不改变模型，只改变模型的「上下文」**。

### 它和另外两条路线的区别

| 方案 | 做法 | 适合 | 不适合 |
|---|---|---|---|
| **RAG** | 检索外部资料拼进 prompt | 知识频繁更新、有私有资料、要引用出处 | 需要新「能力」（推理/格式）而非新「事实」 |
| **微调（fine-tune）** | 用自有数据继续训练模型 | 固定风格/格式、专有任务 | 知识频繁变（训一次很贵，且改不了）；事实类记忆用微调性价比极低 |
| **长上下文** | 把整份资料全塞进 prompt | 资料少（放得下）、一次性问答 | 资料多（塞不下/成本爆炸）、每次都要定位（模型会「中间遗忘」） |

**这张表的用途**：当有人问「为什么不直接微调」「为什么不用长上下文」，答案就在这里——RAG 解决的是「事实的供给」，而事实是**一直在变的**，微调跟不上；长上下文则**放不下也定位不准**。

### 边界：RAG 管什么，不管什么

| 归 RAG 组管 | 不归 RAG 组管 |
|---|---|
| 从资料里**能不能找到**相关内容（召回） | 模型怎么组织语言作答（那是对话/Agent 层） |
| 找到的内容**排得准不准**（排序/重排） | 文本怎么进到系统（那是上传/解析的前置流程） |
| 检索结果怎么**拼进 prompt** | 用什么模型（那是模型层选型） |
| 资料**怎么被切成检索单元** | 模型微调（本项目不做） |

本项目把 RAG 拆成四篇，各自的分工：

| 篇 | 负责 |
|---|---|
| 21 | 文本 ↔ 向量、候选重排（**模型**层面） |
| 22 | 上传 → 解析 → 切片 → 入库（**数据流转**层面） |
| 23 | 多路召回如何合并、如何重排取前 K（**检索策略**层面） |
| 24 | 文档改了之后如何最小代价重建（**增量维护**层面） |

### 四类典型失败模式

RAG 的失败不是「报错」，而是「悄悄答错」。四类：

1. **召回侧失败**：真答案在库里，但没被检索出来。原因可能是切片把答案切断了、embedding 模型对中文不敏感、query 和文档用词差异大。**这类失败最隐蔽**——检索没报错，只是没召回。
2. **精度侧失败**：召回了相关内容，但也混进一堆不相关的，模型被带偏。靠 rerank + 多样性来压。
3. **模型无视检索结果**：检索结果明明在 prompt 里，模型仍按自己的记忆答。靠 prompt 约束（「只依据资料作答」）+ 引用出处来压。
4. **静默的模型/维度错配**：换了 embedding 模型但旧向量还在。两个模型的向量空间不通用；维度恰好相同时**不报错，只返回完全无关的结果**（见 [21](21-本地Embedding与Rerank.md) 失败模式 1）。这是最危险的一类，因为它连「答非所问」都很像「答错了」。

> **注意第 1、2 类都要求「能度量好坏」**——没有评测集，你根本不知道改动是变好还是变差。
> 这就是为什么路线图里 [60 离线评测](60-离线评测.md) 要早做（见 [01](01-总体架构与路线图.md) 第六节）。

---

## ② LangChain4j 官方怎么做

官方把 RAG 分成**两条链路**，并提供了每条链路的组件。文档：
<https://docs.langchain4j.dev/tutorials/rag>（本项目用 **1.20.0 / 1.20.0-beta30** 线）。

### 入库链路（Ingestion）

```
文档 → DocumentLoader → DocumentParser → DocumentSplitter → EmbeddingModel → EmbeddingStore
```

| 阶段 | 官方类 | 说明 |
|---|---|---|
| 加载 | `DocumentLoader`（`FileSystemDocumentLoader` / `ClassPathDocumentLoader` / `UrlDocumentLoader`） | 把来源变成字节流 |
| 解析 | `DocumentParser`（`ApachePdfBoxDocumentParser` / `ApachePoiDocumentParser`） | 字节流 → 纯文本 `Document` |
| 切片 | `DocumentSplitter`（`DocumentSplitters.recursive` 及 `DocumentBy{Paragraph,Line,Sentence,Word,Character,Regex}Splitter`） | `Document` → `List<TextSegment>` |
| 向量化 | `EmbeddingModel`（`OnnxEmbeddingModel` / 各厂商实现） | 文本 → `Embedding` |
| 存储 | `EmbeddingStore`（`MilvusEmbeddingStore` / `InMemoryEmbeddingStore`） | 向量 + 文本 + metadata 落库 |
| 编排 | `EmbeddingStoreIngestor` | 把上面几步串成一个 `ingest(document)` 调用 |

### 检索链路（Retrieval）

```
query → [QueryTransformer] → EmbeddingModel → EmbeddingStore.search → [ScoringModel 重排] → [ContentInjector] → prompt
```

| 阶段 | 官方类 | 说明 |
|---|---|---|
| 查询改写 | `QueryTransformer`（`CompressingQueryTransformer` / `ExpandingQueryTransformer`） | 把口语化问题改成更适合检索的查询 |
| 召回 | `ContentRetriever`（`EmbeddingStoreContentRetriever`） | 一次向量召回；也叫「内容检索器」 |
| 重排 | `ScoringModel` / `ReRankingContentAggregator` | 对候选按相关性重新排序、取前 K |
| 编排 | `RetrievalAugmentor`（`DefaultRetrievalAugmentor`） | 把改写、召回、重排、注入串起来 |
| 注入 | `ContentInjector`（`DefaultContentInjector`） | 把检索片段拼进发给模型的 prompt |

### 官方明确不做的

- **不管生命周期**：`EmbeddingStoreIngestor.ingest()` 是一次性的「给我个 Document，我入库」。它不维护「这篇文档解析到哪一步了」「失败了怎么重试」「删文档时向量怎么清」。
- **不管双写一致性**：它只有一个库（EmbeddingStore），没有「MySQL 权威源 + 向量库派生索引」的概念。
- **不管增量**：没有「文档改了只重建这一篇」的能力，重新 ingest 就是全量再来。
- **`DocumentSplitters.recursive` 的默认链对中文有两处不适配**（末级 word 拆分会抛异常、句子级用英文断句模型）——见 [22](22-知识库与文档生命周期.md) 的坑 1、坑 2。

> **官方文档还区分「Easy RAG」和「Modular RAG」**：前者几行代码就能跑（`AiServices` + 一个 `ContentRetriever` 自动装配），后者要自己拼上表的每个组件。本项目走 Modular 路线——因为要自研的部分（生命周期、双写、融合）全在「编排」层，用 Easy RAG 反而挡路。

---

## ③ 本项目为什么这么落地

**总立场**（与 [01](01-总体架构与路线图.md) 第三节一致）：把 LangChain4j 当**厂商适配层 + 数据模型**，不当**架构骨架**。

| 环节 | 用官方 | 自研 | 理由 |
|---|---|---|---|
| `Document` / `TextSegment` / `Metadata` / `Embedding` 数据模型 | ✅ | | 纯数据结构，无争议 |
| PDF / Office 解析 | ✅ | | `ApachePdfBoxDocumentParser` / `ApachePoiDocumentParser`，官方做得好 |
| TXT / MD / HTML 解析 | | ✅ | 官方没有对应 parser 模块；TXT/MD 直读，HTML 用 jsoup |
| 分层切片器 | ✅ | ⚠️ 改造 | 用官方的分层拆分器，但**末级换成字符级、句子级换成中文正则**（见 [22](22-知识库与文档生命周期.md) 坑 1、2） |
| Embedding / Rerank 模型 | ✅ | | `BgeSmallZhV15QuantizedEmbeddingModel` + `OnnxScoringModel`（见 [21](21-本地Embedding与Rerank.md)） |
| 向量存储 | ✅ | | `MilvusEmbeddingStore`（legacy，见 [01](01-总体架构与路线图.md) 的债） |
| **生命周期状态机** | | ✅ | 官方没有；状态要落自己的表、要能重试 |
| **双写与删除顺序** | | ✅ | 官方只有一个库，没有一致性编排 |
| **搜索结果的详情拼装** | | ✅ | 只用 Milvus 找 id + 分数，正文/文档名从 MySQL 取（权威源） |
| **混合检索 / 融合 / 多样性** | | ✅ | 见 [23](23-混合检索与融合.md) |
| **增量索引** | | ✅ | 见 [24](24-增量索引.md) |

**为什么不用 `EmbeddingStoreIngestor`？** 它把「切片 + 向量化 + 入库」包成一个调用，但我们要在这三步之间**落状态、写 MySQL 切片行、维护冗余计数**，而且入库失败要能停在中途重试。用它就得把状态逻辑塞进它的钩子里，反而更绕。所以只借它的**组件**（parser / splitter），不借它的**编排**。

**为什么搜索结果要从 MySQL 取详情？** Milvus 里其实也存了文本和 metadata，理论上能直接从命中里拿。但那样就出现两个真相源（Milvus 的 metadata 和 MySQL 的切片行），删文档时一旦双写漂移就会不一致。所以约定：**Milvus 只负责「按相似度找回 id 和分数」，其余字段一律回 MySQL 取**。遇到 Milvus 里有、MySQL 里没有的 id，记 warn 跳过（这是漂移信号，见 [24](24-增量索引.md) 的对账）。

---

## ④ 数据结构与流程时序

### 两条链路

```
【入库】 用户上传文件 ──► 落盘 ──► 建 knowledge_document(PENDING) ──► 异步：
             解析(PARSING) ──► 切片 ──► 写 knowledge_chunk ──► 写 Milvus ──► INDEXED
                                        （MySQL 先）          （向量后）

【检索】 query ──► 加前缀 ──► embed ──► Milvus.search(topN) ──► [rerank] ──► 回 MySQL 取详情 ──► hits
```

入库细节与状态流转见 [22](22-知识库与文档生命周期.md)；检索的召回合并与重排见 [23](23-混合检索与融合.md)。

### 两层存储（本项目的关键设计原则）

```
        写入                              读取
用户 ──► MySQL（权威源）                SQL：列表 / 状态 / 统计 / 详情
          │
          └──► Milvus（派生索引）  ──►   只做一件事：按相似度找回 vectorId + 分数
```

- 权威源是 **MySQL**：文字、元数据、状态、计数都在这里。向量库换了/集合重建了，业务数据都不丢。
- Milvus 是**派生**：可以从 MySQL 的切片重新算出来，因此可以放心删除、重建。
- 两边靠 **`knowledge_chunk.vectorId`** 对应（确定性 UUID，见 [24](24-增量索引.md)）。

代价是双写，所以定了一套**删除顺序**和**对账机制**——见 [22](22-知识库与文档生命周期.md) 与 [24](24-增量索引.md)。

### 代码落点

| 能力 | 类 |
|---|---|
| 模型装配 | `config/AiModelConfig`（embedding 常驻；rerank 走 `ai.rerank.enabled` 开关） |
| 向量化（含 query 前缀） | `ai/EmbeddingEngine` |
| 集合工厂（每库一集合） | `ai/MilvusStoreFactory` |
| 解析分发 | `ai/DocumentParserFactory` |
| 切片 | `ai/ChunkSplitterFactory` |
| 索引流水线 | `service/impl/KbIndexServiceImpl` |
| 检索 | `service/impl/KnowledgeSearchServiceImpl` |
| 接口 | `controller/KnowledgeController` |

---

## ⑤ 边界、已知限制、踩过的坑

### 没做的（截至 M1）

| 没做 | 影响 | 计划 |
|---|---|---|
| ~~**混合检索（BM25 + RRF）**~~ | ✅ 已实现（M3）：向量 + BM25 双路召回 → RRF 融合 → 来源多样性，见 [23](23-混合检索与融合.md) |
| **rerank 真实模型** | 默认关闭（`ai.rerank.enabled=false`），只按融合分/向量分排。检索链路的 rerank 这一环已留好（接口 + 降级），缺的只是模型文件 | M2，模型文件待定，见 [21](21-本地Embedding与Rerank.md) |
| **增量索引** | 重建就是全量重切重算 | M3，见 [24](24-增量索引.md) |
| **对账任务** | 双写漂移只能靠日志 warn 发现 | M11 |
| **查询改写（QueryTransformer）** | 口语化问题直接检索，召回可能受影响 | 后置 |
| **docCount/chunkCount 用了冗余计数** | 删除时同步维护，靠代码正确性；对账可修 | 见 [22](22-知识库与文档生命周期.md) |

### 已知限制

- **中文场景 `chunkOverlap` 实际不生效**（框架限制，重叠区用 OpenNLP 英文断句模型）。详见 [22](22-知识库与文档生命周期.md) 坑 2。
- **Milvus 用的是 legacy `langchain4j-milvus` 模块**，LangChain4j 2.0 会移除。见 [01](01-总体架构与路线图.md) 的技术债。
- **`rerank` 关闭时 `score` 是向量余弦相似度**，绝对值没有直觉意义，前端不要展示成百分比。

### 一个反直觉的点

**检索链路的核心指标不是「准确率」而是「召回率」**。因为召不回来的东西，后面的重排、prompt 都无法补救；而召回进来的一堆噪声，还能靠 rerank 压下去。所以 RAG 调优的顺序是：**先把召回做全（切片质量、embedding、多路召回），再谈排序精度**。

> 这也是本项目先做 22（生命周期/切片）再做 23（融合/重排）的顺序原因——切片切坏了，后面再精巧的排序都是在烂地基上盖楼。

### M1 实测：向量相似度分数的区分度很差（重要）

M1 端到端跑通后，用同一批文档实测了两条 query（bge-small-zh，COSINE 度量）：

| query | 与文档的相关性 | 向量分数 |
|---|---|---|
| 「年假有多少天」 | 相关（文档里有年假条款） | **0.783** |
| 「远程办公怎么申请」 | 相关（文档里有远程办公章节） | **0.857** |
| 「今天天气怎么样」 | **完全不相关** | **0.676** |

**结论：不相关的问题也能拿到 0.676，和相关的只差 0.1 左右。**

由此得到两条直接决定架构的推论：

1. **`minScore` 不能拍脑袋，必须按模型的分数分布标定。** 本地 bge 的分数区间很窄，0.4~0.75 完全过滤不掉东西；2026-10-27 用 30 条评估集标定后取 `0.8`（0 条命中丢失），0.85 起就开始误杀期望来源（见 [60 离线评测 §十一](60-离线评测.md)）。阈值是**模型相关**的，换模型后要重标，不能搬运。
2. **rerank 对本项目不是「锦上添花」，是刚需。** 向量召回只负责「把候选捞回来」（召回侧），「哪个才真的相关」必须交给 cross-encoder 重排模型（精度侧）——它把 query 和候选**一起**编码，区分度远高于各自独立编码后算余弦。这是 [23 混合检索与融合](23-混合检索与融合.md) 要用 RRF + rerank 的实证依据。

> **副作用**：阈值 0.8 生效后，非空知识库也可能对某些问题返回空列表；前端「没找到相关内容」的空状态不再只出现在「知识库为空」时。
