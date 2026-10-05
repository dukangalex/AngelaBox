• **1.0.95：** Clash 和节点链接里，当前内核支持的协议都能导入。补上 SSH、Naive、ShadowTLS、Snell 链接，以及 HTTPS 远程节点源。SSR、xhttp、MASQUE 仍会跳过。内核仍是 `v1.15.0-chain.5`。
• **1.0.94：** 切网或网卡掉了再回来，会重新接上，不再停在「没有可用网卡」。没被选中的地区自动选择不再一起测速。内核 `v1.15.0-chain.5`。
• **1.0.93：** 应用图标换成新的彩色标志。桌面、自适应图标、快捷设置和仓库头图一起换。内核仍是 `v1.15.0-chain.4`。
• **1.0.92：** 别的代理开着时，应用内更新先按当前网络访问 GitHub，不再去连一个假 IP。频道改为 https://t.me/AngelaNexus 。默认脚本在 AI 组前面增加独立的 ClaudeAI 组。内核仍是 `v1.15.0-chain.4`。
• **1.0.91：** Clash 和 v2rayN / v2rayNG 里内核已经支持的节点会保留下来跑，不再因为旁边有一条不支持的节点把整份配置丢掉。补上 Hysteria 链接、Naive、SSH、ShadowTLS、Snell，以及端口跳跃、混淆和插件参数。SSR 内核已经移除，会单独跳过。sing-box 订阅不用转。内核仍是 `v1.15.0-chain.4`。
• **1.0.90：** Clash 订阅里的 AnyTLS 会转成 sing-box 节点，不再整份当成不支持。认不出的协议会写出名字。sing-box 订阅不用转。内核仍是 `v1.15.0-chain.4`。
• **1.0.89：** Steam 国内站直连。Crypto 改用 PayPal、Binance、OKX、Bybit、Huobi 和加密货币官方规则集，HTX 域名仍单独直达该组。没有拒绝 HTTPS/SVCB 查询，避免 YouTube 被挤到 TCP。内核仍是 `v1.15.0-chain.4`。
• **1.0.88：** 开着代理时导入订阅不再因为解析不到主机被拒绝。Meta 组补上 Facebook、Instagram、WhatsApp、Threads、Messenger。没有官方的 Muse 规则集。内核仍是 `v1.15.0-chain.4`。
