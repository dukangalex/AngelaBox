# 可选覆写脚本

应用不内置机场覆写。以前导入并留在本机的「机场覆写」，打开脚本列表时会删掉。HiClash 适配版单独提供，用 **工具 → 脚本 → + → 通过文件导入**。不要和默认脚本绑在同一配置上。链式开启时前置订阅的脚本仍然会跑（先脚本，再组链）。

内置默认脚本是 `app/src/main/assets/scripts/airport-region.js`。它按 sing-box JSON 写地区组、自动选择和国内直连。没有 load-balance / fallback；延迟挑选用 urltest，手选用 selector。

## service-groups.js

功能组含「💬 Claude.ai」。测速与规则集只用 HTTPS；没有 `geoip-private` / `geoip-telegram`（官方规则集不存在，私有网用 `ip_is_private`，Telegram 用 `geosite-telegram`）。
