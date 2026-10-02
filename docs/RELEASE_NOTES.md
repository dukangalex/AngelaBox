• **1.0.91：** Clash 和 v2rayN / v2rayNG 里内核已经支持的节点会保留下来跑，不再因为旁边有一条不支持的节点把整份配置丢掉。补上 Hysteria 链接、Naive、SSH、ShadowTLS、Snell，以及端口跳跃、混淆和插件参数。SSR 内核已经移除，会单独跳过。sing-box 订阅不用转。内核仍是 `v1.15.0-chain.4`。
• **1.0.90：** Clash 订阅里的 AnyTLS 会转成 sing-box 节点，不再整份当成不支持。认不出的协议会写出名字。sing-box 订阅不用转。内核仍是 `v1.15.0-chain.4`。
• **1.0.89：** Steam 国内站直连。Crypto 改用 PayPal、Binance、OKX、Bybit、Huobi 和加密货币官方规则集，HTX 域名仍单独直达该组。没有拒绝 HTTPS/SVCB 查询，避免 YouTube 被挤到 TCP。内核仍是 `v1.15.0-chain.4`。
• **1.0.88：** 开着代理时导入订阅不再因为解析不到主机被拒绝。Meta 组补上 Facebook、Instagram、WhatsApp、Threads、Messenger。没有官方的 Muse 规则集。内核仍是 `v1.15.0-chain.4`。
