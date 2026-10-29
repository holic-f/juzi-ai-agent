# AGENTS.md — AI 协作须知

本文件面向所有在此仓库工作的 AI 代理（以及人类协作者）。**开工前先读完本文件**，它记录了命令、测试成本红线、成本纪律和必须保持的既有行为约定。

---

## 1. 项目地图

| 目录 | 内容 |
| --- | --- |
| `src/main/java/com/yupi/juziaiagent/` | Spring Boot 后端：`agent/`（Agent 引擎）、`controller/`、`app/`（LoveApp）、`rag/`、`tools/`、`chatmemory/`、`advisor/`、`config/`、`constant/`、`demo/` |
| `src/test/java/` | 后端测试（**注意成本分级，见第 3 节**） |
| `juzi-ai-agent-frontend/` | Vue 3 + TS + Vite 前端，SSE 解析在 `src/views/ChatView.vue`，测试在 `tests/*.test.mjs` |
| `juzi-image-search-mcp/` | 独立的图片搜索 MCP 服务，和主应用分开构建 |
| `docs/agent-performance.md` | Manus 性能观测、日志字段、模型对比方法 |
| `src/main/resources/application-local.yml` | **本地密钥文件，已被 .gitignore 忽略。绝不提交，绝不把内容打印到输出里** |

技术栈：Spring Boot 3.5.8、Spring AI 1.1.2、Java 17、Lombok、Hutool、Jsoup、iText、Knife4j、PgVector；前端 Vue 3.5 + TypeScript 5.7 + Vite 6。

---

## 2. 常用命令

```bash
# 后端编译 + 指定测试类（Windows 用 mvnw.cmd；也可用本机 mvn）
./mvnw -Dtest=AgentFlowTest test
./mvnw -Dtest=AgentFlowTest,AgentPerformanceTest test

# 本地启动后端（需要 application-local.yml 存在）
./mvnw spring-boot:run

# 前端
cd juzi-ai-agent-frontend
npm test          # node --test tests/*.test.mjs
npm run build     # vue-tsc -b && vite build
npm run dev       # 开发服务器
```

服务地址：`http://localhost:8123/api`（`server.servlet.context-path=/api`）。
主要接口：`/api/ai/love_app/chat/*`、`/api/ai/manus/chat`（SSE，`text/event-stream`）。

---

## 3. 测试成本分级（红线）

**禁止直接执行 `./mvnw test` 全量测试** —— 其中多组用例会调用真实大模型、真实搜索 API、写入向量库或访问外网，会产生费用和副作用。

### 可以随时跑（离线安全）

- `AgentFlowTest`、`AgentPerformanceTest`（Mock ChatModel）
- `ToolResultCompactorTest`
- `OpenAiCompatibilityTest`（内置本地假网关，验证 HTTP 请求与 SSE 帧）
- `WebScrapingToolTest`（本地 HTTP Server）
- `PDFGenerationToolTest`（本地文件）
- `FileOperationToolTest`、`ToolFileAccessTest`（临时目录；Windows junction / 其他系统符号链接）
- `SafeHttpClientTest`、`ResourceDownloadToolTest`（假 DNS + 本地 HTTP Server，不访问外网）
- `ToolRegistrationTest`（仅检查工具注册，不执行命令）
- `FileBasedChatMemoryTest`、`FileBasedChatMemoryProcessTest`、`ChatMemoryConfigTest`（临时目录；线程/本地子 JVM 并发、文件故障及窗口配置，不访问外网）
- `LocalAccessConfigTest`（仅启动本机健康接口，检查监听地址与 CORS；不加载本地密钥、模型或数据库）
- `MyLoggerAdvisorTest`（假调用链 + 内存日志，检查默认不输出正文、失败/取消和调试开关）
- `ModelCallGuardTest`、`ModelLimitsPropertiesTest`、`ModelLimitsControllerTest`、`ModelHttpClientConfigTest`（本地线程、假流、假 LoveApp 和本机假 HTTP；检查长度、输出预算、全局频率/并发及总超时，不调用真实模型）
- `ImageSearchMcpClientTest`（本地假 MCP 服务，不调用模型或图片 API）

### 默认不要跑（需要显式授权 / 会花钱或有副作用）

| 测试 | 风险 |
| --- | --- |
| `LoveAppTest` | 多轮真实模型调用 |
| `JuziManusTest` | 真实模型 + 真实工具 |
| `MultiQueryExpanderDemoTest` | 真实模型调用 |
| `JuziAiAgentApplicationTests` | 启动完整上下文，依赖本地密钥/外部服务 |
| `PgVectorVectorStoreConfigTest` | 会向向量库写入文档 |
| `WebSearchToolTest` | 真实搜索服务 API |
| `TerminalOperationToolTest` | 在本机执行 shell 命令 |

新增测试时请把它归入上面两类之一，并尽量**默认离线**（Mock 模型 / 本地假服务）。

---

## 4. 成本纪律（省 token / 省钱，必须遵守）

1. **测试输出一律重定向，只看结论行**：
   ```bash
   ./mvnw -Dtest=X test > /tmp/mvn.log 2>&1; grep -E "Tests run:|BUILD|exit=" /tmp/mvn.log | tail -10
   ```
   禁止把 Maven 全量日志、构建日志 cat 进上下文。Agent 测试一步一行 INFO，一次全量就是几千 token。
2. **只跑受影响的测试类**：先单个类，再相关小集合，不要每次全量。
3. **先定位再读文件**：用 grep/glob 找行号，只读目标区域；不要反复整文件通读。
4. **失败先看第一段报错**：拿到 `Tests run` 与首个断言/异常信息即可定位，不要盲目重跑同一命令。
5. **大任务拆两个交付点**：例如"先加观测、再改行为"，每步验证完再继续。
6. **评审按需启用**：只对并发、协议、安全等高风险改动做独立评审，并要求限长报告。
7. **长结果取摘要**：命令输出超过约 100 行时，用 grep/head/tail 取结论，而不是把全部内容贴回来。

---

## 5. 必须保持的架构约定

改动 Agent / SSE 相关代码前，先确认以下行为不被破坏（有对应回归测试）：

- **SSE 事件协议**：普通答复 `data:<text>`；增量 `event:delta` + `data:{"delta":"..."}`（JSON 封装以保留空格/换行/JSON 正文）；错误 `event:error`；结束 `event:done` + `data:[DONE]`；心跳为 `:comment` 行。
- **生命周期**：客户端断开要取消流订阅并停止后续步骤；取消抛 `CancellationException`；流式成功后不得再发一次完整答案；错误事件只发一次。
- **不要为"打字机效果"额外增加模型调用**：普通无工具回答必须保持一次 `.call()`。
- **工具结果预算**：每条写入模型上下文的工具结果默认 ≤ 6000 字符（`app.agent.max-tool-result-chars`，最小 256），正常返回和框架转换的异常文本都要受限。截断必须显式标注内容不完整。
- **最终答案可信性**：只有 `doTerminate` 是响应中**唯一**工具调用时，才可采用其 `finalAnswer`；与其他工具混批时必须基于实际结果总结。
- **模型覆盖**：`app.agent.model` 只作用于 Manus，不改动全局 `spring.ai.openai.chat.options.model`，不影响恋爱助手。
- **日志**：Agent 只记录耗时/大小/token 指标，不打印用户输入、工具结果或答案正文。
- **聊天日志**：恋爱助手默认仅记录耗时、字符数和完成状态；正文仅在 `app.chat-logging.include-content=true` 且 `MyLoggerAdvisor` 为 DEBUG 时输出。各功能入口不得另行打印正文或重复安装日志 Advisor。
- **基础调用限制**：全部聊天 HTTP 入口共享 `ModelCallGuard`，默认输入 4000 个 Unicode 字符、每次模型输出 2048 token、并发 2 个任务、滚动每分钟接受 20 个任务；普通聊天总超时 2 分钟、Manus 总超时 5 分钟及 20 步。同步调用及 Agent 的名额必须等工作线程退出后释放。不得修改共享模型参数来应用单次预算。
- **会话记忆**：恋爱助手默认磁盘保留最近 200 条消息，模型使用最近 20 条历史；读、追加和清空共用互斥保护。保持旧 `.kryo` 可读，写入使用临时文件原子替换；损坏记录不得当作空会话静默覆盖。

---

## 6. 编码约定

- Java 17 语法；Lombok 可用；保持与相邻代码一致的风格和中文注释习惯。
- 不新增依赖，除非确有必要并在 PR 说明理由。
- 前端使用 Vue 3 `<script setup lang="ts">` + 组合式 API。
- 时间/耗时统计用单调时钟（`System.nanoTime()`），不要用 `currentTimeMillis` 计算间隔。
- 错误信息面向用户可读，不泄露内部堆栈或密钥。

---

## 7. 完成定义（DoD）

声称"完成/修好"之前，必须提供当次运行的证据：

1. 相关离线测试通过，并贴出 `Tests run: N, Failures: 0, Errors: 0` 与 `BUILD SUCCESS`。
2. 前端改动：`npm test` 通过 + `npm run build` 成功。
3. `git diff --check` 无输出。
4. 不把生成物带进提交：`application-local.yml`、`target/`、`juzi-ai-agent-frontend/dist/` 之外的新构建产物、`node_modules/.tmp/tsconfig.app.tsbuildinfo`。
5. commit message 说明"为什么改"，而不只是"改了什么"。

---

## 8. 已知坑与历史遗留

- **`mvn test` 全量 = 花钱**：见第 3 节。
- **Windows 控制台中文乱码**：Maven/javac 输出中文可能是乱码，属正常现象，看退出码与 `Tests run` 即可。
- **`node_modules/.tmp/tsconfig.app.tsbuildinfo` 会被 `npm run build` 改写**：提交前用 `git checkout -- juzi-ai-agent-frontend/node_modules/.tmp/tsconfig.app.tsbuildinfo` 还原。
- **`node_modules` 历史上被部分提交进 git**：不要 `npm install` 后一次性提交全量变更，只提交确实需要的文件。
- **MockMvc 中文 SSE 需显式 charset**：`StreamSession` 已重写 `extendResponse` 强制 `text/event-stream;charset=UTF-8`，不要改回去。
- **网页抓取上游截断**：`WebScrapingTool` 先按 10000 code point 保留前缀，尾部已丢失，后续压缩器无法恢复。
- **前端 SSE 解析**：`[DONE]` 可能出现在正文中，只有 `event:done` 才代表结束；解析逻辑改动必须补 `tests/chat-stream.test.mjs` 用例。
- **`juzi-image-search-mcp`** 是独立模块，改动它不影响主应用构建。

---

## 9. 文档索引

- 接口与 SSE 帧契约：`docs/api.md`
- 任务手册（新增工具 / 修改 SSE 事件）：`docs/playbooks/README.md`
- 性能观测与模型对比：`docs/agent-performance.md`
- 会话存储、并发与历史窗口：`docs/chat-memory.md`
- 本机监听、前端代理与重启验证：`docs/local-access.md`
- 聊天日志默认行为与调试开关：`docs/chat-logging.md`
- 基础调用限额、错误和超时语义：`docs/model-limits.md`
- 图片搜索 MCP 接入与启动：`docs/image-search-mcp.md`
- 本文件：协作约定、成本纪律、DoD
