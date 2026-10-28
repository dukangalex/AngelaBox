• **1.0.136：**
1. **规则集改为逐个分诊（Rule-sets are now triaged one by one）：** 以前启动时只要有一个远程规则集下载失败，恢复流程就会把全部远程规则集一并跳过，能用的也跟着丢了。本版在启动前逐个核对：有可用缓存或确认能下载的保留，只有确认拉不到的才跳过并提示，其余分流不受影响。
   Previously, one failed remote rule-set download made the recovery drop every remote rule-set at start, usable ones included. Each set is now checked individually: sets with a usable cached copy or a reachable source are kept, only the ones proven unreachable are skipped with a notice, and the rest of the routing is unaffected.
2. **提示改为一闪而过，并全部记入日志（Notices now flash briefly and are all kept in the log）：** 仪表盘不再常驻堆叠提示卡片。信息类提示合并成简短摘要，在底部短暂显示后自动消失；每条提示的全文（含内核原始报错）都会写入日志页新增的「应用提示记录」，可搜索、可导出，事后随时回查。启动失败等硬错误仍照常弹窗。
   The dashboard no longer keeps stacks of resident notice cards. Informational notices are merged into a short summary that flashes briefly at the bottom and dismisses itself; the full text of every notice (kernel error text included) is written to the new "App notice log" on the Log page, searchable and exportable for later review. Hard errors such as a failed start still use a dialog as before.
3. **修复 fakeip 相关的 DNS 报错（Fixed fakeip-related DNS errors）：** 旧配置转换后，fakeip 的 DNS 规则可能指向一个不存在的服务器标签，日志里反复出现「transport not found: dns-fakeip」、fakeip 解析失效。本版按实际生成的服务器标签接线，并把旧版本已经写坏的规则一并纠正。
   After the legacy config conversion, the fakeip DNS rule could point at a server tag that does not exist, logging "transport not found: dns-fakeip" repeatedly while fakeip resolution failed. The wiring now uses the tag actually generated, and stale rules written by older versions are corrected too.
4. **内核与上一版相同：** `v1.15.0-chain.10`（官方 v1.15.0-alpha.13）。可以直接覆盖升级。
   Same kernel as 1.0.135-beta, `v1.15.0-chain.10` (official v1.15.0-alpha.13). Installs over the previous build.

• **1.0.135：**
1. **修复规则集地址失效导致的冷启动失败（Fixed cold-start failures from dead rule-set addresses）：** 应用内置规则集（中国直连三件套、广告拦截）写死的下载地址指向上游仓库的旧位置，文件早已不在那里 — 需要重新下载的冷启动必然失败，此前能启动全靠旧缓存。本版改用当前有效的官方地址，四个文件均已逐个通过完整下载与内核解析校验。
   The built-in rule-sets (China-direct trio and ad blocking) pointed at a stale location in the upstream repositories where the files no longer exist, so any cold start that had to re-download one was guaranteed to fail; earlier starts only survived on cached copies. The addresses now use the current official location, and all four files were verified end-to-end (full download plus a parse by the pinned kernel).
2. **修复恢复流程「跳过规则集」后仍被注回（Recovery no longer re-injects the rule-sets it just skipped）：** 启动恢复删掉远程规则集之后，系统显式的广告拦截等注入点又会把集合原样注回，脚本回滚一步也丢了跳过状态 — 结果仍是「跳过之后仍然启动失败」。本版把全部注入点纳入阶梯抑制，恢复后的配置保证不再含被跳过的远程规则集，中国直连与广告拦截临时降级并给出提示。
   After the startup recovery dropped remote rule-sets, injection points such as ad blocking quietly added them back, and the script-rollback step lost the skip state entirely, so starts still failed after "skipping". All injection points now honour the recovery state, the recovered configuration provably contains none of the skipped sets, and the affected features degrade with a notice instead.
3. **两处启动健壮性修复（Two startup robustness fixes）：** 防 DNS 泄露在当前出口为空直连出站时，不再把 DNS 的 detour 强改过去（内核会因此拒启），改为跳过并提示；启动最终失败的对话框现在附上内核的原始报错（截断显示），方便定位问题。
   DNS leak protection no longer forces a DNS detour onto an empty direct outbound, which the kernel refuses at start; it skips with a notice instead. The final startup-failure dialog now appends the kernel's own error text (truncated) to make field reports diagnosable.
4. **内核与上一版相同：** `v1.15.0-chain.10`（官方 v1.15.0-alpha.13）。可以直接覆盖升级。
   Same kernel as 1.0.134-beta, `v1.15.0-chain.10` (official v1.15.0-alpha.13). Installs over the previous build.

• **1.0.134：**
1. **修复链式代理在大分组下被误拦停启（Chains with large groups no longer refused at start）：** 1.0.133 给链式展开加的防爆上限数值定得过小，入口与落地分组合计几十个节点的正常机场配置会被误判「叶子节点过多」而停止启动，回退配置也同样被拦。本版按内核实际展开方式（线性增长）重定了上限：大分组恢复正常，真正病态的递归配置仍会拦下。单分组成员上限同步放宽，大机场的「全节点」组不再误伤。
   The anti-blowup caps added in 1.0.133 were sized for toy configs and refused real airport profiles whose entry and landing groups hold dozens of nodes, and the fallback config hit the same cap. The bounds now follow the kernel's actual linear expansion: large groups start normally, while genuinely pathological recursive configs are still rejected. The per-group member limit was raised to match.
2. **修复防 DNS 泄露误选订阅自带的中国 DNS（DNS protection no longer hijacks the profile's China DNS）：** 订阅自带的中国 DNS（如阿里、腾讯 DoH）会被误当成「远程 DNS」抢成最终解析，还被改走代理出口，与中国直连冲突。本版按服务器地址识别中国 DNS 并将其排除在覆写之外：它保持直连、中国域名照常走它解析，最终解析只会落到真正的远程 DNS。
   A profile's own China DNS (Aliyun/Tencent DoH and peers) was misread as a foreign resolver, promoted to final and forced through the proxy, fighting China direct. China DNS servers are now recognised by address and excluded from the override: they stay direct, China domains keep resolving through them, and final only ever lands on a genuinely remote resolver.
3. **修复规则集下载失败时跳过仍然启动失败（Startup now survives an undownloadable rule-set）：** 本版注入的中国规则集若在冷启动时下载不下来，恢复流程每一级都会把它们重新注回去，出现「跳过之后仍然启动失败」。现在恢复可以真正跳过这些规则集，中国直连临时按内置域名/IP 名单工作并给出提示；同时修复了剥除规则集后残留的空规则可能把全部 DNS 查询导向中国镜像的问题。
   When a China rule-set injected by this app could not be downloaded at cold start, every recovery rung re-injected it, so skipping still ended in a failed start. Recovery can now genuinely drop those sets, with China direct falling back to the built-in domain/IP lists plus a notice; an emptied rule shell that could have steered all DNS to the China mirror is removed as well.
4. **内核与上一版相同：** `v1.15.0-chain.10`（官方 v1.15.0-alpha.13）。可以直接覆盖升级。
   Same kernel as 1.0.133-beta, `v1.15.0-chain.10` (official v1.15.0-alpha.13). Installs over the previous build.

• **1.0.133：**
1. **「系统显式」上线：系统级防泄漏设置现在真正说了算（System-explicit settings are now the top layer）：** 原「配置覆盖」升级为「系统显式」，在订阅配置和叠加脚本之后生效。以前只要配置绑了脚本，这一组开关就会整体失效；现在脚本先处理配置，这些开关在其后强制覆写同类设置，页面里每一项帮助都写明了这条优先级。
   The profile-override page is now "System-explicit" and applies after both the profile and any overlay script. Previously, binding a script silently disabled the whole switch set; the explicit layer now overrides same-kind settings the script wrote, and every help text on the page says so.
2. **中国直连拆成三项，并自带规则集与中国 DNS 解析（China direct split into three switches, with bundled rule-sets and China DNS）：** 原「中国直连」拆为中国 IP/域名直连、中国公共 DNS 直连、局域网直连三项，可分别开关。中国直连现在自带官方 geosite-cn / geoip-cn 规则集，并通过中国 DNS 服务器镜像解析国内域名，不再依赖订阅自己提供规则集才生效；老版本的「中国直连」设置会自动迁移到三项上。
   The old single China-direct switch is now three: China IP/domain direct, China public-DNS direct and LAN direct. China direct ships the official geosite-cn / geoip-cn rule-sets and mirrors China domains through a China DNS server, so it works even when the subscription provides no rule-sets; the old setting migrates onto the three new ones automatically.
3. **防 DNS 泄露改为强制覆写（DNS leak protection now enforces instead of only filling gaps）：** 以前只补空缺，配置写死的国外 DNS 会原样保留；现在会把最终 DNS 改为远程加密 DNS 并强制其走代理出口，同时封堵明文 DoT（853 端口）。习惯旧行为的话，设置里保留了一个版本「旧版仅填空模式」可切回。
   DNS protection used to fill only empty slots, leaving a profile's hardcoded foreign DNS in place. It now rewrites the final DNS to a remote encrypted resolver forced through the proxy egress, and blocks plaintext DoT on port 853. A one-release "legacy fill-only" fallback switch is available in settings.
4. **新增防 VPN 泄露与防隐私，防 WebRTC 补上 STUN 拦截（New VPN-leak and privacy protection; WebRTC blocking gains STUN）：** 防 VPN 泄露在最终出口为空或直连时强制走代理（链式代理已固定的出口优先，不会被它改掉）。防隐私开启后，通知不再显示配置名，并拦截局域网设备发现协议（与局域网直连同开时，发现拦截优先）。防 WebRTC 泄露新增按协议识别的 STUN 拦截，不再只靠端口。
   VPN-leak protection forces an empty or direct final egress through the proxy (a chain-pinned egress still wins). Privacy protection hides the profile name from notifications and blocks LAN discovery protocols, which take precedence over LAN direct when both are on. WebRTC blocking now also rejects STUN by protocol, not just by port.
5. **链式代理更稳，内核更新到 chain.10（Chains are more robust; kernel updated to chain.10）：** 出口选策略组时，组里保存的默认节点以前会被丢掉、回退到第一个成员，现在完整保留（内核修复）。删除被其它配置用作落地的配置时，会先弹窗列出受影响的绑定让你确认，删除后绑定标记为已失效而不是悄悄解除；入口失效时，曾成功生效过的绑定会在启动时明确报错停启，不再静默换路。
   When a chain exit is a policy group, its saved default member used to be dropped in favour of the first member; it is now preserved (kernel fix). Deleting a profile that other profiles use as a chain landing now asks first and lists the affected bindings, and deleted landings mark bindings invalidated instead of silently unbinding them. A chain whose entry disappears stops with a clear error if it had ever taken effect, instead of silently rerouting.
6. **界面与联动一致性优化（Interface and cross-page consistency pass）：** 配置列表现在带链式状态徽标（已绑链 / 被引用 / 落地已失效）；仪表盘的链路图会如实标出已失效的落地，不再画成完好链路；Clash 模式控件加了每个模式的生效说明；防 VPN 泄露会实时提示 IPv6 未捕获的风险；设置页统一为共享的开关行组件，多处过期帮助文案已按新语义改写。
   Profile cards now carry chain badges (bound / referenced / landing invalidated); the dashboard path diagram marks an invalidated landing instead of drawing a healthy chain; the Clash mode control explains each mode; VPN-leak protection warns live when IPv6 capture is off; settings rows share one component and stale help texts were rewritten to the new semantics.
7. **内核：** `v1.15.0-chain.10`（官方上游仍为 v1.15.0-alpha.13，本版为其最新版）。可以直接覆盖升级。
   Kernel `v1.15.0-chain.10` (upstream remains v1.15.0-alpha.13, its latest). Installs over the previous build.

• **1.0.132：**
1. **恢复备份不再串用密码，也不会悄悄连上备份里的服务器（A restored backup can no longer mix up your password, or silently connect to a server from the backup）：** 以前恢复一份备份，备份里的 WebDAV 地址会被配上你本机保留的密码一起使用；备份里带的远程控制服务器还会在下次启动时自动连上。现在恢复后 WebDAV 地址与账号只用你恢复前的那一份；备份里的远程控制服务器需要你在界面里手动重新选择，附带的密钥不会从备份带入。
   Restoring a backup used to pair the backup's WebDAV address with the password kept on this device, and a remote-control server stored in the backup connected automatically on the next launch. Now the WebDAV address and account always stay the ones from before the restore; a restored remote-control server must be picked again by hand, and secrets stored in the backup are not carried over.
2. **从备份恢复的远程订阅默认关闭自动更新（Remote profiles restored from a backup start with auto-update off）：** 恢复出来的远程订阅一律先关自动更新、更新间隔不低于 15 分钟，恢复结果里会列出恢复了哪些。备份里的叠加脚本与链式绑定默认不恢复，恢复界面可以勾选包含；脚本与配置的绑定关系始终不从备份恢复，需要在配置里手动绑定。
   Remote profiles created by a restore now start with auto-update off and an interval of at least 15 minutes, and the restore summary lists what was restored. Overlay scripts and chain bindings in a backup are skipped by default, with a checkbox on the restore screen to include them; script-to-profile bindings are never restored and are set by hand in the profile.
3. **带 Tailscale SSH 的配置会被点名，并默认移除（A profile that turns on Tailscale SSH is called out, and the switch is removed）：** 订阅配置若开启了 `ssh_server`，任何能登录你 tailnet 的人就能拿到这台手机的 shell。以前导入不会有任何提示；现在确认框会明确写出这一点，实际保存与启动的配置会移除该项。
   A subscription profile that enables `ssh_server` hands a shell on this phone to anyone who can log in to your tailnet. Imports used to say nothing about it; the confirmation dialog now names it, and the profile that gets saved and started has the switch removed.
4. **导入确认写明内容，按确认时的内容导入（Import confirmations say what is being imported, and import exactly that）：** 扫码、文件和从其他应用打开导入时，确认框会写明配置类型、远程订阅的完整地址和自动更新状态；以前扫码导入的本地配置点了就直接导入，现在同样先确认。从其他应用打开的配置按你确认时看到的内容导入，确认后内容被替换也不会生效。本地文件里引用了远程节点源或规则源时，会先把地址列出来让你确认，再去拉取。
   QR, file and open-from-another-app imports now show the profile type, the full remote URL and its auto-update state before anything is saved; QR imports of local profiles, which used to apply immediately, ask first too. A file opened from another app is imported as the exact content you confirmed, so a swap after the dialog cannot take effect, and remote provider URLs embedded in a local file are listed for confirmation before any fetch happens.
5. **畸形文件不会再让应用崩溃或长时间卡死（Malformed files can no longer crash the app or hang it）：** 嵌套过深的 YAML、构造过的规则集文件、迭代次数离谱的加密备份、无限累积的二维码分片流，现在都会被上限拦下并给出错误，而不是崩溃或把 CPU、内存耗尽。叠加脚本的 5 秒超时现在能真正切断，用内置函数绕过超时的写法不再管用；一轮订阅更新里失败的节点源最多尝试 8 个，不会拖满整轮时间。
   Deeply nested YAML, crafted rule-set files, encrypted backups with absurd iteration counts and QR fragment streams that accumulate forever are now stopped by hard limits with an error, instead of crashing or eating CPU and memory. The 5-second limit for overlay scripts is now enforced by a watchdog that built-in-function tricks cannot slip past, and one update round tries at most 8 failing providers instead of burning the whole round on them.
6. **开机自启最多因崩溃报告跳过一次（Boot auto-start is skipped at most once per crash report）：** 以前只要有一份没看过的崩溃报告，每次开机都会跳过自启，服务可能一直不开。现在同一份报告只跳过一次，下次开机正常启动。
   Previously one unread crash report made every boot skip auto-start, so the service might never come up. A report now suppresses exactly one boot; the next boot starts normally.
7. **静默安装按你的设置执行（Silent install follows the method you configured）：** 以前装有 LSPosed 时会被强制改用 root 安装，无视你在设置里选的方式。现在始终按设置执行，选了 root 才用 root。
   With LSPosed present the installer used to force the root method regardless of your setting. The configured method now always wins, and root is used only when you chose it.
8. **内核与上一版相同：** `v1.15.0-chain.9`（官方 v1.15.0-alpha.13）。可以直接覆盖升级。
   Same kernel as 1.0.131-beta, `v1.15.0-chain.9` (official v1.15.0-alpha.13). Installs over the previous build.

• **1.0.131：**
1. **修一个装不上的问题：安装包现在带全套签名（Installability fix — the APK now carries every signature scheme）：** 之前所有版本的安装包只有 v2 签名。Android 6 以及部分定制 ROM 的安装器只认 JAR 签名，遇到只有 v2 的包会报「该安装包未包含任何证书」而拒绝安装。现在同时带 v1 / v2 / v3，任何安装器都能装。
   Every earlier build was signed with the v2 scheme only. Android 6, and some OEM installers that validate JAR signatures, reject such a package with INSTALL_PARSE_FAILED_NO_CERTIFICATES, which the user sees as "该安装包未包含任何证书". This build carries v1, v2 and v3, so any installer accepts it.
2. **发版门禁加了一道检查：** 以后但凡打出的包不是 v1+v2+v3 全套签名，发版工作流会直接拒绝发布，不会再无声上线。
   The release workflow now refuses to publish a package unless apksigner reports all three schemes as verified.
3. **内核与上一版相同：** `v1.15.0-chain.9`（官方 v1.15.0-alpha.13）。除签名方式外没有功能改动，已装 1.0.130-beta 的可以直接覆盖升级。
   Same kernel as 1.0.130-beta, `v1.15.0-chain.9` (official v1.15.0-alpha.13). Nothing changed but the signing, so 1.0.130-beta installs over it.

• **1.0.130：**
1. **内核跟进官方上游 v1.15.0-alpha.13（Kernel now tracks upstream v1.15.0-alpha.13）：** 从 alpha.11 跨到 alpha.13，共 209 个上游提交。核心变化：TUN 栈重写并去掉 gVisor 依赖、HTTP 代理协议重写、新增 MASQUE 与 tailcat、anytls 迁到独立库、按目的地做背压。用户可感知的部分是长连接更稳、切换网络后内存占用下降。
   Two hundred and nine upstream commits since alpha.11: a rewritten TUN stack with gVisor gone, a rewritten HTTP proxy protocol, MASQUE and tailcat, anytls moved into its own library, and per-destination backpressure. Expect steadier long-lived connections and a smaller resident size after switching networks.
2. **链式代理的三个行为原样保留（The chain behaviour is unchanged）：** 上游这版自己实现了「解析真实出站链」（`resolveOutbound` / `OutboundChain`），与本仓库的链式编译契合，所以这一层改用上游实现；本仓库独有的部分（`protocol/chain/`、`option/chain_compile.go`）保持不动，UDP 仍然明确失败。
   Upstream now resolves the real outbound chain itself, which is what the chain compiler relied on, so that layer is upstream's code now. The fork-only pieces stay as they were, and UDP is still refused loudly.
3. **urltest 的三个自有修复重新落到上游代码上（The three urltest fixes were re-applied）：** 选中节点失败时按延迟换下一个（单连接最多试 3 个）、记住成功的节点与失败清空、未使用的地区组不在启动和网络切换时把整组节点叫醒。
   First-available dialing (at most three members per connection, the sticky winner first), failure memory, and no probe pass for an unused region group on startup or on an interface change.
4. **签名与包不变：** 同一把证书（`CN=ChainBox`），只带 arm64，内核 pin 为 `v1.15.0-chain.9`。
   Same signing certificate (`CN=ChainBox`), arm64 only, kernel pinned to `v1.15.0-chain.9`.

• **1.0.129：**
1. **链式代理只带 TCP，UDP 明确失败不再偷跑 (Chained profiles carry TCP only — UDP fails loudly instead of leaking):** 内核的 `detour` 只作用于 TCP 拨号，落地节点自己建的 UDP 连接不经过链路，源地址既不是入口也不是落地。以前链式开启时这类流量照常发出，出口地址与界面上显示的落地节点不一致。现在链式出站拒绝 UDP，连接直接失败，不会再有包从你没选的地址出去；QUIC（UDP 443）仍由默认开关复位，日常浏览无感。
   The kernel's `detour` only routes the TCP dial, so a UDP socket created by the landing hop never passes through the chain and its source address was neither the entry nor the landing node. That traffic used to go out anyway, reporting an exit that did not match the landing node shown in the UI. The chain outbound now refuses UDP, so the connection fails visibly and no packet leaves from an address you did not pick; QUIC (UDP 443) is still reset by the default toggle, so ordinary browsing is unaffected.
2. **导入的配置不再把手机变成局域网里的开放代理 (An imported profile can no longer turn the phone into an open proxy for the LAN):** 订阅里的 sing-box 配置若写了 `"listen": "0.0.0.0"`，内核会照做，同一 Wi-Fi 下任何人都能拿你的手机当出口，界面上看不出任何迹象。现在非回环入站监听在导入和启动时一律改回 `127.0.0.1`（unix socket 路径不动），并在提示里点名改了哪一条。
   If a sing-box profile from a subscription says `"listen": "0.0.0.0"`, the kernel obeys and anyone on the same Wi-Fi can use the phone as an exit, with nothing in the UI to show for it. Non-loopback inbound listeners are now rebound to `127.0.0.1` at import and start (unix socket paths untouched) and the notice names each rewritten entry.
3. **规则集地址钉到发布版本 (Rule-set URLs are pinned to a release tag):** 规则集决定一个域名走代理还是直连，所以它的地址是供应链输入。应用自己写出的规则集地址现在钉在 `sing-geosite@20261024005255` 与 `sing-geoip@20261012`，含默认脚本、广告拦截与 Clash 转换；订阅自带的第三方规则集仍原样保留。任何把可变分支改回来的改动都会被 CI 拦住。
   A rule set decides whether a domain is proxied or dialled directly, which makes its URL a supply-chain input. The URLs the app writes itself are pinned to `sing-geosite@20261024005255` and `sing-geoip@20261012`, including the default overlay script, ad blocking and Clash conversion; third-party sets arriving with a subscription are left exactly as they are, and CI now fails if a mutable ref comes back.
4. **DNS 兜底地址也过安全策略 / 安装包只带 arm64 / 导入入口收窄：** 隧道开着时 DNS 兜底查到的地址不再跳过安全检查（假 IP 仍放行）；安装包只含 arm64，32 位设备不再“装上就闪退”，包体小约 3 MB；导入入口只保留规则集 `.bpf` 匹配，任何应用不能再借 `content://` 把文本推进导入流程。
   The DNS fallback answer no longer skips the address policy while the tunnel is up (fake-IP still accepted); the APK is arm64-only, so 32-bit devices are reported incompatible instead of installing and crashing, and it is about 3 MB smaller; the import intent filter keeps only the `.bpf` rule-set patterns, so no app can push text into the import flow through its own `content://` provider.
5. **本版相对 1.0.114-stable 另外包含 1.0.115–1.0.128 的全部改动：** 规则集地址优先保留你自己的、更新提示不再刷屏、备份可加口令加密、恢复后首次启动更稳、坏件检疫只摘坏的那一个、链式降级必出声、规则集冷启动不再被误跳过、仪表盘显示真实状态、ECH 节点与脚本共存等。逐条说明见各 beta 的更新日志。
   This release also carries everything from 1.0.115 through 1.0.128: your own rule-set addresses are preserved, update notices no longer flood the dashboard, backups can be password-encrypted, the first start after a restore is more reliable, a broken component is quarantined instead of taking the profile down, chain downgrades are always announced, rule sets are no longer wrongly skipped at cold start, the dashboard tells the truth, and ECH nodes work alongside overlay scripts. Per-release detail is in each beta's changelog.

• **1.0.128：**
1. **链式代理只带 TCP，UDP 明确失败不再偷跑 (Chained profiles carry TCP only — UDP now fails loudly instead of leaking):** 内核的 `detour` 只作用于 TCP 拨号，落地节点自己建的 UDP 连接不会经过链路，源地址既不是入口也不是落地。以前链式开启时这类流量会照常发出去，出口地址与界面上显示的落地节点不一致。现在链式 outbound 拒绝 UDP，连接直接失败，不会再有任何一条包从你没选的地址出去。默认的「禁用 QUIC」仍会复位 UDP 443，日常浏览无感；游戏、语音、自建 WireGuard 这类 UDP 需要关掉链式才能用。
   The kernel's `detour` only routes the TCP dial; a UDP socket created by the landing hop never passes through the chain, so its source address was neither the entry nor the landing node. With a chain bound, that traffic used to go out anyway, reporting an exit address that did not match the landing node shown in the UI. The chain outbound now refuses UDP, so the connection fails visibly and no packet can leave from an address you did not pick. QUIC (UDP 443) is still reset by the default overlay, so ordinary browsing is unaffected; UDP traffic such as games, voice calls and self-hosted WireGuard needs the chain turned off.
2. **导入的配置不再把手机变成局域网里的开放代理 (An imported profile can no longer turn the phone into an open proxy for the LAN):** 订阅里的 sing-box 配置如果写了 `"listen": "0.0.0.0"`，内核会照做，同一个 Wi-Fi 下的任何人都能拿你的手机当出口，而界面上看不出任何迹象。现在非回环的入站监听在导入和启动时一律改回 `127.0.0.1`（unix socket 路径不动），并在提示里点名改了哪一条。
   If a sing-box profile from a subscription contains `"listen": "0.0.0.0"`, the kernel obeys it and anyone on the same Wi-Fi can use the phone as an exit — with nothing in the UI to show for it. Non-loopback inbound listeners are now rebound to `127.0.0.1` at import and start (unix socket paths are left alone), and the notice names each entry that was rewritten.
3. **规则集地址钉到发布版本，不再跟着可变分支漂 (Rule-set URLs are pinned to a release tag instead of a mutable branch):** 规则集决定一个域名走代理还是直连，所以它的地址是供应链输入。应用自己写出的规则集地址现在钉在 `sing-geosite@20261024005255` 与 `sing-geoip@20261012`，包括默认脚本、广告拦截和 Clash 转换；订阅自带的第三方规则集仍原样保留。任何能把可变分支改回 `@rule-set` 的改动都会被 CI 拦住。
   A rule set decides whether a domain is proxied or dialled directly, which makes its URL a supply-chain input. The URLs the app writes itself are now pinned to `sing-geosite@20261024005255` and `sing-geoip@20261012`, including the default overlay script, ad blocking and Clash conversion; third-party rule sets that arrive with a subscription are still left exactly as they are. CI now fails if a mutable `@rule-set` ref comes back.
4. **DNS 兜底查到的地址也要过安全策略 (The DNS fallback answer now goes through the same address policy):** 隧道开着、绕过隧道的 DNS 又被拦时，应用最后会退回系统解析；这一步以前拿到的地址没有再检查，等于给「返回的地址必须全部合法」这条规则开了个后门。现在假 IP 照常放行（隧道会按名字还原目标），其余地址仍然必须通过同一套检查。
   With the tunnel up and out-of-tunnel DNS blocked, the app falls back to system resolution; the addresses from that last step used to skip the check entirely, which punched a hole in the "every returned address is allowed" rule. Fake-IP answers are still accepted (the tunnel maps them back by name), and everything else still has to pass the same check.
5. **安装包只带 arm64，32 位设备不再装上就闪退 (The APK ships arm64 only, so 32-bit devices no longer install and then crash):** 以前安装包声明支持四种架构，实际只有 arm64 带内核库，32 位设备安装后一启动就在加载内核时崩掉。现在明确只发 arm64，不支持的设备会被直接判定为不兼容，安装包也小了约 3 MB。
   The APK used to declare four architectures while only arm64 carried the kernel library, so a 32-bit device installed it and crashed on kernel load at first start. The build is now arm64-only; unsupported devices are reported as incompatible instead, and the APK is about 3 MB smaller.
6. **导入入口收窄 (The import intent filter is narrower):** 以前本应用会出现在「用哪个应用打开这个文本文件」的候选里，任何应用都能把自己的一份文本通过 `content://` 推进导入流程。现在只保留规则集 `.bpf` 文件那几条路径匹配，分享菜单和「从文件导入」不受影响。
   The app used to appear as a handler for every text file, and any app could push arbitrary text into the import flow through its own `content://` provider. Only the `.bpf` rule-set path patterns remain; the share menu and "import from file" are unaffected.

• **1.0.127：**
1. **你自带的规则集地址现在原样采用 (Rule sets you bring keep their own addresses):** 以前 sing-box 配置里自带的远程规则集，不管原地址能不能用，都会因为文件名长相被换成官方地址，个别 IP 规则集还被误换成域名集。现在原地址优先逐字保留；只有官方地址能证明确实不存在时才替换并逐个点名，IP 集与域名集绝不互相替换。原地址不是公共 HTTPS 的，会在提示里写明未采用的原因，不再含糊带过。
   Remote rule sets in sing-box profiles now keep their own URLs instead of being rewritten to official addresses just because of how the file is named — and IP rule sets are never swapped for domain sets or the other way round. A swap happens only when the referenced file provably does not exist, and every swap is named in the notice; when an address is not adopted because it is not a public HTTPS address, the notice says so.
2. **更新提示不再刷屏 (Update notices no longer flood the dashboard):** 以前一份配置的更新提示能堆满整个仪表盘。现在收成一条横幅：标题与内容对齐、按类别分组、最多显示 3 条，其余折叠成一项，可以手动关掉（下次更新有新提示时再出现）；网络体检里同一条订阅提示也不再重复出现两遍。
   Per-profile update notices used to stack up and fill the dashboard. They now live in a single banner with an accurate title and grouped lines — at most three lines shown, the rest folded into a summary — and the banner can be dismissed until the next update brings new notices. The Network Checkup no longer shows the same subscription notice twice.
3. **备份导出可以加口令加密 (Backup export can be password-encrypted):** 导出备份时现在可以设一个口令，整包加密保存，节点密码和 UUID 不再明文躺在备份文件里；恢复加密备份需要输对口令，口令不对或文件损坏会干净地报错，绝不恢复一半。不设口令时，导出前会明确提醒你备份内含节点明文凭据。以前的未加密备份照常可以恢复。
   When exporting a backup you can now set a password and have the whole package encrypted, so node passwords and UUIDs no longer sit in plaintext inside the backup file. Restoring an encrypted backup asks for the password; a wrong password or a damaged file fails cleanly instead of restoring halfway. Exporting without a password now warns you first that the backup contains plaintext node credentials. Older unencrypted backups still restore as before.
4. **恢复备份后第一次启动更稳 (First start after a restore is more reliable):** 恢复备份一完成就立即预热规则集缓存，恢复出来的配置第一次启动不用再等规则集现下载，不会因为下载慢被误跳过。
   Rule-set caches are now warmed up right when a backup restore completes, so a restored profile's first start no longer waits on fresh rule-set downloads or risks having them skipped for being slow.

• **1.0.126：**
1. **你自带的分流名单现在会被真正保留 (Your own rule lists are now actually kept):** 从 Clash 订阅或配置导入时，你自备的规则名单（rule-providers）以前会被整体换成官方名单，名单内容和地址悄悄失效，长尾国内域名还会误走代理。现在名单内容会被真正转换进配置随你一起生效；万一某个名单实在取不到，才会用官方名单替代，并逐个点名告诉你替换了哪一个、跳过了哪一个，绝不静默处理。
   When importing a Clash subscription or profile, your own rule-provider lists used to be replaced wholesale with the official sets — your list contents and URLs were silently dropped, and long-tail China domains could end up routed through the proxy. Your lists are now converted into the profile and actually take effect; only when a list truly cannot be fetched is an official set substituted, and the import notice names each substituted or skipped list instead of staying silent.
2. **规则集冷启动不再被误跳过，失败会说清原因 (Rule sets are no longer wrongly skipped at cold start, and failures explain themselves):** 以前冷启动时远程规则集下载稍慢，整批规则集就可能被跳过、分流不准，事后还会在一段时间内次次跳过。现在所有导入方式都会提前把规则集缓存到本地；每次冷启动都先完整尝试加载，实在超时会放宽一次再试；真失败时横幅会写明实际原因（比如只是启动超时，并不证明是规则集的问题），不再只有一个含糊的「已跳过」。
   At cold start, a slow remote rule-set download could get the whole batch skipped, skewing routing — and the skip could then repeat for a while. Every import path now warms a local rule-set cache ahead of time; each fresh start first tries the full profile, retries once with a longer budget on a timeout, and when something does fail, the banner states the actual reason (for example, that a start timeout alone does not prove the rule sets were at fault) instead of a vague "skipped".
3. **仪表盘显示更真实 (The dashboard tells the truth):** 网络体检结果现在标注属于哪个配置，切换配置后上一份的体检结果自动清掉，不再串账；服务状态按真实状态显示，内核异常退出后不会还写着「运行中」；节点名字再长也能完整显示（自动缩字、放不下换两行），不再截断成半截。
   Network Checkup results now say which profile they belong to, and switching profiles clears the previous profile's results instead of mixing them up. The service status reflects the real state — it no longer claims "Running" after the core has died. Node names are shown in full no matter how long they are (the font shrinks, then wraps to two lines) instead of being cut off mid-name.
4. **自家导入的配置启动不再弹内核弃用警告 (Profiles the app converts no longer trigger a kernel deprecation warning at start):** 本应用导入转换出的配置以前每次启动都会弹一个内核弃用警告框，要手动点掉。现在已补齐缺的缺省项，启动干净不再弹。
   Profiles converted by the app itself used to raise a kernel deprecation dialog on every start. The missing default is now filled in, and startup is clean.
5. **分组转换的语义差异会提前告知 (Group conversion differences are disclosed up front):** Clash 的 fallback 分组（按顺序优先）和本应用转换成的 urltest 分组（选最快）语义不同，以前静默近似、用户无从知晓。现在只要发生这种转换，导入提示会把差异说明白。
   A Clash fallback group (strict priority order) converts to a urltest group (fastest wins); that semantic difference used to be applied silently. The import notice now discloses it whenever this conversion happens.

• **1.0.125：**
1. **一个坏件不再拖垮整份配置 (One bad component no longer takes the whole profile down):** 启动时若配置里某个部件有问题（某条出站、某个 DNS、某条分流规则或某个规则集），现在会逐个检疫，只把真正坏的那个摘掉，其余内容原样保留、照常启动，并明确告诉你摘了什么。所有办法都用尽才降到最小可用形态，不再出现「配置明明是好的却怎么都启不动」。
   At startup, if one component of a profile is broken (an outbound, a DNS server, a route rule or a rule set), the app now quarantines components one by one, removes only the one that is actually broken, keeps everything else exactly as it was, and tells you what was removed. The minimal fallback shape is the last resort — a valid profile no longer fails to start because of a single bad part.
2. **导入和订阅更新不再误拒 (Import and subscription updates no longer refuse valid profiles):** 导入与更新时的校验已和启动时的修复能力对齐：启动能修好的配置，导入时不会再被拒之门外；确实有问题时也会把原因说清楚，不再只给你一个失败计数。
   Validation at import and subscription-update time is now aligned with what startup can repair: a profile that startup could fix is no longer rejected at the door, and when something really is wrong you get a readable reason instead of a bare failure count.
3. **链式降级必出声，绝不静默直连 (Chain downgrades are always announced, never a silent direct connection):** 链式代理实在保不住需要降级时，会用强提示把情况说明白；任何情况下都不会不声不响把你改成直连。
   If a chain cannot be preserved and has to be downgraded, you get a prominent notice explaining exactly that. Your traffic is never silently switched to a direct connection.

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
