更新说明（测试版）
• 本版是 **测试内核**，不是稳定版。日常使用请留在 1.0.57。安装本包后，稳定通道不会再提示降回 1.0.57；要回稳定版请到 GitHub 下载 1.0.57 覆盖安装（签名相同）。
• 设置 → 核心 不再显示 unknown。发版编译前会给内核打上 `v1.15.0-chain.1`，界面显示 `1.15.0-chain.1（官方 1.15.0-alpha.5）`。1.0.57 / 1.0.58-beta 因浅克隆没有 git tag，`Libbox.version()` 为 unknown；请改用本版。销毁工作目录后「数据大小」为 0 kB 是正常的。
• 内核已对齐官方 sing-box **v1.15.0-alpha.5**（`go.mod` 1.25.5；本仓库发版 Go 1.25.5。官方该 tag 的 CI 为 1.26.8。fork tag `v1.15.0-chain.1` 已打在 commit `5e1593f0`，`chain-dev` 已快进到同一提交）。链式出站保留。不跟 1.14.1（仅为修补，无新协议）。
• 1.0.58-beta 已经编进同一份 1.15 内核，但当时未打 tag、界面未兜底，所以核心版本显示 unknown。本版补齐 tag、显示与刚需开关。
• 配置覆盖新增「按需连接」（默认开）：空闲时断开 WireGuard / Tailscale / OpenVPN / OpenConnect，需要时再连，省电。对应官方 1.15 `on_demand`。不是分流，脚本开着时应用仍会写入。
• 自动重定向（ROOT）：1.15 起支持热点/中继转发。
• 不做开关：TUN `stack`（内核已用自有栈，字段会静默去掉）、Tailcat（协议类型，不是总开关）、仅 Linux 的 `multi_queue`、cache `buffer_size`（内核默认 1MB 自动缓冲）。
• 发行证书仍为 CN=ChainBox（SHA-256 `e7041217…`），未轮换。订阅 / 脚本 / 更新 / WebDAV 仅允许公网 HTTPS。
