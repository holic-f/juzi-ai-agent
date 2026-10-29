# Manus 响应速度与耗时定位

## 已采用的优化

- 每条工具结果默认最多 6000 个 Java UTF-16 字符（不是 token）。超限保留头尾，并明确标记内容不完整。正常返回和 Spring AI 转换的异常结果都受预算限制。
- 网页结果可本地提取正文，不额外调用模型做摘要。已经由上游工具截掉的内容不能恢复。
- 每轮 nextStep 提示只添加到当前请求，不随历史轮数重复积累。
- 普通无工具回答保留一次模型请求，不为流式显示额外请求一次。
- 模型单独调用 `doTerminate(finalAnswer)` 时直接采用该最终答案，省去重复总结。与其它工具混批时，预写答复不能采用，必须根据实际执行结果总结。
- 无最终答案的旧式 `doTerminate` 仍需一次总结，但该次请求使用流式响应，发送 `event:delta`，最后发送 `event:done`。工具规划文字不发送到正文。
- 每段 delta 使用 JSON 对象 `{ "delta": "文本" }`，精确保留空格、换行、JSON 正文与字面 `[DONE]`；流式完成后不重复发送整段答案。
- Manus 不再启用完整请求/回答内容日志，只记录计时、大小及 token 指标。恋爱助手不受此次改动影响。

## 配置

默认不切换真实模型。只有确认当前网关支持目标型号和工具调用后再设置：

```yaml
app:
  agent:
    model: ${MANUS_MODEL:}
    max-tool-result-chars: ${MANUS_MAX_TOOL_RESULT_CHARS:6000}
```

- `MANUS_MODEL` 为空：沿用全局 `spring.ai.openai.chat.options.model`。
- 非空：仅覆盖 HTTP Manus 请求新建的代理，不改变恋爱助手。
- `MANUS_MAX_TOOL_RESULT_CHARS` 最小 256。可根据任务提高，例如完整分析较大文件时设为 12000；更小输入可能更快，但会损失工具结果中间的细节。
- 不自动发送未经网关验证的 reasoning / thinking 参数。

## 日志怎么看

每次任务都有 `run` 标识，用它串联各步骤：

```text
agent_model run=... step=... phase=plan completed=true elapsed_ms=... input_chars_estimate=...
agent_model_result run=... step=... model=... prompt_tokens=... completion_tokens=... tool_calls=...
agent_tool run=... step=... tool=... completed=true elapsed_ms=... raw_chars=... context_chars=...
agent_model_first_delta run=... step=... phase=final first_delta_ms=...
agent_model run=... step=... phase=final completed=true elapsed_ms=... input_chars_estimate=... output_chars=...
agent_final run=... source=terminate skipped_summary=true
agent_task run=... state=FINISHED steps=... elapsed_ms=...
```

- `elapsed_ms` 为单调时钟计算的耗时（毫秒）。任务耗时包含排队、模型、工具和收尾，不等于某一次模型耗时。
- `first_delta_ms` 从最终总结请求开始到首个非空增量成功发送，并非整个任务的首字时间。
- `input_chars_estimate` 在请求前估算 system、消息正文、工具调用参数及相关工具定义的文本量；不是完整序列化请求字节数，不是 token 数。实际 token 以服务商 usage 为准，服务商未返回时可能为 0。
- 工具 `completed=true` 只代表回调正常返回；返回 `Error ...` 仍可能是业务失败。
- 如果模型阶段占主要耗时，优先比较低延迟模型；如果工具阶段占主要耗时，应优化相应网络/文件/终端工具；如果步骤很多，检查重复操作和任务结束条件。

## 比较模型的正确方式

先在当前型号收集同一组代表性任务，再只修改 `MANUS_MODEL` 重启后重复测试。每种型号至少重复多次，比较：

1. 总任务耗时中位数和较慢请求耗时；
2. 模型请求次数、工具调用次数和任务完成率；
3. 最终总结首段耗时与内容准确性；
4. 输入/输出 token 及费用。

不要只用一次问候语的速度判断工具任务，也不要把离线模拟测试的毫秒数当作真实供应商性能。本次没有进行真实 API 型号性能对比。

## 验证范围

离线回归命令：

```bash
mvn -Dtest=AgentFlowTest,AgentPerformanceTest,ToolResultCompactorTest,OpenAiCompatibilityTest,WebScrapingToolTest,PDFGenerationToolTest test
cd juzi-ai-agent-frontend
npm test
npm run build
```

本地 OpenAI 兼容网关测试验证实际 HTTP 请求、独立型号覆盖、禁用工具的最终流式请求、delta 与 done 协议。前端测试直接执行组件脚本，但不代替真实浏览器的 Vue 生命周期、代理和慢客户端压力测试。

仓库中的部分旧测试会调用外部模型、搜索 API、MCP 或写入向量库；不要在未准备隔离配置时直接运行它们。

修改后重启后端，再刷新前端页面。正在运行且不响应中断的工具仍需等待返回；取消会阻止后续步骤和发送操作。
