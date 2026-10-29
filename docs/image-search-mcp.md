# 在主项目中调用图片搜索 MCP

图片服务和主项目分别运行，通过 MCP SSE 连接：

```text
GET /api/ai/love_app/chat/mcp
  → LoveApp.doChatWithMcp
  → Spring AI MCP ToolCallbackProvider
  → http://127.0.0.1:8127/sse
  → searchImage
  → Pixabay
```

## 1. 先启动图片服务

在 IDEA 中编辑 `JuziImageSearchMcpApplication` 的运行配置：

- Active profiles：`sse`（或程序参数 `--spring.profiles.active=sse`）。
- Environment variables：`PIXABAY_API_KEY=你的真实Key`。
- 启动后确认 Tomcat 监听 `8127`。默认的 `stdio` 模式不会开放 HTTP 端口。

也可以在 PowerShell 中启动：

```powershell
Set-Location 'E:\AI-Agent-code\juzi-ai-agent\juzi-image-search-mcp'
$env:PIXABAY_API_KEY = '你的真实Key'
mvn spring-boot:run '-Dspring-boot.run.profiles=sse'
```

## 2. 再启动主项目

在 IDEA 中编辑 `JuziAiAgentApplication` 的运行配置：

- Active profiles：`local,mcp-image`，保留 `local`，并把 `mcp-image` 放在后面。
- Working directory：`E:\AI-Agent-code\juzi-ai-agent`。
- 模型和数据库仍使用现有本地配置。Pixabay Key 由图片服务持有。

PowerShell 启动方式：

```powershell
Set-Location 'E:\AI-Agent-code\juzi-ai-agent'
mvn spring-boot:run '-Dspring-boot.run.profiles=local,mcp-image'
```

新增的 `application-mcp-image.yml` 启用 MCP 客户端、连接图片服务并关闭旧 JSON 的 stdio 子进程入口。
无需为这条 SSE 连接修改 `mcp-servers.json` 或打包图片服务 jar。
如果图片服务在其他地址，给主项目设置环境变量 `IMAGE_SEARCH_MCP_URL`，只填基础地址，例如 `http://127.0.0.1:8127`。

主项目会在启动时连接 MCP 服务，因此图片服务需要先启动。若启动日志提示连接失败，先检查图片服务是否使用 `sse` profile。

## 3. 从主项目发起对话

此步骤会调用真实模型；模型决定使用图片工具时，还会调用 Pixabay：

```powershell
$message = [Uri]::EscapeDataString('请使用图片搜索工具搜索 computer，返回 3 个真实图片链接。')
$chatId = [Guid]::NewGuid().ToString()
Invoke-RestMethod -Uri "http://localhost:8123/api/ai/love_app/chat/mcp?message=$message&chatId=$chatId"
```

在图片服务 `ImageSearchTool.searchImage()` 上打断点，可以确认主项目确实通过 MCP 调用了工具。

目前 MCP 工具接入的是上面的独立对话接口。普通聊天接口、现有前端聊天和 Manus 工具列表仍按原有方式工作。

## 离线验证

`ImageSearchMcpClientTest` 属于离线安全测试：读取本次新增的客户端配置，连接本机假 MCP 服务，验证工具发现、工具调用和参数传递；不读取 `application-local.yml`，不调用真实模型或图片 API。

```powershell
mvn '-Dtest=ImageSearchMcpClientTest' test
```

遵守仓库约定，运行时将 Maven 输出重定向到日志，只查看测试统计和构建结论。
