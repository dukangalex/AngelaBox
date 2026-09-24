# 可选覆写脚本

这些不是应用内「默认脚本」。应用里导入：**工具 → 脚本 → + → 导入机场覆写**。也可以用代码或文件导入。与默认脚本不要同时绑在同一配置上。链式开启时前置订阅的脚本仍然会跑（先脚本，再组链）。

内置默认脚本是 `app/src/main/assets/scripts/airport-region.js`。它按 sing-box JSON 写地区组、自动选择、负载均衡、故障转移和国内直连。

## 机场覆写（airport-tun.js）

应用内名称是「机场覆写」。源文件同时在 `app/src/main/assets/scripts/airport-tun.js` 和本目录，两份必须相同。

从机场覆写改到 sing-box，链式中转整段不搬。保留：地区分组、自动选择 / 负载均衡 / 故障转移、服务分组、强制 TUN、本机 mixed（`127.0.0.1:17890`）、国内直连、广告、DNS 劫持。叶节点去掉 `detour` / `dialer-proxy`。

国外 A/AAAA 用 fake-ip。DNS 用直连 UDP（国内）和节点上的 TCP（其余），不用 DoH，不拒绝 HTTPS/SVCB。规则集走 HTTPS `.srs`，证书校验保持开启。

sing-box 没有 Clash 的 `load-balance` outbound，负载均衡组用 `urltest` 近似。

## service-groups.js

功能组含「💬 Claude.ai」。测速与规则集只用 HTTPS；没有 `geoip-private` / `geoip-telegram`（官方规则集不存在，私有网用 `ip_is_private`，Telegram 用 `geosite-telegram`）。
