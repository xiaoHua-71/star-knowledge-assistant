# 本地 Embedding 与 Rerank

> 本篇记录 M0（依赖引入与本地模型验证）的实测结果。**已实测的部分标注了证据，未验证的部分标了「待验证」**——别把两者混着读。

## ① 这个模块治的是哪类病（通用问题定义）

检索链路里有**两个把文本变成「可比较的数值」的环节**，本篇管这两个：

| 环节 | 什么时候用 | 输入 → 输出 |
|---|---|---|
| **Embedding（向量化）** | 入库时（文档侧）、检索时（query 侧） | 一段文本 → 一个定长浮点向量 |
| **Rerank（重排）** | 检索后，对召回的候选重新排序 | (query, 候选文本) 对 → 一个相关性分数 |

它们在链路里的位置：

```
文档 → 切片 → 【embedding】→ 向量库
query → 【embedding】→ 召回 N 条 → 【rerank】→ 取 Top-K → 拼进 prompt
```

### 边界

| 归它管 | 不归它管 |
|---|---|
| 文本 ↔ 向量的转换、相似度计算 | 文本怎么切（那是切片策略） |
| 候选的重排打分 | 召回多少条、怎么融合多路召回（那是混合检索） |
| 模型的加载、维度声明 | 向量存哪、怎么删（那是向量库） |

### 两类模型的关键区别（决定了它们能不能本地化）

| | embedding | rerank |
|---|---|---|
| 计算次数 | 入库时一篇文档几十次；检索时**每次 1 次** | 每次检索 1 次，但要算 N 个候选（N 通常 20~50） |
| 单次计算量 | 一次前向 | N 次前向（每个候选一次） |
| 能否独立于 LLM | **能**，输出是向量不是文本 | **能**，输出是分数 |
| 调用成本 | 按量计费的话，重复入库重复花钱 | 同上 |

**这就是为什么 embedding 和 rerank 适合本地化、而对话模型不适合**：前两者输入输出都是数值，是纯粹的「数学函数」，小模型够用且能离线跑；后者要做指令遵循和工具调用，小模型差距明显。这个取舍见 [01-总体架构与路线图](01-总体架构与路线图.md)。

### 四类典型失败模式

1. **模型/维度错配。** 换了 embedding 模型但旧向量还在库里。两个模型的向量空间**不通用**——如果维度恰好相同，检索会**不报错地返回完全无关的结果**。这是最危险的一类，因为它静默。
2. **query 前缀漏加。** bge 系列模型要求 query 侧加一段特定前缀（见坑 3）。漏加的后果是召回率下降，而所有测试仍然通过。
3. **原生库加载失败。** ONNX 运行时是带着平台相关的原生动态库的，它可能在**第一次真正推理时**才失败，而不是启动时。**M0 就踩到了这个（坑 1）。**
4. **切片长度超出模型上限。** bge-small-zh 建议单段不超过 512 token。超长**不会报错**，只是向量质量下降。

---

## ② LangChain4j 官方怎么做

**Embedding 侧的类**（`dev.langchain4j:langchain4j-embeddings` 及其模型子包）：

| 类 | 作用 |
|---|---|
| `dev.langchain4j.model.embedding.EmbeddingModel` | 接口（在 `langchain4j-core`） |
| `dev.langchain4j.model.embedding.DimensionAwareEmbeddingModel` | 带维度声明的接口 |
| `dev.langchain4j.model.embedding.onnx.AbstractInProcessEmbeddingModel` | 进程内模型的基类 |
| `dev.langchain4j.model.embedding.onnx.OnnxBertBiEncoder` | ONNX + BERT 双编码器的实际执行者 |
| `dev.langchain4j.model.embedding.onnx.PoolingMode` | 池化方式（`CLS` / `MEAN`）——必须和模型的训练方式一致 |

官方提供两种用法：

1. **直接用打包好的模型。** 有一批 `langchain4j-embeddings-<模型名>` artifact，**把 onnx 文件和 tokenizer.json 直接打在 jar 里**，零下载零配置。命名规律是模型名 + 是否量化（`-q` 后缀）。
2. **加载自定义模型。** `OnnxEmbeddingModel(modelPath, tokenizerPath, poolingMode)`，模型文件放磁盘上自己管。

**Rerank 侧：**

| 类 | 作用 |
|---|---|
| `dev.langchain4j.model.scoring.ScoringModel` | 接口（在 `langchain4j-core`）。核心方法 `Response<List<Double>> scoreAll(List<TextSegment> segments, String query)` |
| `dev.langchain4j.model.scoring.onnx.OnnxScoringModel` | ONNX 实现（在 `langchain4j-onnx-scoring`） |

**⚠️ 关键区别：rerank 这边官方不打包模型文件。** `OnnxScoringModel` 的构造函数收的是**文件路径**，你得自己准备一个 ONNX 格式的 cross-encoder 模型 + tokenizer.json。这是 embedding 和 rerank 在工程上的最大差别，也是本项目唯一还没落地的部分（见第五节）。

**接口签名已实测未变**：`ScoringModel.scoreAll(List<TextSegment>, String)` 在 `1.20.0` 里和 salary-leap 用的 `1.4.0` **完全一致**（1.20.0 新增了 `scoreAsync` / `ScoringRequest`，但老方法没动）。所以从 salary-leap 搬 `QwenScoringModel` 的移植成本很低。

---

## ③ 本项目怎么落地

### Embedding：用官方打包的 bge-small-zh 量化模型

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-embeddings-bge-small-zh-v15-q</artifactId>
    <version>1.20.0-beta30</version>
</dependency>
```

类名：`dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel`，**无参构造即可用**。

实测确认（读 1.20.0 的 sources jar）：

| 项 | 值 | 来源 |
|---|---|---|
| 维度 | **512** | 类 Javadoc 明确写着 `Embedding dimensions: 512`；`knownDimension()` 返回 512 |
| 池化 | `PoolingMode.CLS` | 源码里 `loadFromJar(..., PoolingMode.CLS)` |
| 模型文件 | `bge-small-zh-v1.5-q.onnx`（24 MB）**打进 jar** | jar 内文件列表 |
| tokenizer | `bge-small-zh-v1.5-q-tokenizer.json`（439 KB）**打进 jar** | 同上 |
| 建议单段长度 | ≤ 512 token | 类 Javadoc |
| **query 前缀** | `为这个句子生成表示以用于检索相关文章：` | **类 Javadoc 自己写的**，见坑 3 |

**选它的理由**：中文场景、512 维（存储和计算都省）、量化版体积小、**模型打进 jar 所以部署时零额外下载**。

### Rerank：`OnnxScoringModel` + 自备模型文件 ｜ ⬜ 未落地

计划用 `langchain4j-onnx-scoring` 加一个 bge-reranker 模型文件。**模型文件来源还没定**（见第五节），所以这一块**是当前唯一没跑通的环节**。兜底方案是保留 salary-leap 的 `QwenScoringModel`（远端 DashScope rerank），用配置开关切换——因为 `KnowledgeService` 注入的是 `ScoringModel` 接口，换实现不用改调用方。

### 对话模型保持远端

不在本篇范围内，见 [01-总体架构与路线图](01-总体架构与路线图.md)。已确认配置前缀没变（仍是 `langchain4j.community.dashscope.chat-model`），salary-leap 的 yml 可以照抄。

---

## ④ 数据结构与流程

### 维度变更的影响面（512 必须贯穿全链路）

改 embedding 模型 = 改维度 = **旧向量全部作废**。所以要把「维度」这个概念从配置一路贯通到校验。受影响的点：

| 位置 | 要做什么 |
|---|---|
| `application.yml` 的 `milvus.dimension` | `1536` → **`512`**（现在是占位值，必须改） |
| `knowledge_base.dimension` | 建库时记下当时的维度（见 [10-数据模型总览](10-数据模型总览.md)） |
| `knowledge_base.embeddingModel` | 记下模型标识，检索前校验「query 用的模型 == 入库用的模型」 |
| Milvus 集合 | 维度是集合的 schema 属性，**改维度必须新建集合**，不能在原集合上改 |
| 切片长度 | 400 字符的默认值要按 512 token 上限核对（见坑 5） |

### query 侧加前缀、文档侧不加

```
入库路径：切片正文 ──────────────────────────► embed(原文)
检索路径：query ──► 拼前缀 ──► embed(前缀+query)
```

**这个不对称是刻意的**，而且必须只在**一处**实现（计划是 `EmbeddingEngine` 的 query 方法里），否则很容易变成「某些调用路径加了、某些没加」。

---

## ⑤ 边界、已知限制、坑

### 坑 1（M0 实测踩到）：onnxruntime 1.22.0 的原生库在本机加载失败

**这是 M0 唯一的真失败，也是最值得记的一条。**

**现象**：第一次运行 `LocalEmbeddingModelTest` 直接失败，报

```
java.lang.UnsatisfiedLinkError: C:\Users\qq\AppData\Local\Temp\onnxruntime-java<随机串>\onnxruntime.dll:
  动态链接库(DLL)初始化例程失败。
    at ai.onnxruntime.OnnxRuntime.load(OnnxRuntime.java:387)
    at ai.onnxruntime.OrtEnvironment.<clinit>(OrtEnvironment.java:34)
    at dev.langchain4j.model.embedding.onnx.OnnxBertBiEncoder.<init>(OnnxBertBiEncoder.java:47)
```

**注意这个报错的位置**：它发生在**类加载阶段**（`<clinit>`），也就是说**只要碰到 embedding 模型就炸**，而且错误信息里完全不提「依赖版本」四个字。如果没写这个测试，它会等到第一次真正检索时才暴露。

**排查过程（已排除的项，记下来免得下次重复排查）**：

| 怀疑 | 排除依据 |
|---|---|
| jar 没下全 | `onnxruntime-1.22.0.jar` = 139 MB，`win-x64/onnxruntime.dll` 12.4 MB，条目数 134，结构完整 |
| 缺 VC++ 运行库 | `msvcp140.dll` / `vcruntime140.dll` / `vcruntime140_1.dll` 都在，版本 **14.42.34438**（很新的 VS2022 redist） |
| CPU 不支持指令集 | CPU 是 **AMD Ryzen 7 4800U**（Zen 2），支持 AVX2 |
| 架构不匹配 | x64 机器 + x64 JDK |

**真正的根因**：`langchain4j-onnx-scoring` 声明依赖 `onnxruntime:1.22.0`（深度 1），而 `langchain4j-embeddings` 声明 `1.20.0`（深度 2）。Maven「取近者」选了 1.22.0——**而 1.22.0 的原生库在这台机器上起不来**。

**修法**：在 `dependencyManagement` 里显式把 onnxruntime 钉到 **1.20.0**（正是 `langchain4j-embeddings` 自己声明的版本）。

**修完实测通过**：

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0   -- BUILD SUCCESS
```

**这条坑为什么要写进文档**：

1. 报错信息里**没有**「版本」线索，也没有「换版本试试」的暗示。不知道的人会去查 VC++、查 CPU、查杀毒软件，全是弯路。
2. 它是**环境相关**的——换一台机器可能 1.22.0 就能用。所以 pom 里的注释写了「若哪天换机器后 1.22.0 可用，可以去掉这段让它自然解析」。
3. 本地仓库里现在躺着 **1.20.0（93 MB）+ 1.22.0（139 MB）两份**共 226 MB。确认用不上 1.22.0 之后可以删掉后者省空间。

### 坑 2：两个 LC4j 模块声明的 onnxruntime 版本不一致

这是坑 1 的成因，单独列出来因为它**不是一次性的**——升级 LC4j 时还会再遇到。

```
langchain4j-embeddings-*        → onnxruntime 1.20.0   （深度 2）
langchain4j-onnx-scoring        → onnxruntime 1.22.0   （深度 1）← Maven 选这个
```

**两个模块各声明各的版本，没有 BOM 统一管理。** 所以：

- 升级 LC4j 后要**重新检查 `mvn dependency:list -DincludeArtifactIds=onnxruntime`**，确认解析到的版本
- 我们的 `dependencyManagement` 钉死版本，会**覆盖** LC4j 的声明。这是特性也是风险：将来 `onnx-scoring` 如果依赖 1.22+ 的新 API，钉 1.20.0 会编译/运行失败——那时要评估是升 onnxruntime 还是降 `onnx-scoring`

### 坑 3：query 前缀漏加（静默降召回）

bge-small-zh 的类 Javadoc 明确写着：

> It is recommended to add `"为这个句子生成表示以用于检索相关文章："` prefix to a query.

**query 侧加，文档侧不加。** 漏加的后果是召回率下降——**但所有测试都会通过**，因为没有断言在检查召回质量。只有 [60-离线评测](60-离线评测.md) 那套指标能发现。

已加一条单测守住它的反面：断言「模型不会自动加前缀」，防止将来官方改成自动加、我们代码里再加一次变成加两遍。

### 坑 4：模型不会自动加前缀（已加断言）

见 `LocalEmbeddingModelTest.模型不会自动添加检索前缀`：如果某天加了前缀和没加前缀的向量变得完全相同，说明官方改成自动加了——那时该改的是我们的 `EmbeddingEngine`，不是删断言。

### 坑 5：400 字符的切片默认值需要实测核对

`bge-small-zh` 建议单段 ≤ 512 token。当前 `knowledge_base.chunkSize` 默认给的是 400 **字符**。

**中文里「字符数」和「token 数」不是一回事**：一个汉字通常算 1~2 个 token（取决于 tokenizer 的合并规则），400 个汉字可能远超 512 token。

**这条待验证**：方法是把 `knowledge_chunk.tokenCount` 用**真实 tokenizer 实测**填上，统计分布，确认没有大量切片超 512。在拿到数据之前，`chunkSize = 400` 只是一个**猜测值**，别当成结论。

### 没做的

| 没做 | 影响 | 备注 |
|---|---|---|
| **rerank 模型文件** | rerank 这一环还没跑通 | 当前最大缺口，见下 |
| 模型预热 | 首次调用要加载模型（实测约 5 秒级），第一个用户会等 | 可以在 `ApplicationReadyEvent` 里 embed 一句预热 |
| 多模型热切换 | 一个进程只能用一套模型 | 换模型要重启 + 重建索引 |
| GPU 加速 | ONNX 走 CPU | 当前数据量下 CPU 够用；笔记见下 |
| 模型文件的版本管理 | 打包进 jar（embedding），无法热替换 | 换取的是部署零下载 |

### 待验证（下一件事）

**rerank 的模型文件从哪来。** 这是唯一还没落地的环节，需要在 M2 之前解决：

| 候选模型 | 说明 |
|---|---|
| `Xenova/ms-marco-MiniLM-L-6-v2` | 小、英文，**适合先把链路试通**，中文效果差 |
| `BAAI/bge-reranker-base` | 中文可用，体积适中 |
| `BAAI/bge-reranker-v2-m3` | 多语言效果最好，但 fp32 约 2.2 GB，要找量化版 |

**下载通道**（国内网络，按优先级）：ModelScope（魔搭，最稳）→ `hf-mirror.com`（`HF_ENDPOINT=https://hf-mirror.com`）→ 官站最后。

**必须做的两件事**：
1. 模型文件放 `models/` 并加进 `.gitignore`（体积大，不进版本库）
2. **启动时校验文件存在，缺失就快速失败**——别拖到第一次 rerank 才报错（这正是坑 1 的教训）
