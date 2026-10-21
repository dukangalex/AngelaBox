• **1.0.124：**
1. **规则集下载不了不再跳过，改用本地缓存照常启动 (Rule sets fall back to the local cache instead of being skipped):** 以前远程规则集一时下载不下来，启动就会把它跳过，分流可能不准。现在订阅更新时会自动把规则集缓存在本地备好；启动时若下载不了，就直接用缓存替代，规则照常生效。只有实在没有缓存时才降级处理，并且会明确告知你。
   When a remote rule set could not be downloaded, startup used to skip it, which could make routing inaccurate. Subscription updates now keep a local cache of rule sets, and at startup a failed download is substituted with the cached copy so the rules keep working. Dropping a rule set is the last resort, and it is always reported.
2. **动态路径图看得清了 (The flow diagram is readable now):** 路径图里的节点和域名标签不再被截断成半截词，改为完整单行显示；每条流下面标注当前连接数和它占这一列的比例，流量走哪条路一眼看清。
   Node and domain labels in the traffic flow diagram are no longer cut off mid-word — they show in full on a single line — and each flow now shows its live connection count and its share of the column, so you can see at a glance where the traffic goes.

• **1.0.123：**
1. **开了脚本，带 ECH 的节点也能用了 (ECH nodes now work with overlay scripts on):** 默认脚本覆写开启时，脚本重建的 DNS 会把 ECH 配置查询的路由冲掉，整批 ECH 节点连不上。这一版在脚本跑完后按 sing-box 官方走法把 ECH 查询路由补回，默认脚本自身也会为 ECH 域名留出查询通道。
   With an overlay script enabled, the script-rebuilt DNS dropped the route for ECH config lookups, so every ECH node failed to connect. The ECH query route is now restored after scripts run, aligned with the official sing-box arrangement, and the bundled script itself keeps a query path open for ECH domains.
2. **网络体检不再误报「节点连不上」 (Network Checkup no longer cries wolf):** 体检的隧道探测改为两个目标、两轮探测，并用同一轮实测到的节点延迟交叉校验：偶发超时不再直接判「节点连不上」，而是如实显示「暂时测不准」并附上延迟毫秒数；延迟数字也会注明是刚刚测得还是上次测得。
   The tunnel probe now tries two targets over two rounds and cross-checks against the node latency measured in the same run: a single timeout no longer reports "node unreachable" — it honestly reports "uncertain" with the measured latency, and latency figures say whether they are fresh or from the last test.
3. **启动失败说人话，不再静默硬启 (Startup failures are explicit, never silent):** 配置加工中途出错时，不再拿近乎原始的订阅静默启动（可能链式和防护都没套上）；要么处理完成再启动，要么停下来把原因讲清。脚本把配置改坏也能被认出来并回退，不再出现「开着脚本跑不起来还查不出原因」。
   If profile processing fails partway, the app no longer silently starts on the near-raw profile (which could skip chaining and protections): it either finishes processing or stops with a clear reason. A script that breaks the config is now recognised and rolled back instead of leaving the service mysteriously unable to start.
4. **导入更宽容，坏节点不再拖垮整份订阅 (Import tolerates individual bad nodes):** 节点参数按内核能接受的形式归一（例如 flow 变体），实在不合规的单个节点只跳过它并明确告知，不再让整份订阅在启动时被内核拒绝；同名节点、规则集替换或删除也都会出声说明，不再静默发生。
   Node parameters are normalised to what the kernel accepts (for example flow variants); a single non-compliant node is skipped with a notice instead of making the kernel reject the whole profile at startup. Same-name nodes and rule-set replacements or removals are now always reported instead of happening silently.
5. **链式更可靠 (Chains are more reliable):** 跨配置组链时，落地配置自己绑定的脚本现在也会生效；分组嵌套里的落地节点不再自环；入口与落地双跳都是 TLS 时，每次启动按实际链路复检并给出提示。
   When chaining across profiles, the landing profile's own bound scripts now take effect; landing nodes nested inside groups no longer loop onto themselves; and the both-hops-TLS warning is rechecked against the actually compiled chain on every start.
6. **订阅更新更省心 (Subscription updates behave better):** 失效订阅自动退避，不再每轮硬拉；服务器限流（HTTP 429）按临时故障自动重试，不再误判为永久失效；更新落盘改为原子写入，更新中途断电也不会把配置写坏。
   Dead subscriptions back off automatically instead of being re-fetched every round; HTTP 429 rate limiting is treated as temporary and retried, not misjudged as permanent failure; updated profiles are written atomically, so a power loss mid-update cannot corrupt them.
7. **设置更尊重你的选择 (Settings respect your choices):** 升级迁移不再把你亲手关掉的开关重新打开；分应用代理的托管模式会明确提示它优先于手动名单，不再静默覆盖。
   Upgrade migrations no longer turn switches back on after you deliberately turned them off, and per-app managed mode now clearly states that it takes precedence over the manual list instead of silently overriding it.

• **1.0.122：**
1. **带 ECH 的节点连不上修复（第二轮） (ECH nodes fixed, round two):** 1.0.121 修掉查询被拦截的问题后，带 ECH 的订阅节点在部分网络下仍连不上。这一版把 ECH 配置查询改成与 sing-box 官方客户端完全一致的走法；旧版本存下来的订阅会在升级后自动修复，不用删了重导。
   After the 1.0.121 fix, subscriptions with ECH nodes still could not connect on some networks. This release aligns the ECH config lookup exactly with the official sing-box client, and profiles stored by older versions are repaired automatically on upgrade — no need to delete and re-import.

• **1.0.121：**
1. **带 ECH 的节点现在能正常连接 (Nodes with ECH now connect):** 修复 DNS 防泄漏把 ECH 配置查询也一并拦截的问题——节点自带 ECH 参数时，内核取 ECH 配置的查询被误拒，节点连不上。现在 ECH 查询走专用通道放行，其它域名的防泄漏行为不变。
   Fixed DNS leak protection also blocking ECH config lookups: for nodes that carry ECH parameters, the kernel's ECH config query was refused, so the node could never connect. ECH queries now go through their dedicated channel; leak protection for all other domains is unchanged.

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
