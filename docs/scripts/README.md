# 可选覆写脚本

这些不是应用内「默认脚本」。导入路径：**工具 → 脚本 → + → 代码或文件**，再绑定到配置。与默认脚本不要同时绑在同一配置上。链式开启时前置订阅的脚本仍然会跑（先脚本，再组链）。

内置默认脚本是 `app/src/main/assets/scripts/airport-region.js`。它按 sing-box JSON 写地区组、自动选择、负载均衡、故障转移和国内直连。HiClash / Mihomo 原脚本里的链式代理（dialer-proxy、链式中转）不搬进来。fake-ip 只用于非国内的 A/AAAA；ECH 查询由应用在脚本之后改到直连 DNS。

## airport-tun.js（机场 · TUN · 无链式）

从 Clash/Meta 机场订阅脚本改写。用途：地区分组、自动选择 / 负载均衡 / 故障转移、强制写入 TUN 与本机 mixed（`127.0.0.1:17890`）、国内直连、广告、DNS 劫持。叶节点去掉 `detour` / `dialer-proxy`。

Clash 的 `proxies`、`proxy-groups`、`RULE-SET` 字符串规则不能直接跑；本引擎只认官方 sing-box JSON。原脚本的 fake-ip / geodata.dat / `skip-cert-verify` 没有搬过来：本应用用 DNS 劫持，规则集走 HTTPS `.srs`，证书校验保持开启。

sing-box 没有 Clash 的 `load-balance` outbound，负载均衡组用 `urltest` 近似。

## service-groups.js

功能组含「💬 Claude.ai」。测速与规则集只用 HTTPS；没有 `geoip-private` / `geoip-telegram`（官方规则集不存在，私有网用 `ip_is_private`，Telegram 用 `geosite-telegram`）。
