# 手册：新增或修改 SSE 事件

适用：给 Manus 的流式接口增加新事件（进度、思考、引用来源等），或修改现有 `delta` / `error` / `done` 行为。

> 这是**协议级改动**，前后端 + 测试需要一起改，且不能破坏既有语义。改动前先读 `docs/api.md` 第 2 节。

## 涉及文件

| 层 | 文件 | 作用 |
| --- | --- | --- |
| 发出 | `agent/model/BaseAgent.java` → `StreamSession` | `send()` / `delta()` / `finish()`，统一出口 |
| 触发 | `agent/model/ToolCallAgent.java` | 何时发（规划后、工具后、总结流式） |
| 前端解析 | `juzi-ai-agent-frontend/src/views/ChatView.vue` | `appendSseFrame()` / `parseEventData()` |
| 后端测试 | `src/test/.../AgentFlowTest.java`、`AgentPerformanceTest.java` | 断言真实帧顺序 |
| 前端测试 | `juzi-ai-agent-frontend/tests/chat-stream.test.mjs` | 逐字节分帧、JSON 负载、终止语义 |
| 文档 | `docs/api.md` 第 2 节、`AGENTS.md` 第 5 节 | 协议契约 |

## 步骤

### 1. 后端定义事件

所有发送都必须走 `StreamSession`，不要直接操作 `emitter`：

```java
// 结构化负载用 JSON，禁止裸拼字符串
private void progress(String text) {
    try {
        String data = EVENT_JSON.writeValueAsString(Map.of("stage", text));
        if (!send(SseEmitter.event().name("progress").data(data, UTF8_TEXT))) {
            throw new CancellationException("SSE connection closed");
        }
    } catch (JsonProcessingException e) {
        throw new IllegalStateException("Cannot encode progress event", e);
    }
}
```

要点：

- `send()` 返回 `false` 表示连接已断（内部已 `disconnect()`），**必须**抛出 `CancellationException` 终止任务，不能继续执行。
- 文本统一用 `UTF8_TEXT` 媒体类型，避免中文乱码。
- 结构化数据一律 JSON 封装：前端要能精确保留空格、换行、引号。

### 2. 决定发送时机

- 规划阶段不要发用户可见内容（`thinking` 属于内部过程）。
- 工具执行前后可发进度类事件，但**不能替代**最终答复。
- 最终答复只有两条路径：`delta` 流式，或 `finish()` 里的整段 `data`。二者互斥，由 `answerStreamed` 控制。

### 3. 前端解析

在 `appendSseFrame()` 中按 `event:` 分发。**当前实现有一个兜底分支**：未识别的事件会走到 `if (data) message.content += parseEventData(data)`，即被当成正文追加。所以新增事件时**必须显式加分支**，让它要么更新 UI、要么被忽略：

```ts
switch (event) {
  case 'delta': /* JSON.parse(data).delta 追加 */ break
  case 'progress': /* 更新进度 UI，不影响正文 */ break
  case 'error': /* 标记失败，保留正文 */ break
  case 'done': /* 收尾 */ break
  default: /* 显式忽略，别让它落到兜底追加分支 */ break
}
```

### 4. 补测试（缺一不可）

后端（MockMvc 抓原始帧）：

```java
assertTrue(body.contains("event:progress"));
assertEquals(1, body.split("event:done", -1).length - 1);  // done 只能一次
assertFalse(body.contains("event:error"));
```

前端（`tests/chat-stream.test.mjs`）：

- 新事件逐字节拆分后仍能正确解析；
- 新事件**不进入**正文（除非设计如此）；
- 新事件之后 `delta` / `error` / `done` 的既有语义不变。

### 5. 验证

```bash
./mvnw -Dtest=AgentFlowTest,AgentPerformanceTest test > /tmp/mvn.log 2>&1
grep -E "Tests run:|BUILD" /tmp/mvn.log | tail -5

cd juzi-ai-agent-frontend
npm test > /tmp/fe.log 2>&1; tail -5 /tmp/fe.log
npm run build > /tmp/build.log 2>&1; tail -3 /tmp/build.log
```

## 不可破坏的既有语义（改动前逐条对照）

1. `event:done` + `data:[DONE]` 每条流**只发一次**，且是最后一个帧。
2. `event:error` 每条流最多一次，之后仍要有 `done`。
3. 流式成功后**不得**再补发整段 `data`（否则正文重复）。
4. 客户端断开必须能取消：`CancellationException` 要能穿透到任务循环。
5. 心跳 `:heartbeat` 是注释帧，不能带 `data`，前端忽略。
6. 恋爱助手 `/love_app/chat/sse` 是**无 done 的 EOF 语义**，不能套用 Manus 规则。
7. `delta` 的 `data` 是 JSON 对象，不是裸文本。

## 完成清单

- [ ] 新事件走 `StreamSession.send()`，断连即抛 `CancellationException`
- [ ] 结构化负载 JSON 封装，UTF-8
- [ ] 前端 `default` 分支忽略未知事件
- [ ] 后端帧顺序测试 + 前端分帧测试都补了
- [ ] `docs/api.md` 第 2 节事件表已更新
- [ ] `AGENTS.md` 第 5 节（如涉及新约束）
- [ ] 后端相关测试、`npm test`、`npm run build` 全部通过

## 常见坑

- 直接 `emitter.send(...)` 绕过 `StreamSession` → 断连检测、心跳、`closed` 状态全部失效。
- 发完事件忘了检查返回值 → 在已断开的连接上继续跑完整个任务，白花钱。
- 用裸文本发 delta → 片段中的空格/引号被前端解析吃掉（已用 JSON 封装修过一次）。
- 新增事件后没改前端 `default` 分支 → 旧版本前端把新帧当正文，界面出现乱码。
- 测试只断言「包含某字符串」→ 抓不到重复 `done`、重复整答这类协议错误。