# 可选覆写脚本

`service-groups.js` 不是应用内「默认脚本」。导入路径：**工具 → 脚本 → + → 代码或文件**，再绑定到配置。与默认脚本不要同时绑在同一配置上。

功能组含「💬 Claude.ai」。测速与规则集只用 HTTPS；没有 `geoip-private` / `geoip-telegram`（官方规则集不存在，私有网用 `ip_is_private`，Telegram 用 `geosite-telegram`）。
