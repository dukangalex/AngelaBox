• **1.0.120：**
1. **DNS 更稳、更快、更省电 (DNS more resilient, faster, and power-friendly):** 弱网或断网瞬间，用未过期的旧 DNS 答案先顶上、后台自动刷新，解析成功率更高；单次 DNS 查询超时从 10 秒缩到 5 秒，失败切换更快；DNS 缓存加大，命中更多、更省电省流量。
   On flaky or dropping networks, slightly stale DNS answers keep working while a background refresh runs; per-query DNS timeout drops from 10s to 5s so fallback is faster; a larger DNS cache means more hits and less battery/data use.

• **1.0.119：**
1. **开着其它 VPN 也能导入订阅 (Other VPNs no longer block subscription import):** 之前把别家 VPN 的 fake-ip DNS 答案当成禁止地址过滤掉了，导致开着其它 VPN 时订阅导入失败。现在隧道没开时不再拦截；拨号经由对方 VPN 可达。
   Previously, fake-ip DNS answers from other VPN apps were treated as forbidden addresses and filtered out, causing subscription imports to fail while another VPN was running. Now fake-ip filtering only applies when AngelaBox's own tunnel is up; with no tunnel, dial-out goes through the other VPN and works.

• **1.0.118：**
1. **Subscription import DNS fallback completed (订阅导入 DNS 兜底补上最后一块):** when system DNS is poisoned and UDP public DNS is blocked, the import retry chain now falls back to TCP/53 and tries once more. Previously this retry branch was unreachable because the ladder terminated early — the fix in this release makes it actually work.
   当系统 DNS 被污染且 UDP 公共 DNS 被拦截时，自动改用 TCP/53 再试一次；之前这个重试分支因 ladder 提前终止永远走不到，这次真正打通。

• **1.0.117：**
1. **订阅导入 DNS 兜底真正生效：** 修复 1.0.116 的一个 bug——没开 VPN 或连着其它 VPN 时，公共 DNS 查询被错误地跳过，部分订阅仍报"域名解析失败"。现在 DNS 污染重试链完整可用。
• **1.0.116：**
1. **DNS 防泄漏默认生效：** 以前这个开关近乎空转，现在原生层真正实现 FakeIP + 53 端口劫持，DNS 不再走明文。
2. **导入更透明：** 订阅/配置导入时被跳过的节点，会明确告诉你原因；订阅后台更新跳过节点时，首页会有提醒。
3. **导入更稳：** 修复一批"导入成功但连不上"的参数丢失（vless、wireguard、hysteria 等）；没开 VPN 导入订阅时，DNS 被污染会自动换公共 DNS 重试一次。
4. **报错说人话：** 订阅更新失败时，会明确告诉你是哪一步出问题、该怎么解决，不再让你去"检查网络"。
• **1.0.115：**
1. **默认脚本：** 不再写入已废弃的 `independent_cache`。以后内核删掉这个字段时，开着脚本仍能启动。
2. **国产机自启动：** 没有「查询全部应用」权限时，华为、小米、OPPO、vivo、魅族的自启动页面仍能打开；打不开再落到应用信息页。
