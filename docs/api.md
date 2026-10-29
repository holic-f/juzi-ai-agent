# 接口契约

后端：Spring Boot，`server.port=8123`，`server.servlet.context-path=/api`。
所有接口均为 `GET`，参数通过 query string 传递，**没有请求体**。

前端调用入口：`juzi-ai-agent-frontend/src/views/ChatView.vue`（`fetch` + `ReadableStream` 手工解析 SSE，未用 `EventSource`）。

---

## 1. 接口总览

| 方法 | 路径 | 返回 | 用途 | 超时 |
| --- | --- | --- | --- | --- |
| GET | `/api/ai/love_app/chat/sync` | `text/plain` 字符串 | 恋爱助手同步对话 | 总计 2 分钟 |
| GET | `/api/ai/love_app/chat/mcp` | `text/plain` 字符串 | 恋爱助手 + MCP 工具对话 | 总计 2 分钟 |
| GET | `/api/ai/love_app/chat/sse` | `text/event-stream`（`ServerSentEvent`） | 恋爱助手流式对话 | 总计 2 分钟 |
| GET | `/api/ai/love_app/chat/sse/event` | `text/event-stream`（`ServerSentEvent`） | 同上，带 event 包装 | 总计 2 分钟 |
| GET | `/api/ai/love_app/chat/sse/emitter` | `text/event-stream` | 同上，`SseEmitter` 实现 | 总计 2 分钟 |
| GET | `/api/ai/manus/chat` | `text/event-stream` | **超级智能体 Manus** 流式对话 | 总计 5 分钟 |

### 参数

| 参数 | 适用接口 | 必填 | 说明 |
| --- | --- | --- | --- |
| `message` | 全部 | 是 | 用户输入文本，需 URL 编码 |
| `chatId` | 仅 `/love_app/chat/*` | 建议 | 会话 ID，用于多轮记忆；缺省时由后端记忆实现决定 |

上述时限均可配置。所有聊天入口共享默认限制：输入 4000 个 Unicode 字符、每次模型输出 2048 token、并发 2 个任务、滚动每分钟接受 20 个任务；输入无效返回 400，超限返回 429 并带 `Retry-After`，未开始输出时的普通聊天超时返回 504。详见 `model-limits.md`。

恋爱助手正常 SSE 正文帧及 EOF 完成方式不变；部分正文已输出后超时，发送一次 `event:error`，`data` 为 `{"message":"超时提示"}`，前端保留正文并显示错误。

> `/ai/manus/chat` **不接收 `chatId`**：每次请求都会新建一个 Agent 实例，不跨请求保留对话记忆。

---

## 2. Manus SSE 协议（`/api/ai/manus/chat`）

响应头：`Content-Type: text/event-stream;charset=UTF-8`（`StreamSession.extendResponse` 强制，避免中文乱码）。

### 帧类型

| 帧 | 示例 | 含义 |
| --- | --- | --- |
| 注释 | `:connected` | 连接建立，立即发出，用于刷新响应头；前端忽略 |
| 注释 | `:heartbeat` | 每 **15 秒** 一次，防止空闲断流；前端忽略 |
| 普通数据 | `data:完整答案` | 非流式答复：一次性给出完整文本 |
| 增量 | `event:delta` + `data:{"delta":"片段"}` | 流式答复的片段 |
| 错误 | `event:error` + `data:错误说明` | 执行失败，正文为面向用户的中文说明 |
| 结束 | `event:done` + `data:[DONE]` | 正常结束，**每条流的最后一个帧** |

### 原始报文示例

非流式（无工具直接回答）：

```
:connected

data:你好，我是超级智能体。

event:done
data:[DONE]

```

流式总结（工具执行后需要生成最终答复）：

```
:connected

event:delta
data:{"delta":"根据工具结果，"}

:heartbeat

event:delta
data:{"delta":"文件已生成。"}

event:done
data:[DONE]

```

失败：

```
:connected

event:error
data:任务执行失败，请稍后重试。

event:done
data:[DONE]

```

### 必须遵守的规则

1. **`data` 帧与 `event:delta` 不会同时出现**：流式成功时只发 delta，不再补发一次完整答案（`answerStreamed` 标记控制）；否则前端会显示两遍。
2. **`event:done` + `data:[DONE]` 是唯一可靠的结束信号**。普通 `data:[DONE]`、显式 `event:message` 中的 `[DONE]` 和 `event:delta` 中的 `[DONE]` 都属于正文，必须保留并继续接收后续内容。Manus 在未收到有效结束帧时连接就结束，仍应提示回复可能不完整；恋爱助手保留下文的 EOF 兼容规则。
3. **`event:error` 只发一次**，且其后仍会跟 `event:done`；前端需保留已收到的正文并追加错误提示。
4. **delta 是 JSON 对象**，必须 `JSON.parse(data).delta` 取值，不能直接拼接原始 `data`——否则片段里的空格、换行、引号会失真。
5. **客户端断开**：服务端检测到写失败会取消任务、中断工作线程并停止心跳，不会继续调用模型或写文件。
6. 心跳是注释帧（以 `:` 开头），**不产生 `data` 行**，解析时必须忽略。

---

## 3. 恋爱助手 SSE（`/api/ai/love_app/chat/sse`）

与 Manus 不同，这是 Spring 的 `Flux<String>`：

```
data:第一段

data:第二段

```

- **没有 `event:done`、没有 `event:error`、没有心跳**。
- 流结束 = HTTP 连接正常关闭（EOF），前端据此判定成功；连接中途断开则判定为失败。
- 若把前端解析逻辑改成"必须有 done 才算成功"，会直接打断恋爱助手，改动前先看 `tests/chat-stream.test.mjs` 里的 EOF 兼容用例。

`/sse/event` 与 `/sse/emitter` 只把同样的内容包进 SSE 事件，帧结构相同。

---

## 4. 错误语义

| 现象 | 前端应如何判定 |
| --- | --- |
| HTTP 状态非 2xx | 失败，展示「服务请求失败（HTTP xxx）」 |
| 连接建立后读取报错 / 无 `event:done` 就 EOF（Manus） | 失败，保留已收到的正文 |
| 收到 `event:error` | 失败，保留正文并追加错误说明 |
| 收到 `event:done` | 成功结束 |
| 用户主动停止 | 取消请求，忽略其后的读取异常，不提示错误 |

---

## 5. 模型与配置

| 配置 | 作用 |
| --- | --- |
| `spring.ai.openai.chat.options.model` | 全局模型（恋爱助手使用） |
| `app.agent.model` | **仅覆盖 Manus**；留空则沿用全局模型 |
| `app.agent.max-tool-result-chars` | 单条工具结果写入模型上下文的上限，默认 6000，最小 256 |

密钥位于 `src/main/resources/application-local.yml`（已被 git 忽略），**不要提交、不要打印**。
