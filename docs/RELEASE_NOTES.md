• **Windows 图形客户端：** 推荐 `AngelaBox-v1.0.66-beta-windows-amd64.zip`。解压后运行 `AngelaBox.exe`，第一次会提示安装 AngelaBox 服务，点「安装」并允许管理员权限。安装器 `AngelaBox-windows-*.exe` 仍是自签，若 Chrome 拦截请 Ctrl+J → 保留危险文件 → 仍然保留。不要把无版本号的 `AngelaBox-windows-amd64.zip` 当成图形界面，那个是命令行。
• 检查更新只看本仓库 AngelaBox 发行，不再误报官方 sing-box 1.15。托盘、窗口、服务提示一律显示 AngelaBox。
• 默认脚本 overlay-revision 13：叶节点 `tcp_keep_alive` 改为时长 `60s`（不能写 true，否则内核拒启）。脚本启动失败时绑定保留，不再被规范化强行关掉。
• 链式与前置脚本不再互斥：先跑入口订阅脚本，再按入口→落地组链。落地配置上的脚本不会套到当前配置。
• Windows TUN 强制 `auto_detect_interface`、严格路由，并把 `AngelaBox.exe` / `sing-box-daemon.exe` 排除出隧道，避免回环。出站仍走代理节点，只是连节点时用真实网卡，不会把代理流量泄到直连。
• 内核跟进官方 sing-box **v1.15.0-alpha.6**。Go 1.25.5，fork tag `v1.15.0-chain.3`，commit `d7639f61`。设置 → 核心显示 `1.15.0-chain.3（官方 1.15.0-alpha.6）`。
• Android 启动器图标为透明底立方体。自动选择每 10 分钟测一次，空闲 4 小时才停测。忽略电池优化后息屏不再暂停整条隧道。
• 本版是 **测试内核**，不是稳定版。日常使用请留在 1.0.57。订阅 / 脚本 / 更新 / WebDAV 仅允许公网 HTTPS。构建过程完全开源；未购买商业代码签名证书。
