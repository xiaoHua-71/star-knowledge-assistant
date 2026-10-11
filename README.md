# 智能星知识助手

基于 Spring Boot 3 + LangChain4j 的智能知识助手，目标是跑通 **Agent 的整条流程**：

- **Agent + 工具调用** —— 模型自己在 ReAct 循环里决定调哪个工具、调几次、什么时候停
- **多轮问答 + 会话记忆** —— 同一会话能追问、能引用上文
- **RAG 检索封装成工具** —— 检索作为 Agent 的一个工具，由模型决定要不要查知识库
- **流式输出 + 演示前端** —— SSE 打字式返回，工具调用过程可视

> 这是独立项目，和 `salary-leap` 不是一套代码。用户模块（注册 / 登录 / 用户信息 / 修改密码）从
> salary-leap 复制而来，但去掉了那边的游戏字段；基础设施（Milvus）直接复用宿主机上现成的那套。

## 当前进度

**Phase 1（骨架 + 用户模块）已完成。Phase 2 方案已定**，并按「文档带动实现」的方式写成了两份文档：

| 目录 | 内容 | 读者 |
|---|---|---|
| [`knowledge/`](knowledge/00-索引与阅读顺序.md) | 每个模块的流程与设计说明（12 张表 DDL、中断恢复状态机、决策记录） | 后端 / 自己回顾 |
| [`frontend/`](frontend/00-索引与阅读顺序.md) | 前后端接口契约。`01-通用约定` + `10-user-用户模块` 已完整可用 | 前端 |

入口是 [`knowledge/00-索引与阅读顺序.md`](knowledge/00-索引与阅读顺序.md)。
**改代码前先读对应文档；设计变更时先改文档，再把「原设计为什么不成立」记进那篇的「边界与坑」。**

**M0（依赖引入 + 本地模型验证）已完成**：本地 embedding（bge-small-zh，512 维）实测跑通。
**rerank 还没落地**（官方不打包 rerank 模型，模型文件来源待定）。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.5.5 / Java 21 |
| 持久化 | MySQL 8 + MyBatis-Plus 3.5.13 |
| 缓存 & 会话 | Redis（验证码 + Spring Session 登录态） |
| 接口文档 | knife4j 4.4.0（基于 springdoc 2.8.17） |
| 向量库 | Milvus 2.5.27（本地 `docker-compose.yml` 起，见仓库根目录） |
| 前端 | 待定（候选：Vue 3 + Vite + Element Plus） |
| AI 框架 | LangChain4j `1.20.0`（集成模块 `1.20.0-beta30`） |
| 对话模型 | DeepSeek-V4.1-Flash（**远端**，DeepSeek 官方直连，OpenAI 兼容，模型 ID `deepseek-flash`） |
| 向量 / 重排模型 | **本地 ONNX**：bge-small-zh-v15（512 维）/ bge-reranker |

**⚠️ `onnxruntime` 被显式钉在 `1.20.0`。** LC4j 的 `embeddings` 与 `onnx-scoring` 声明的版本不一致，
Maven 会解析到 `1.22.0`，而 **1.22.0 的原生库在本机加载失败**（`UnsatisfiedLinkError: DLL 初始化例程失败`）。
原因与完整排查过程见 [`knowledge/21-本地Embedding与Rerank.md`](knowledge/21-本地Embedding与Rerank.md)。

## 启动前置

1. **MySQL**：执行 `sql/create_table.sql` 建库建表
2. **Redis**：`localhost:6379`
3. **邮件**：改 `application.yml` 里的 `spring.mail.username` / `password`（换成自己的 SMTP 授权码）。
   这段**不能删** —— 缺了它 `JavaMailSender` bean 不会创建，应用启动直接失败。
   没配 SMTP 时发验证码不会崩，只是降级成打日志（验证码仍写进 Redis）。
4. **Milvus**（Phase 2 才用得上）：本项目自带 `docker-compose.yml`（etcd + minio + milvus），
   在项目根目录 `docker compose up -d`，数据落在 `./volumes/`（已 gitignore）。
   容器名和网络名都带 `star-` 前缀、不会覆盖别的一套；但**端口都是 19530，两套 Milvus 不能同时起**。

## 运行

```bash
# 本机默认 JDK 11 编译不过，要指定 21
JAVA_HOME=/e/Java/jdk-21.0.5 mvn spring-boot:run
```

- 服务端口：`8102`（salary-leap 占 8101）
- context-path：`/api`
- 接口文档：http://localhost:8102/api/doc.html

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/user/register` | 注册（`registerType` 选 `password` 或 `email`） |
| POST | `/api/user/sendCode` | 发送邮箱注册验证码 |
| POST | `/api/user/login` | 登录，登录态写进 Redis session |
| GET | `/api/user/current` | 获取当前登录用户 |
| POST | `/api/user/update` | 更新昵称 / 头像 |
| POST | `/api/user/updatePassword` | 修改密码（校验旧密码） |
| POST | `/api/user/logout` | 注销 |

## 测试

```bash
# 只跑不依赖外部环境的测试（推荐，本地平时跑这个）
JAVA_HOME=/e/Java/jdk-21.0.5 mvn test -Dtest=LocalEmbeddingModelTest -DfailIfNoSpecifiedTests=false

# 全量
JAVA_HOME=/e/Java/jdk-21.0.5 mvn test
```

- `MainApplicationTests` 是 `@SpringBootTest`，需要 MySQL / Redis / 邮件配置齐备才能过，本地平时不跑
- `LocalEmbeddingModelTest` **不启 Spring** —— 它验的恰恰是「本地 ONNX 模型能不能脱离框架跑起来」
  （原生库加载、512 维、query 前缀不会被自动添加）
