• **1.0.98：** 带注释或行尾逗号的 sing-box 配置可以导入，和内核的读法一致。Android 上的 TUN 固定用 `dns_mode: hijack`，避免桌面配置把 DNS 留在系统解析器。系统开了指定主机的私人 DNS 时会提示关掉。自动重定向仍默认关，并写明当前内核上路由器地址的 UDP DNS 可能绕过劫持。
• **1.0.97：** 内核是官方 sing-box 1.15.0-alpha.10，型号 `v1.15.0-chain.7`。链式、失败换节点、空闲组不测速和网卡回退都还在。修正合并后测速记录的函数签名。系统 DNS 搜索域会交给内核。带查询参数的 Shadowsocks 链接会保留端口。sing-box 的 MASQUE 端点可以启动。Clash 里名叫 masque 的节点仍跳过。xhttp 仍然没有。1.0.96 没有发出。
• **1.0.95：** Clash 和节点链接里，当前内核支持的协议都能导入。补上 SSH、Naive、ShadowTLS、Snell 链接，以及 HTTPS 远程节点源。SSR、xhttp、MASQUE 仍会跳过。内核仍是 `v1.15.0-chain.5`。
• **1.0.94：** 切网或网卡掉了再回来，会重新接上，不再停在「没有可用网卡」。没被选中的地区自动选择不再一起测速。内核 `v1.15.0-chain.5`。
• **1.0.93：** 应用图标换成新的彩色标志。桌面、自适应图标、快捷设置和仓库头图一起换。内核仍是 `v1.15.0-chain.4`。
• **1.0.92：** 别的代理开着时，应用内更新先按当前网络访问 GitHub，不再去连一个假 IP。频道改为 https://t.me/AngelaNexus 。默认脚本在 AI 组前面增加独立的 ClaudeAI 组。内核仍是 `v1.15.0-chain.4`。
• **1.0.91：** Clash 和 v2rayN / v2rayNG 里内核已经支持的节点会保留下来跑，不再因为旁边有一条不支持的节点把整份配置丢掉。补上 Hysteria 链接、Naive、SSH、ShadowTLS、Snell，以及端口跳跃、混淆和插件参数。SSR 内核已经移除，会单独跳过。sing-box 订阅不用转。内核仍是 `v1.15.0-chain.4`。
• **1.0.90：** Clash 订阅里的 AnyTLS 会转成 sing-box 节点，不再整份当成不支持。认不出的协议会写出名字。sing-box 订阅不用转。内核仍是 `v1.15.0-chain.4`。
• **1.0.89：** Steam 国内站直连。Crypto 改用 PayPal、Binance、OKX、Bybit、Huobi 和加密货币官方规则集，HTX 域名仍单独直达该组。没有拒绝 HTTPS/SVCB 查询，避免 YouTube 被挤到 TCP。内核仍是 `v1.15.0-chain.4`。
• **1.0.88：** 开着代理时导入订阅不再因为解析不到主机被拒绝。Meta 组补上 Facebook、Instagram、WhatsApp、Threads、Messenger。没有官方的 Muse 规则集。内核仍是 `v1.15.0-chain.4`。
