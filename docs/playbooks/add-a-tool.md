# 手册：新增一个工具

适用：给 Agent 增加一个新的可调用能力（文件、网络、命令、生成类操作）。

## 涉及文件

| 步骤 | 文件 |
| --- | --- |
| 1. 实现工具 | `src/main/java/com/yupi/juziaiagent/tools/XxxTool.java` |
| 2. 注册 | `src/main/java/com/yupi/juziaiagent/tools/ToolRegistration.java` |
| 3. 测试 | `src/test/java/com/yupi/juziaiagent/tools/XxxToolTest.java` |
| 4. 成本归档 | `AGENTS.md` 第 3 节（新增测试属于离线还是付费） |
| 5. 如需对外说明 | `docs/api.md` 或 `docs/agent-performance.md` |

## 步骤

### 1. 实现工具类

普通类即可（**不需要** `@Component`，统一由 `ToolRegistration` 手动 `new`）。方法用 `@Tool` 标注：

```java
public class WeatherTool {

    @Tool(description = "查询指定城市的实时天气。仅在用户询问天气时使用；城市名使用中文。")
    public String getWeather(
            @ToolParam(description = "城市名称，例如：上海") String city) {
        try {
            return "上海 晴 25℃";
        } catch (Exception e) {
            return "查询天气失败：" + e.getMessage();
        }
    }
}
```

约定：

- **工具名默认取方法名**；需要稳定命名时显式写 `@Tool(name = "getWeather", description = "...")`，改名会让模型的历史调用失效。
- `description` 是模型选择工具的唯一依据：写清「做什么、什么时候用、不要什么时候用、参数含义」。
- **失败返回可读字符串**（现有工具都是 `"Error ..."` / `"xxx失败：..."`），不要抛异常。异常文本虽然会被 6000 字符预算截断，但对模型是噪音。
- **不要自己截断结果**：`ToolCallAgent` 已统一按 `app.agent.max-tool-result-chars`（默认 6000）压缩，并标注截断。
- 返回纯文本或 JSON 字符串；不要返回 `null`。
- 有副作用的工具（写文件、执行命令、下载）要在 `description` 里说明，避免模型随意调用。

### 2. 注册

在 `ToolRegistration#allTools()` 中构造并加入 `ToolCallbacks.from(...)`：

```java
WeatherTool weatherTool = new WeatherTool();
return ToolCallbacks.from(
        fileOperationTool, webSearchTool, webScrapingTool,
        resourceDownloadTool, terminalOperationTool,
        terminateTool, pdfGenerationTool,
        weatherTool          // 新增
);
```

`allTools` 这个 Bean 同时被 `LoveApp`（恋爱助手）和 `JuziManus`（超级智能体）使用——**加在这里意味着两个 Agent 都能调用**。若只想给 Manus 用，需要另建 Bean 并在 `AiController` 构造 `JuziManus` 时传入。

### 3. 命名冲突检查

```bash
grep -rn "@Tool(" src/main/java/com/yupi/juziaiagent/tools/
```

工具名重复会导致模型调用歧义。特别注意不要占用 `doTerminate`（结束信号由框架特殊处理）。

### 4. 写离线测试

测试必须**不联网、不花钱、不写用户目录**：

```java
class WeatherToolTest {
    @Test
    void returnsReadableErrorInsteadOfThrowing() {
        String result = new WeatherTool().getWeather("不存在的城市");
        assertFalse(result.isEmpty());
    }
}
```

需要网络时，用 `com.sun.net.httpserver.HttpServer` 起本地假服务（参考 `WebScrapingToolTest`）。若确实无法离线（真实第三方 API），在 `AGENTS.md` 第 3 节把它登记为「默认不要跑」。

### 5. 验证

```bash
./mvnw -Dtest=XxxToolTest,AgentFlowTest,AgentPerformanceTest test > /tmp/mvn.log 2>&1
grep -E "Tests run:|BUILD" /tmp/mvn.log | tail -5
```

- `AgentFlowTest` / `AgentPerformanceTest` 确认工具循环与 SSE 协议没被打断。
- 不要跑全量 `mvn test`（见 `AGENTS.md` 第 3 节）。

## 完成清单

- [ ] 工具类 + `@Tool` 描述清晰，失败返回字符串
- [ ] 已加入 `ToolRegistration.allTools()`（或明确只给某个 Agent）
- [ ] 工具名无冲突，未占用 `doTerminate`
- [ ] 离线测试通过
- [ ] 新测试已在 `AGENTS.md` 第 3 节归档
- [ ] `git diff --check` 通过

## 常见坑

- 忘注册 → 模型根本看不到这个工具，表现为「答得很泛、不调用」。
- 描述太笼统（如「处理数据」）→ 模型误调用或该调用时不调用。
- 在工具里自己 `substring` 截断 → 与统一预算重复，还可能截断 JSON 导致解析失败。
- 抛异常 → 框架会把异常 message 转成工具结果喂回模型，长堆栈污染上下文。
- 工具耗时很长 → 客户端断开后无法真正中断，只能等它返回；长任务要说明清楚或在内部加超时。