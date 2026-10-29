# 聊天日志

恋爱助手的所有聊天入口共用 `MyLoggerAdvisor`。默认只记录一条完成指标，例如：

```text
AI chat mode=call status=complete durationMs=850 inputChars=18 outputChars=120
```

- `mode`：普通调用为 `call`，流式回复为 `stream`。
- `status`：正常完成为 `complete`，失败为 `error`，流式取消为 `cancel`。
- `durationMs`：本次调用耗时，单位毫秒，使用单调时钟计算。
- `inputChars`：当前用户消息的 Java 字符串长度，不包含系统提示词或历史消息。
- `outputChars`：普通调用的回答长度，或流式调用收到的文本分片长度之和；失败/取消时为已收到的长度。工具参数、图片不计入文本长度；长度不是 token 数。

默认日志不包含用户输入、AI 回复、恋爱报告正文、工具结果或异常消息。流式日志不额外拼接完整答案，不增加模型调用。

## 临时查看正文

默认配置为 `app.chat-logging.include-content: false`，对应环境变量 `CHAT_LOG_INCLUDE_CONTENT`。不配置也默认为关闭；仅把全局日志级别改为 DEBUG 仍不会输出正文。

确需排查时，可在本地配置中同时设置：

```yaml
app:
  chat-logging:
    include-content: true
logging:
  level:
    com.yupi.juziaiagent.advisor.MyLoggerAdvisor: DEBUG
```

将这些字段合并进已有配置，避免重复的 `app` 或 `logging` 顶层键。重启后生效，流式回答按收到的分片记录 DEBUG 正文。排查结束后关闭 `include-content` 并重启。

关闭日志只影响之后产生的项目聊天日志，不删除已有日志，也不关闭会话记忆保存；会话存储设置见 `chat-memory.md`。本开关不控制自行开启的 HTTP 请求体跟踪或第三方 SDK 的详细日志。

## 离线验证

Windows PowerShell：

```powershell
.\mvnw.cmd '-Dtest=MyLoggerAdvisorTest,OpenAiCompatibilityTest' test *> "$env:TEMP/juzi-chat-logging-test.log"
Select-String -Path "$env:TEMP/juzi-chat-logging-test.log" -Pattern 'Tests run:|BUILD SUCCESS|BUILD FAILURE'
```

前者使用假调用链和内存日志，后者只请求本机假模型服务，不使用真实模型密钥。
