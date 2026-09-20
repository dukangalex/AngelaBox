更新说明（测试版）
• **Windows 图形客户端：** 推荐 `AngelaBox-v1.0.65-beta-windows-amd64.zip`（便携包，解压运行 `AngelaBox.exe`）。GitHub zip 一般能过 Chrome 下载拦截。安装器 `AngelaBox-windows-*.exe` 仍是自签，若拦截请 Ctrl+J → 保留危险文件 → 仍然保留。不要把无版本号的 `AngelaBox-windows-amd64.zip` 当成图形界面，那个是命令行。
• 链式与前置脚本不再互斥：先跑入口订阅脚本，再按入口→落地组链。落地配置上的脚本不会套到当前配置。
• Windows TUN 强制 `auto_detect_interface`、严格路由，并把 `AngelaBox.exe` / `sing-box-daemon.exe` 排除出隧道，避免回环。
• 内核跟进官方 sing-box **v1.15.0-alpha.6**。Go 1.25.5，fork tag `v1.15.0-chain.3`，commit `d7639f61`。设置 → 核心显示 `1.15.0-chain.3（官方 1.15.0-alpha.6）`。
• Android 启动器图标为透明底立方体。默认覆写脚本 overlay-revision 12：自动选择每 10 分钟测一次，空闲 4 小时才停测，叶节点 TCP keepalive。忽略电池优化后息屏不再暂停整条隧道。
• 本版是 **测试内核**，不是稳定版。日常使用请留在 1.0.57。订阅 / 脚本 / 更新 / WebDAV 仅允许公网 HTTPS。构建过程完全开源；未购买商业代码签名证书。
