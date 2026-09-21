• **1.0.71：** `geoip-cn` 等 IP 规则集「查看」会列出 CIDR（约 8000 条），不再停在「二进制规则集」。链接本身没问题，sing-box 也支持，是上一版解码器跳过了 IP 段。提供者页第一次解码后会记住条目数，再进是秒开。本版只发 Android，不再附带 Windows 命令行包。
• **发行完整性：** 发版工作流现在拒绝 `version_tag` 与 `version.properties` 的 `VERSION_NAME` 不一致的请求，防止 Release tag、安装包版本和更新提示脱节。本包版本为 **1.0.71-beta**。
• **1.0.70：** 规则提供者「查看」直接解码本地 `.srs`（不再去拉 404 的 JSON 源），条目数是域名列表而不是「1 个条目」。代理提供者「查看」打开配置文件（行号在左侧，JSON 高亮），节点名自带数字时不再出现 `1  1⭐ofo`。同步/刷新不再闪白；中文相对时间显示「x 分钟前」。本版只发 Android；Windows 搁置。
• **1.0.69：** 提供者「查看」改为编号条目列表（`+.abema-tv.com`），不再弹出规则集定义 JSON。页面分「代理提供者」和「规则提供者」，同步时顺带拉取 `.srs` 的 JSON 源以便显示真实条目数。仪表「配置」芯片跳到完整配置页（流量/到期/三点菜单/+添加配置），不占用底栏。远程订阅会保存 `subscription-userinfo`。本版只发 Android；Windows 搁置。
• **1.0.68：** 配置规范化在规则集 404 后会重启命令通道再试，不再弹出原始 EOF；失败时保留脚本绑定，只有脚本自己写出内核读不了的字段才回滚。仪表补上规则 / 全局 / 直连（自定义脚本如 MY脚本覆写也会注入）。默认脚本 overlay-revision 14 增加 ⚖️ 负载均衡、🛡️ 故障转移（urltest 近似）。配置页三个点可打开「提供者」管理规则集（查看 / 同步 / 上传）。Windows 启动画面在握手完成前一直留在窗口上，不再被 React 提前藏掉变成空屏。
• **Windows 图形客户端：** 推荐 `AngelaBox-v1.0.68-beta-windows-amd64.zip`。先卸载 1.0.66 / 1.0.67，不要 zip 和 NSIS 混用。解压到本地 NTFS 磁盘后运行 `AngelaBox.exe`，第一次点「安装」并允许管理员权限。官方 SFW 是安装器提权后装到 Program Files；便携 zip 会在同一提权里给守护进程加 `--allow-unsafe-installation-directory-permissions`，并先解除下载标记。安装器 `AngelaBox-windows-*.exe` 仍是自签，若 Chrome 拦截请 Ctrl+J → 保留危险文件 → 仍然保留。不要把无版本号的 `AngelaBox-windows-amd64.zip` 当成图形界面，那个是命令行。
• 服务未安装时不再空等连接；连接页显示 AngelaBox 与「连接中」。点安装失败会显示中文原因，不再弹出整段 PowerShell。
• 检查更新只看本仓库 AngelaBox 发行，不再误报官方 sing-box 1.15。托盘、窗口、服务提示一律显示 AngelaBox。
• 默认脚本 overlay-revision 14：叶节点 `tcp_keep_alive` 为时长 `60s`，并带 负载均衡 / 故障转移。脚本启动失败时绑定保留，不再被规范化强行关掉。
• 链式与前置脚本不再互斥：先跑入口订阅脚本，再按入口→落地组链。落地配置上的脚本不会套到当前配置。
• Windows TUN 强制 `auto_detect_interface`、严格路由，并把 `AngelaBox.exe` / `sing-box-daemon.exe` 排除出隧道，避免回环。出站仍走代理节点，只是连节点时用真实网卡，不会把代理流量泄到直连。
• 内核跟进官方 sing-box **v1.15.0-alpha.6**。Go 1.25.5，fork tag `v1.15.0-chain.3`，commit `d7639f61`。设置 → 核心显示 `1.15.0-chain.3（官方 1.15.0-alpha.6）`。
• Android 启动器图标为透明底立方体。自动选择每 10 分钟测一次，空闲 4 小时才停测。忽略电池优化后息屏不再暂停整条隧道。
• 本版是 **测试内核**，不是稳定版。日常使用请留在 1.0.57。订阅 / 脚本 / 更新 / WebDAV 仅允许公网 HTTPS。构建过程完全开源；未购买商业代码签名证书。
