更新说明
• 应用内更新改为直接读取安装包 v2 签名块核对发行证书。三星 One UI 上系统 PackageManager 对未安装的 APK 经常读不到证书，1.0.56 因此误报「APK has no signing certificate」。校验和通过且系统安装器能装的包，现在可以应用内安装。
• 1.0.56 无法用应用内更新升到本版（同一缺陷）。请点「查看发布」或从 GitHub Releases 下载 AngelaBox-android.apk 覆盖安装。装好 1.0.57 后，以后的应用内更新即可使用。
• 配置兼容层会静默去掉 TUN 的 `stack` 字段，为官方 sing-box 1.15 新 TCP/IP 栈做准备；1.14.0 内核仍按默认 mixed 工作，仪表不会因此显示「已修正」。
• 稳定版内核仍钉官方 **sing-box 1.14.0**（Go 1.25.5，commit `03ad0a1`）。不跟 1.14.1（Go 1.26.8 与 oomprofile 不兼容）。1.15.0-alpha.5 测试内核在独立分支开发，不会混进本版。
• 发行证书仍为 CN=ChainBox（SHA-256 `e7041217…`），未轮换。订阅 / 脚本 / 更新 / WebDAV 仅允许公网 HTTPS。
