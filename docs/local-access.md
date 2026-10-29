# 前后端同机访问

当前默认使用 `local` profile。前后端都在一台电脑运行时，启动后使用以下地址：

| 服务 | 地址 | 配置位置 |
| --- | --- | --- |
| 前端开发 | `http://127.0.0.1:5173` | `juzi-ai-agent-frontend/vite.config.ts` 的 `server` |
| 前端预览 | `http://127.0.0.1:4173` | 同文件的 `preview` |
| 后端健康接口 | `http://127.0.0.1:8123/api/health` | `application.yml` 中仅对 `local` 生效的段 |

`127.0.0.1` 是本机回环地址：常规网络连接只能从本机到达。`0.0.0.0` 和 `::` 是通配监听地址，不能据此认定服务仅供本机使用；即使浏览器用 `localhost` 打开，服务也可能监听其他网卡。

前端启动命令 `npm run dev` 和 `npm run preview` 不再通过参数将监听地址覆盖为 `0.0.0.0`。开发端口固定为 5173，预览固定为 4173；占用时会报错，不自动跳到不在允许来源列表里的其他端口。

## 前端如何连接后端

前端请求相对路径 `/api/...`，Vite 将它代理到 `http://127.0.0.1:8123`。预览继承同一代理配置。使用 IPv4 回环地址避免 `localhost` 被解析到 IPv6 而与后端绑定不一致。

这只限制别人连接本机服务，不会阻止后端向外访问模型或公网工具。

后端 CORS 允许以下精确来源：

- `http://127.0.0.1:5173`
- `http://localhost:5173`
- `http://127.0.0.1:4173`
- `http://localhost:4173`

普通本机前端走同源 `/api` 代理，不依赖跨域；上述列表也允许本机页面直接跨域访问后端。CORS 仅约束浏览器跨域请求，不是身份认证；本机其他程序仍可以连接。

## 重启和确认

修改配置不会自动改变已有监听。先在原终端或 IDE 停止前后端，随后从项目根目录启动后端：

```powershell
.\mvnw.cmd spring-boot:run
```

从前端目录启动开发服务：

```powershell
npm run dev
```

用 PowerShell 检查实际监听：

```powershell
Get-NetTCPConnection -State Listen |
    Where-Object { $_.LocalPort -in @(8123, 5173, 4173) } |
    Select-Object LocalAddress, LocalPort, OwningProcess
```

正在使用的这些端口应只显示 `127.0.0.1`，未启动的端口不显示。再打开健康接口，预期返回 `ok`，并打开前端页面确认能加载。正式聊天仍会调用配置的模型，离线验证不发送聊天请求。

后端本机地址段仅在 `local` 生效，不改变单独 `prod` profile 的监听行为。命令行 `--server.address`、环境变量 `SERVER_ADDRESS`、更高优先级的外部配置，以及 Vite 的 `--host` 参数仍可覆盖默认值；以重启后的实际监听为准。若以后通过容器或其他电脑访问，需要重新配置监听和网页来源。

`CorsConfig` 的本机来源列表对所有 profile 生效，因此以后使用外部域名前端部署 `prod` 时，也要修改允许来源；`prod` 监听地址未收紧，并不代表外部页面仍可跨域访问。

## 离线测试

`LocalAccessConfigTest` 从公开 `application.yml` 读取 local 段，用最小 Web 应用实际绑定回环地址，验证健康接口、允许来源、预检请求及拒绝来源。它不加载 `application-local.yml`。

前端 `tests/local-access.test.mjs` 启动随机端口的开发和预览服务，检查实际监听与页面响应、代理目标；服务和临时预览目录在结束后清理，不中断正在运行的开发进程，也不调用模型。
