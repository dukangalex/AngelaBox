• **发行完整性：** 发版工作流现在拒绝 `version_tag` 与 `version.properties` 的 `VERSION_NAME` 不一致的请求，防止 Release tag、安装包版本和更新提示脱节。本包版本为 **1.0.68-beta**。
• **1.0.68：** 配置规范化在规则集 404 后会重启命令通道再试，不再弹出原始 EOF；失败时保留脚本绑定，只有脚本自己写出内核读不了的字段才回滚。仪表 Rule 芯片补上全局 / 直连。默认脚本 overlay-revision 14 增加 ⚖️ 负载均衡、🛡️ 故障转移（urltest 近似）。配置页三个点可打开「提供者」管理规则集（查看 / 同步 / 上传）。Windows 启动画面移出 React 根节点，空屏时仍能看到 AngelaBox。
• **Windows 图形客户端：** 推荐 `AngelaBox-v1.0.68-beta-windows-amd64.zip`。先卸载 1.0.66 / 1.0.67，不要 zip 和 NSIS 混用。解压到本地 NTFS 磁盘后运行 `AngelaBox.exe`，第一次点「安装」并允许管理员权限。官方 SFW 是安装器提权后装到 Program Files；便携 zip 会在同一提权里给守护进程加 `--allow-unsafe-installation-directory-permissions`，并先解除下载标记。安装器 `AngelaBox-windows-*.exe` 仍是自签，若 Chrome 拦截请 Ctrl+J → 保留危险文件 → 仍然保留。不要把无版本号的 `AngelaBox-windows-amd64.zip` 当成图形界面，那个是命令行。
• 服务未安装时不再空等连接；连接页显示 AngelaBox 与「连接中」。点安装失败会显示中文原因，不再弹出整段 PowerShell。
• 检查更新只看本仓库 AngelaBox 发行，不再误报官方 sing-box 1.15。托盘、窗口、服务提示一律显示 AngelaBox。
• 默认脚本 overlay-revision 14：叶节点 `tcp_keep_alive` 为时长 `60s`，并带 负载均衡 / 故障转移。脚本启动失败时绑定保留，不再被规范化强行关掉。
• 链式与前置脚本不再互斥：先跑入口订阅脚本，再按入口→落地组链。落地配置上的脚本不会套到当前配置。
• Windows TUN 强制 `auto_detect_interface`、严格路由，并把 `AngelaBox.exe` / `sing-box-daemon.exe` 排除出隧道，避免回环。出站仍走代理节点，只是连节点时用真实网卡，不会把代理流量泄到直连。
• 内核跟进官方 sing-box **v1.15.0-alpha.6**。Go 1.25.5，fork tag `v1.15.0-chain.3`，commit `d7639f61`。设置 → 核心显示 `1.15.0-chain.3（官方 1.15.0-alpha.6）`。
• Android 启动器图标为透明底立方体。自动选择每 10 分钟测一次，空闲 4 小时才停测。忽略电池优化后息屏不再暂停整条隧道。
• 本版是 **测试内核**，不是稳定版。日常使用请留在 1.0.57。订阅 / 脚本 / 更新 / WebDAV 仅允许公网 HTTPS。构建过程完全开源；未购买商业代码签名证书。
