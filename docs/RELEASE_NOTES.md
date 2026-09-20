更新说明（1.0.65）

• **链式代理与前置脚本可组合：** 当前（前置）订阅的覆写脚本先执行，随后由原生 Chain outbound 将脚本生成的入口接到落地节点。保存链式不再清除脚本，启用脚本也不再取消链式；脚本若删除已选入口或将其改成 DIRECT，启动会 Fail Closed 并提示错误，绝不静默直连。
• **后台智能活跃：** 服务保持前台服务与 `START_STICKY`；用户明确允许忽略电池优化时，Doze 下保持隧道和当前节点。未授权时内核在 Doze 暂停、设备唤醒后恢复。自动选择约每 10 分钟探测，并以 TCP keepalive 维持会话，减少无线电唤醒、内存和电池消耗。
• **Windows ZIP 发布：** 图形便携版发布为 `AngelaBox-v1.0.65-windows-amd64.zip`，其中含完整的解包应用、守护进程与资源，并生成 SHA-256 校验文件，避免以裸 `.exe` 触发浏览器的 HTTP 下载阻断；安装版 `.exe` 仍单独附带。命令行内核继续使用独立且不冲突的 `AngelaBox-windows-amd64.zip`（另有 arm64 / 386），其中含 `sing-box.exe`、许可证和使用说明。
• **Windows 信任与防回环：** 当前未购买商业 Authenticode 证书，CI 使用 `CN=AngelaBox Test` 自签证书；Chrome 可能标记 `.exe` 为未经验证。按 Ctrl + J，在下载页选择「保留危险文件 → 仍然保留」。Windows 使用独立的 `angelabox-daemon` 服务、数据目录和受保护命名管道，避免与官方 SFW / 旧 sing-box 服务互相接管造成代理回环。
• **完全开源构建：** 本项目的源码、GitHub Actions 构建流程、Windows 打包脚本和发布说明均公开可审计。
