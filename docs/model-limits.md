# 基础模型调用限制

针对前后端同机、个人使用设置基础保护，全部六个聊天 HTTP 入口共享同一份任务计数，包含同步、MCP、三种 SSE 和 Manus。健康检查不计入。

| 配置（前缀 `app.model-limits`） | 默认值 | 含义 |
| --- | --- | --- |
| `max-input-chars` | `4000` | 当前用户输入的 Unicode 字符数；一个 emoji 按一个 code point 计，空白输入拒绝 |
| `max-output-tokens` | `2048` | 每一次模型请求的输出预算；不是汉字数，也不是一个 Agent 全程的总预算 |
| `output-token-parameter` | `auto` | 沿用原有输出字段；也可明确设为 `max-tokens` 或 `max-completion-tokens` |
| `manus-output-token-parameter` | `auto` | 默认沿用上项及原有字段；Manus 使用不同模型时可单独指定输出字段 |
| `max-concurrent-tasks` | `2` | 当前后端进程最多同时处理的聊天任务，所有会话和接口共用 |
| `max-tasks-per-minute` | `20` | 最近滚动 60 秒最多接受 20 个任务，不按整点分钟重置 |
| `chat-timeout` | `2m` | 普通聊天从任务开始计算的总时限，不因收到新分片而重新计时 |
| `manus-timeout` | `5m` | Manus 整个任务的总时限，心跳不会续期 |
| `manus-max-steps` | `20` | Manus 最多执行 20 个规划/工具步骤；必要的最终总结仍是一次独立模型请求 |

这些字段已经写在公开的 `application.yml` 中；可通过本地配置覆盖，或分别设置 `MODEL_MAX_INPUT_CHARS`、`MODEL_MAX_OUTPUT_TOKENS`、`MODEL_MAX_CONCURRENT_TASKS`、`MODEL_MAX_TASKS_PER_MINUTE`、`MODEL_CHAT_TIMEOUT`、`MODEL_MANUS_TIMEOUT`、`MODEL_MANUS_MAX_STEPS` 环境变量。修改后重启后端。所有数值必须大于零，时限至少为 1ms，否则启动失败。

两个输出字段配置对应 `MODEL_OUTPUT_TOKEN_PARAMETER` 和 `MODEL_MANUS_OUTPUT_TOKEN_PARAMETER`。网关支持的字段由使用的模型决定，不按模型名称猜测；默认 `auto` 兼容已有配置，无原配置时使用 `max_tokens`。

当前接口使用 GET URL 参数，公开配置同时将后端、前端开发服务器和预览服务器的请求行及请求头总上限调整为 64KB，让默认 4000 个中文或 emoji 编码后可以到达应用层校验。若自行把输入上限调得更大，还要考虑服务器和代理的 URL 长度限制。

## 为什么这样限制

限制输入和输出可以约束单次请求规模；限制并发和频率可以挡住重复点击、多开标签页或脚本循环请求。后端统一检查，所以仅修改浏览器页面不能绕过。

拒绝的请求不会调用模型，也不会占用每分钟的接受名额；已经接受但随后失败、取消或超时的任务仍计入频率，防止用失败请求反复重试。频率计数只在内存中，后端重启后清零。

普通聊天、报告、RAG、工具/MCP 回答以及 RAG 查询改写均设置输出预算；Manus 规划和最终总结同样设置。保留原模型名、温度等配置，不修改共享模型。原来配置更小的输出上限时继续采用更小值；原来使用 `max-completion-tokens` 时继续使用该字段，否则使用 `max-tokens`。模型网关仍需支持并遵守相应字段。

Spring 自动配置的阻塞 `RestClient.Builder` 还设置底层 HTTP 超时：连接阶段最多 10 秒（聊天时限更短时取更短值），响应头及响应体等待总计不超过普通聊天时限。该 builder 的其他使用方同样受此网络超时约束。Manus 每次阻塞模型请求也使用这一网络上限，整个任务仍受 5 分钟总时限控制。

## 错误与取消

- 请求尚未开始返回正文：无效/过长输入返回 HTTP 400，并发/频率超限返回 HTTP 429（带 `Retry-After` 秒数），普通聊天超时返回 HTTP 504。提示不包含输入或上游堆栈。
- 普通 SSE 已开始发送正文：连接已使用 HTTP 200，无法再改成 504；超时取消上游，并发送一次 `event:error`，其 `data` 为 `{"message":"超时提示"}`，使用前端已有的 JSON 消息解析和错误提示能力，保留已有文本。普通正文仍为原先的 `data:<text>`，正常完成仍兼容 EOF。尚未输出正文的超时返回 HTTP 504。
- Manus 总超时：取消任务，发送一次 `event:error` 及 `event:done`，不再继续下一步。正常的 delta/done 协议保持不变。
- 浏览器停止生成或连接报错：响应式流取消；手动 `SseEmitter` 入口也释放订阅。同步调用没有流式断开通知，受总超时保护。

同步调用超时会中断工作线程；Manus 超时/断开也会中断工作线程并在下一步骤之前检查取消。某些 SDK 或工具可能不能立刻响应中断，因此这些任务的并发名额会保留到工作线程真正退出，避免实际工作堆积。响应式流的名额在流完成、报错或取消时释放。已发到模型服务端的请求无法由本地保证停止计费。

这些上限用于基础保护，不代表按人民币计费的硬预算。一个 Manus 任务可能多次调用模型；MCP/工具对话也可能由框架执行多个模型请求。任务频率统计按外部聊天任务计，不按内部模型请求计。历史消息、系统提示词和工具上下文仍会消耗输入 token；会话窗口和工具结果预算分别见 `chat-memory.md`、`agent-performance.md`。本限制不覆盖手工执行的 demo、测试或绕过 Controller 的 Java 调用的任务名额。

## 离线检查

新增 `ModelCallGuardTest`（输入边界、原子并发、滚动频率、总超时和资源释放）、`ModelLimitsPropertiesTest`（输出预算及原配置兼容）、`ModelLimitsControllerTest`（六入口拒绝、HTTP 状态及 Retry-After）、`ModelHttpClientConfigTest`（本机假服务器等待响应头和响应体的网络超时）。`AgentFlowTest` 验证定时超时的 SSE 帧与工作线程退出；`OpenAiCompatibilityTest` 检查实际发往本机假网关的输出上限和 4000 字符边界。

```powershell
.\mvnw.cmd '-Dtest=ModelCallGuardTest,ModelLimitsPropertiesTest,ModelHttpClientConfigTest,ModelLimitsControllerTest,AgentFlowTest,AgentPerformanceTest,OpenAiCompatibilityTest,MyLoggerAdvisorTest' test *> "$env:TEMP/juzi-model-limits-test.log"
Select-String -Path "$env:TEMP/juzi-model-limits-test.log" -Pattern 'Tests run:|BUILD SUCCESS|BUILD FAILURE'
```
