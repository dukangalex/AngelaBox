更新说明（测试版）
• **Windows 图形客户端：** 下载 `AngelaBox-windows-*.exe` 双击安装。不要下 zip（zip 是命令行 `sing-box.exe`）。1.0.62-beta 会闪退，不要用。
• 内核跟进官方 sing-box **v1.15.0-alpha.6**（Fixes and improvements，无新协议类型）。本仓库发版 Go 1.25.5，fork tag `v1.15.0-chain.3`，commit `d7639f61`。设置 → 核心显示 `1.15.0-chain.3（官方 1.15.0-alpha.6）`。上游修补包括：Windows 进程归属不再扫描 TCP 表、go 栈内存、自动重定向 DNS 劫持、WireGuard 域名握手、libbox 命令客户端取消。
• Android 启动器图标改为透明底立方体，无白底方块。
• **公开说明（不改写 git 历史）：** 仓库早期历史含 2020 年 SagerNet 调试钥匙（CN 猫羽 世界，SHA-256 `32250a4b…`）与上游 Firebase 客户端配置（包名 `io.nekohasekai.sfa`，项目 `sing-b0x`）。二者都不是当前发行证书 CN=ChainBox（SHA-256 `e7041217…`）。GitHub Support Ticket 4763595 指出这两条提交仍是现存 tag 的祖先，直链会保持 200。改写 1.0.x 会打断已安装用户的覆盖更新，因此保持历史、不轮换发行证书。请只从本仓库安装 `AngelaBox-android.apk`，不要从 `goodmen001/AngelaBox` 安装。详见 docs/SECURITY.md。
• 默认覆写脚本仍为 overlay-revision 11。「组」页仍显示当前节点与延迟。
• 本版是 **测试内核**，不是稳定版。日常使用请留在 1.0.57。要回稳定版请到 GitHub 下载 1.0.57 覆盖安装（签名相同）。订阅 / 脚本 / 更新 / WebDAV 仅允许公网 HTTPS。
