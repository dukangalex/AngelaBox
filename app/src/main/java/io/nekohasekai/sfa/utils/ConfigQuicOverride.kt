package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.chain.ChainBindingCodec
import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.chain.ChainRuntimeCompiler
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject

class ChainApplyException(message: String) : IllegalStateException(message)

object ConfigQuicOverride {

    suspend fun apply(
        content: String,
        skipScripts: Boolean = false,
        replaceRuleSetNeedles: Collection<String> = emptyList(),
        dropRuleSetNeedles: Collection<String> = emptyList(),
        dropAllRemoteRuleSets: Boolean = false,
        // Per-set triage (v1.0.136): exact tags the start triage proved
        // undownloadable for this run. Dropped at the rule-set stage
        // below and suppressed in the segment, exactly like a ladder
        // drop — but only these sets; reachable ones stay.
        dropRuleSetTags: Set<String> = emptySet(),
        // Replace undownloadable remote rule sets with their cached local
        // copies (normalization substitute) instead of dropping them.
        substituteCachedRuleSets: Boolean = false,
        stripEch: Boolean = false,
        // The caller (BoxService) knows which profile this start is for.
        // Reading Settings.selectedProfile here instead forked the two
        // facts whenever the selection changed mid-start: this profile's
        // content could get another profile's scripts / chain binding.
        profileId: Long = Settings.selectedProfile,
    ): String {
        OverrideStatus.clear()
        val warnings = mutableListOf<OverrideNotice>()
        // Pipeline (one set of rules at a time, no overlapping routing):
        // 1. 配置规范化 / sanitize — kernel syntax only, keep nodes/groups/routes.
        //    Banner only if heal actually rewrote the config (已修正 + notes).
        // 2. overlay script — if bound, it rewrites routing from the raw
        //    config. Switches are passed into the script as `overlay` so a
        //    cooperative script can honor them, but since the system-explicit
        //    rework (design v2 §2.2) the script no longer owns the final word:
        //    step 5 applies after it, unconditionally.
        // 3. chain — if bound, compile the script-produced entry graph into
        //    the native entry → landing path. Scripts and chain are intentionally
        //    composable; a script never mutates the saved subscription.
        // 4. kernel compat / clash modes / per-rung rule-set handling.
        // 5. system explicit (SystemExplicitSegment) — the LAST route/dns
        //    stage: China family / VPN-leak final policy / strict / QUIC /
        //    DNS / IPv6 / privacy / WebRTC / ads, written over whatever the
        //    subscription or a script produced, as one ordered rule block.
        val healed = if (Settings.configNormalize) {
            ConfigNormalize.heal(content)
        } else {
            ConfigNormalize.HealResult(ConfigCompat.sanitize(content), emptyList())
        }
        var out = healed.content

        val binding = ChainBindings.get(profileId)
        val savedEntry = binding?.entryTag?.trim().orEmpty()

        try {
            var root = JSONObject(out)
            applyLogLevel(root)
            val scripts = if (skipScripts) emptyList() else OverlayScripts.enabledFor(profileId)
            // scriptOn means the scripts actually RAN, not merely that
            // some are bound: a script that throws applies nothing
            // (ConfigScriptOverride is all-or-nothing), so the App overlay
            // switches below must take over instead of yielding to a
            // script that never wrote anything — and the failure must be
            // a visible error, not a skippable warning.
            var scriptOn = false
            if (scripts.isNotEmpty()) {
                val failure = runCatching {
                    ConfigScriptOverride.apply(root, profileId)
                }.exceptionOrNull()
                if (failure == null) {
                    scriptOn = true
                } else {
                    warnings += OverrideNotice(
                        title = "覆写脚本未生效",
                        reason = failure.message ?: "脚本执行失败",
                        hint = "脚本没有套上，这次已改用应用的覆写开关按订阅原配置加工。绑定还在，修好脚本后再开。",
                        error = true,
                    )
                }
            }
            out = ConfigCompat.sanitize(root.toString())
            val entryMissing = binding != null && savedEntry.isNotEmpty() &&
                ChainRuntimeCompiler.savedEntryMatches(out, savedEntry) == null
            if (binding != null) {
                try {
                    // D2 (scoped, NF-3): only a binding that has already
                    // materialized successfully fails closed when its saved
                    // entry disappears — never-materialized bindings keep
                    // the graceful fallback notice below. The policy lives
                    // here at the startup caller; resolveMainTag itself and
                    // its UI/dashboard callers are untouched.
                    if (ChainBindingCodec.entryDisappearanceFailsClosed(binding, entryMissing)) {
                        error(
                            "保存的入口「$savedEntry」在当前生效配置里不存在（订阅更新或脚本改名都可能）。" +
                                "这条链式绑定此前已成功生效过，为避免流量被悄悄改走其它分组，已停止启动。" +
                                "请到「工具 → 链式代理」重新选择入口。",
                        )
                    }
                    out = ConfigChainReapply.apply(out, profileId)
                    if (ConfigChainReapply.lastLandingNotes.isNotEmpty()) {
                        warnings += OverrideNotice(
                            title = "链式落地",
                            reason = ConfigChainReapply.lastLandingNotes.joinToString("；"),
                            hint = "",
                        )
                    }
                    if (ConfigChainReapply.lastBothHopsTls) {
                        warnings += OverrideNotice(
                            title = "链式两跳均为 TLS",
                            reason = "入口与落地的节点都使用 TLS，双层 TLS 在部分网络下可能不稳定。",
                            hint = "若连接异常，可换一个非 TLS 的入口或落地试试。",
                        )
                    }
                    if (entryMissing) {
                        warnings += OverrideNotice(
                            title = "链式入口已失效",
                            reason = "保存的入口「$savedEntry」在当前生效配置里不存在（订阅更新或脚本改名都可能），已自动改用当前配置的主分组。落地绑定仍有效。",
                            hint = "不必重新配链式。若入口不对，到「工具 → 链式代理」重选一次即可。",
                        )
                    }
                    ConfigChainReapply.lastDiscardedSummary?.let { summary ->
                        warnings += OverrideNotice(
                            title = "链式成员已过滤",
                            reason = summary,
                            hint = "被丢弃的成员不进入链路，其余成员照常组链。若其中有特意放的 direct/block 成员，它在链里不生效。",
                        )
                    }
                } catch (e: Exception) {
                    val notice = OverrideNotice(
                        title = "链式代理未生效，已停止启动",
                        reason = e.message ?: "无法串联出站",
                        hint = "链路只绑定当前配置，订阅更新不会清掉绑定。请到「工具 → 链式代理」确认入口和落地。失败不会自动改走 DIRECT。",
                        error = true,
                    )
                    OverrideStatus.set(warnings + notice)
                    throw ChainApplyException(notice.reason)
                }
            }
            root = JSONObject(out)
            applyLogLevel(root)
            if (scriptOn) {
                val label = scripts.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()
                    .joinToString("、").ifBlank { "脚本" }
                warnings += OverrideNotice(
                    title = "${label}覆写",
                    reason = "脚本启用中",
                    hint = "",
                )
            }
            if (healed.changed) {
                warnings += OverrideNotice(
                    title = "配置规范化",
                    reason = "已修正",
                    hint = healed.notes.joinToString("；"),
                )
            }
            // Missing TUN cannot capture traffic (0 connections) and strict
            // route has nothing to write. Insert a standard TUN at runtime
            // only — do not turn the default script on, and do not edit the
            // subscription file.
            // Structural stages run isolated like the switches below: one
            // stage throwing must not discard every stage that already
            // succeeded (the old catch-all returned the pre-pipeline
            // content and the service "started" with none of this applied).
            applyOne(warnings, "TUN 入站") {
                ConfigInboundCompat.ensureAndroidTun(root)
            }
            // A bound script owns the dns block: the bundled sample rebuilds
            // it from scratch, which wipes the ECH query server/route the
            // import installed — and its own HTTPS/SVCB reject then starves
            // every ECH node. Re-assert the ECH route after any script,
            // whatever the script emitted.
            applyOne(warnings, "ECH 查询路由") {
                if (scriptOn) ConfigIngest.ensureEchQueryRoute(root)
            }
            // After scripts and chain merge: rewrite 404 remote rule-sets to
            // official testingcf geosite/geoip URLs so APP routing still
            // matches the original tags. Chain landing is untouched.
            applyOne(warnings, "内核兼容") {
                ConfigInboundCompat.applyKernelCompat(root)
            }
            applyOne(warnings, "缓存文件") {
                ConfigInboundCompat.ensureCacheFile(root, profileId.toString())
            }
            applyOne(warnings, "直连超时") {
                ConfigInboundCompat.ensureDirectHttpTimeout(root)
            }
            applyOne(warnings, "模式规则") {
                // ensureClashModes injects Global/Direct rules and reorders
                // route rules — it IS config normalization, so the switch
                // gates it too. It used to run unconditionally, rewriting
                // the runtime config after the user turned normalization
                // off, with no notice.
                if (Settings.configNormalize) ConfigNormalize.ensureClashModes(root)
            }
            if (BuildConfig.KERNEL_UPSTREAM.startsWith("1.15")) {
                applyOne(warnings, "按需连接") {
                    applyOnDemand(root, Settings.onDemand)
                }
            }
            applyOne(warnings, "DNS detour 清理") {
                ConfigCompat.stripBrokenDnsDetours(root)
            }
            if (stripEch) {
                applyOne(warnings, "ECH 剥离") {
                    ConfigIngest.stripEch(root)
                }
            }
            applyOne(warnings, "规则集处理") {
                if (replaceRuleSetNeedles.isNotEmpty()) {
                    ConfigInboundCompat.replaceRemoteRuleSetsMatching(root, replaceRuleSetNeedles)
                }
                if (substituteCachedRuleSets) {
                    val dir = runCatching {
                        io.nekohasekai.sfa.Application.application.getExternalFilesDir(null)
                            ?: io.nekohasekai.sfa.Application.application.filesDir
                    }.getOrNull()
                    if (dir != null) ConfigNormalize.substituteCachedRuleSets(root, dir)
                }
                if (dropAllRemoteRuleSets) {
                    ConfigInboundCompat.dropAllRemoteRuleSets(root)
                } else if (dropRuleSetNeedles.isNotEmpty()) {
                    ConfigInboundCompat.dropRemoteRuleSetsMatching(root, dropRuleSetNeedles)
                }
                if (dropRuleSetTags.isNotEmpty()) {
                    ConfigInboundCompat.dropRemoteRuleSetsByTags(root, dropRuleSetTags)
                }
                // Localization store (137b): stamp `initial_path` on
                // the surviving remote definitions whose exact URL
                // has a last-good copy in RuleSetStore, so a cold
                // start floors on the stored bytes while the URL
                // keeps driving kernel updates. Runs after the drops
                // above — a suppressed definition is already gone and
                // gets nothing — and is read-only against the store,
                // so every rung's rebuild is stamped the same way and
                // the start path gains no writes or network from it.
                val storeDir = runCatching {
                    RuleSetStore.storeDir(
                        io.nekohasekai.sfa.Application.application.filesDir,
                    )
                }.getOrNull()
                if (storeDir != null) RuleSetStore.stampInitialPaths(root, storeDir)
            }
            // System-explicit segment: the last route/dns-mutating stage.
            // No script gate — the toggles apply over subscription and script
            // output alike (design v2 §2.2; the pre-v2 behavior yielded all
            // seven route-class toggles to any script that ran). Stage order
            // below is execution order only; the route rules land as ONE
            // ordered block in assembleRouteRules.
            // D9: when this run is a ladder recovery that dropped
            // rule-sets, the segment must not resurrect what it would
            // otherwise inject — it runs after the rule-set rungs above
            // and would rebuild the exact config that just failed to
            // download (v1.0.133-beta field failure). The suppression
            // covers EVERY app-side injector in the segment: the CN
            // sets (133) and the ads set, whose omission from the 134
            // suppression rebuilt the failing config on the drop rung
            // all over again (v1.0.134-beta field failure).
            val suppressedRuleSets = ladderSuppressedRuleSets(
                dropAll = dropAllRemoteRuleSets,
                needles = dropRuleSetNeedles,
                tags = dropRuleSetTags,
            )
            // Bundled seeds (137a): resolve installed seed paths
            // against the app files dir, the same runCatching shape
            // as the substitute rung's dir above. No files dir (or no
            // seed installed) → the provider yields null and every
            // definition ships without initial_path, exactly as
            // before this feature.
            val seedFilesDir = runCatching {
                io.nekohasekai.sfa.Application.application.filesDir
            }.getOrNull()
            val seedPath: (String) -> String? = { tag ->
                seedFilesDir?.let { RuleSetSeeds.initialPathIfInstalled(it, tag) }
            }
            val segment = SystemExplicitSegment(
                root,
                SystemExplicitSegment.Config(
                    chinaIpDomainDirect = Settings.chinaIpDomainDirect,
                    chinaPublicDnsDirect = Settings.chinaPublicDnsDirect,
                    lanDirect = Settings.lanDirect,
                    privacyProtect = Settings.privacyProtect,
                    vpnLeakProtect = Settings.vpnLeakProtect,
                    disableQuic = Settings.disableQuic,
                    excludeCnQuic = Settings.excludeCnQuic,
                    dnsProtect = Settings.dnsProtect,
                    dnsProtectLegacyFillOnly = Settings.dnsProtectLegacyFillOnly,
                    disableIpv6 = Settings.disableIpv6,
                    webrtcProtect = Settings.webrtcProtect,
                    adsBlock = Settings.adsBlock,
                ),
                chainTag = binding?.let {
                    ChainRuntimeCompiler.chainTag(profileId, it.landingProfileId)
                },
                suppressedRuleSetTags = suppressedRuleSets,
                seedPath = seedPath,
            )
            applyOne(warnings, "中国直连") { segment.applyChinaFamily() }
            applyOne(warnings, "防 VPN 泄露") { segment.applyVpnLeakProtect() }
            // B7: strictRoute is a TRUE sub-item of vpnLeakProtect (same
            // rule as the other sub-items): the stage registers only when
            // the parent is on. The stored strictRoute value itself is
            // never rewritten here — parent off simply leaves the
            // subscription's own strict_route untouched.
            if (Settings.vpnLeakProtect) {
                applyOne(warnings, "严格路由") { applyStrictRoute(root, Settings.strictRoute) }
            }
            applyOne(warnings, "禁用 QUIC") { segment.applyQuicToggle() }
            applyOne(warnings, "DNS 防泄漏") { segment.applyDnsProtectToggle() }
            applyOne(warnings, "禁用 IPv6") { segment.applyDisableIpv6Toggle() }
            applyOne(warnings, "防隐私") { segment.applyPrivacyToggle() }
            applyOne(warnings, "防 WebRTC 泄露") { segment.applyWebrtcToggle() }
            applyOne(warnings, "广告拦截") { segment.applyAdsToggle() }
            applyOne(warnings, "系统显式") { segment.assembleRouteRules() }
            warnings += segment.notices
            out = root.toString()
        } catch (e: ChainApplyException) {
            throw e
        } catch (e: Exception) {
            // A fatal here (e.g. the post-script sanitize rejects the
            // script's output) means the processed config cannot be
            // trusted: hard-stop with the reason instead of silently
            // starting the raw subscription with no chain / no overlays.
            OverrideStatus.set(
                warnings + OverrideNotice(
                    title = "配置加工失败，已停止启动",
                    reason = e.message ?: "覆盖失败",
                    hint = "没有用未加工的订阅原文启动。关掉脚本或对应开关后再试。",
                    error = true,
                ),
            )
            throw IllegalStateException("配置加工失败，已停止启动：${e.message ?: "未知错误"}", e)
        }

        OverrideStatus.set(warnings)
        return out
    }

    /**
     * Every app-injected rule-set tag the startup ladder has given up
     * on for this run: the CN sets (see
     * [ConfigChinaDirect.ladderSuppressedCnRuleSets]) plus the ads set
     * ([ConfigAdBlock.RULESET_TAG]), which is the segment's second
     * definition injector. A drop-all rung suppresses both; a needle
     * drop suppresses whichever of them the needles name. Replace-rung
     * needles are not passed here (see the CN function's docs).
     */
    internal fun ladderSuppressedRuleSets(
        dropAll: Boolean,
        needles: Collection<String>,
        tags: Collection<String> = emptyList(),
    ): Set<String> {
        val suppressed = ConfigChinaDirect.ladderSuppressedCnRuleSets(dropAll, needles).toMutableSet()
        if (dropAll) {
            suppressed += ConfigAdBlock.RULESET_TAG
        } else if (needles.isNotEmpty()) {
            val stems = needles.map {
                it.trim().lowercase().substringAfterLast('/').substringBefore('?').removeSuffix(".srs")
            }.toSet()
            if (ConfigAdBlock.RULESET_TAG in stems) suppressed += ConfigAdBlock.RULESET_TAG
        }
        // Triage (v1.0.136) names exact tags, app-injected ones
        // included: an app set the triage proved undownloadable is
        // suppressed by name, no stem matching involved.
        if (tags.isNotEmpty()) {
            val appTags = ConfigChinaDirect.CN_RULE_SET_TAGS + ConfigAdBlock.RULESET_TAG
            suppressed += appTags.filter { it in tags }
        }
        return suppressed
    }

    private fun applyOne(warnings: MutableList<OverrideNotice>, title: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            warnings += OverrideNotice(
                title = "$title 未完全生效",
                reason = e.message ?: "覆盖失败",
                hint = "该开关会强制覆盖运行时配置，不改订阅文件。其它已开启的开关仍会继续写入。",
                error = true,
            )
        }
    }

    internal fun applyLogLevel(root: JSONObject) {
        val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
        log.put("level", "info")
    }

    /**
     * The WebRTC reject block for the system-explicit segment: the existing
     * port/keyword set plus the kernel-native STUN protocol match (design
     * v2 §2.2 item 1). The protocol rule only fires after the flow was
     * sniffed — the segment puts a sniff-all rule at the head of this block
     * when it assembles (NF-20).
     */
    internal fun webrtcRouteRules(): JSONArray {
        val rules = ConfigNormalize.webrtcRejectRules()
        rules.put(
            JSONObject()
                .put("protocol", JSONArray().put("stun"))
                .put("action", "reject"),
        )
        return rules
    }

    /**
     * Mihomo REJECT, not REJECT-DROP. Default reject resets the flow so
     * HTTP/3 clients fall back to TCP. method drop blackholes UDP 443 and
     * looks like a hang. China UDP 443 is routed direct first when
     * [excludeCnQuic] is on and the China rule-sets exist.
     */
    internal fun quicRouteRules(root: JSONObject, excludeCnQuic: Boolean): JSONArray {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val direct = firstDirectTag(root)
        val sets = ruleSetTags(route)
        val extra = JSONArray()
        if (excludeCnQuic && direct != null) {
            if ("geoip-cn" in sets) {
                extra.put(
                    JSONObject()
                        .put("network", "udp")
                        .put("port", 443)
                        .put("rule_set", "geoip-cn")
                        .put("outbound", direct),
                )
            }
            val domains = JSONArray()
            if ("geosite-cn" in sets) domains.put("geosite-cn")
            if ("geosite-geolocation-cn" in sets) domains.put("geosite-geolocation-cn")
            if (domains.length() > 0) {
                extra.put(
                    JSONObject()
                        .put("network", "udp")
                        .put("port", 443)
                        .put("rule_set", domains)
                        .put("outbound", direct),
                )
            }
        }
        extra.put(
            JSONObject()
                .put("network", "udp")
                .put("port", 443)
                .put("action", "reject"),
        )
        return extra
    }

    /** Historical entry point: prepend the QUIC block to the current rules. */
    internal fun applyQuic(root: JSONObject) {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val old = route.optJSONArray("rules") ?: JSONArray()
        val extra = quicRouteRules(root, Settings.excludeCnQuic)
        val merged = JSONArray()
        for (i in 0 until extra.length()) merged.put(extra.get(i))
        for (i in 0 until old.length()) merged.put(old.get(i))
        route.put("rules", merged)
    }

    private fun firstDirectTag(root: JSONObject): String? {
        val outs = root.optJSONArray("outbounds") ?: return null
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (!o.optString("type").equals("direct", true)) continue
            val tag = o.optString("tag").trim()
            if (tag.isNotEmpty()) return tag
        }
        return null
    }

    private fun ruleSetTags(route: JSONObject): Set<String> {
        val sets = route.optJSONArray("rule_set") ?: return emptySet()
        val tags = linkedSetOf<String>()
        for (i in 0 until sets.length()) {
            val tag = sets.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
            if (tag.isNotEmpty()) tags += tag
        }
        return tags
    }

    internal fun applyStrictRoute(root: JSONObject, enabled: Boolean = true) {
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also { root.put("inbounds", it) }
        var touched = false
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            if (ib.optString("type") != "tun") continue
            ib.put("strict_route", enabled)
            touched = true
        }
        if (!touched && enabled) {
            throw IllegalStateException("当前配置没有 TUN 入站，严格路由无法写入")
        }
    }

    private val onDemandTypes = setOf("wireguard", "tailscale", "openvpn", "openconnect")

    /** 1.15 endpoint/outbound field. Not routing; not gated by overlay scripts. */
    internal fun applyOnDemand(root: JSONObject, enabled: Boolean) {
        fun walk(key: String) {
            val arr = root.optJSONArray(key) ?: return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("type") in onDemandTypes) {
                    obj.put("on_demand", enabled)
                }
            }
        }
        walk("endpoints")
        walk("outbounds")
    }

    /**
     * 真正的 DNS 防泄漏（默认开就必须真生效）：
     * 1. fakeip DNS：A/AAAA 查询返回 198.18.0.0/15 假地址，应用拿不到真实 IP，
     *    sing-box 在路由前把假地址映射回域名，走隧道出站；
     * 2. 53 端口 + dns 协议流量劫持到内部 DNS，应用自带的 DNS 配置绕不过；
     * 3. HTTPS/SVCB 查询直接拒绝（防 ECH/DNS 旁路）；节点自身的 ECH 查询域名
     *    除外——放行到专用 ech-* DNS 服务器（见 ensureFakeIpDns 的规则排序），
     *    否则内核取不到 ECH 配置，带 ECH 的节点永远连不上。
     * 幂等：重复调用不会叠加。strategy 与韧性默认值只做加法不覆盖；final 的
     * 处理分两档（design v2 §2.2 第 2 条）：默认覆盖模式把 final 强制改到
     * 受保护服务器（第一个非中国远程类型服务器——订阅自带的中国 DNS 是直达
     * 解析器，永不作候选、detour 永不被改；没有则新建 sys-dns-remote）并把
     * 其 detour 强制为当前出站，覆盖了订阅/脚本的设置时发 OverrideNotice
     * (NF-7)；dnsProtectLegacyFillOnly 开启时回到旧行为——只补空白。
     *
     * DNS 韧性默认值（1.14+ 内核均支持；已有用户配置只做加法不覆盖）：
     * - optimistic：过期缓存先返回、后台刷新，弱网/断网瞬间解析成功率更高；
     * - timeout：单查询 5s（内核默认 10s），弱网下 fallback 更快；
     * - cache_capacity：LRU 2048（小于 1024 会被内核忽略），提升命中率、省电省流量。
     */
    internal fun applyDnsProtect(
        root: JSONObject,
        cnMirrorRules: JSONArray = JSONArray(),
        overwriteFinal: Boolean = true,
        egressTag: String? = null,
        notices: MutableList<OverrideNotice>? = null,
    ) {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val finalBefore = dns.optString("final")
        if (dns.optString("strategy").isBlank()) {
            dns.put("strategy", "prefer_ipv4")
        }
        if (!dns.has("optimistic")) {
            dns.put("optimistic", true)
        }
        if (!dns.has("timeout")) {
            dns.put("timeout", "5s")
        }
        if (!dns.has("cache_capacity")) {
            dns.put("cache_capacity", 2048)
        }
        // ECH 查询域名必须先有显式路由（官方同构：走订阅的远程 DNS），否则
        // 会被 ensureFakeIpDns 里的 HTTPS/SVCB reject 吞掉——原生 sing-box
        // 订阅自身没有 ech-* 规则时尤其如此。幂等：已有路由不动。
        ConfigIngest.ensureEchQueryRoute(root)
        ensureFakeIpDns(dns, cnMirrorRules)
        if (overwriteFinal) {
            applyProtectedDnsFinal(root, egressTag, finalBefore, notices)
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        route.put("auto_detect_interface", true)
        ConfigInboundCompat.ensureHijackDns(root)
    }

    /** First server whose type can actually carry queries off-device. */
    private fun firstProtectedDnsServer(dns: JSONObject): JSONObject? {
        val servers = dns.optJSONArray("servers") ?: return null
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (server.optString("tag").isBlank()) continue
            // sys-cn-dns is the China mirror target, not a general resolver.
            if (server.optString("tag") == ConfigChinaDirect.SYS_CN_DNS_TAG) continue
            // Neither is any China DNS endpoint the subscription defines
            // itself (its `dns-cn`, a 223.5.5.5 UDP server, ...): those
            // are direct-reach resolvers. Promoting one to the protected
            // final would send every unmatched query to a China resolver
            // and force its detour onto the egress — the exact pair of
            // notices from the v1.0.133-beta field failure.
            if (ConfigChinaDirect.isChinaDnsServer(server)) continue
            val type = server.optString("type").lowercase()
            if (type in setOf("https", "tls", "udp", "tcp", "quic")) return server
        }
        return null
    }

    /**
     * True when [tag] names a direct outbound the kernel counts as
     * empty — no dialer options at all (its IsEmpty test deep-equals
     * the dialer options against the zero value). [ConfigCompat]'s
     * isEmptyDirect only ever widens that verdict: every field it
     * checks is a dialer option, so a direct it calls non-empty is
     * always safe to detour onto, and a false "empty" verdict costs
     * only a skipped detour rewrite, never a refused start.
     */
    private fun isEmptyDirectOutbound(root: JSONObject, tag: String): Boolean {
        val outs = root.optJSONArray("outbounds") ?: return false
        for (i in 0 until outs.length()) {
            val outbound = outs.optJSONObject(i) ?: continue
            if (outbound.optString("tag") == tag) return ConfigCompat.isEmptyDirect(outbound)
        }
        return false
    }

    private fun applyProtectedDnsFinal(
        root: JSONObject,
        egressTag: String?,
        finalBefore: String,
        notices: MutableList<OverrideNotice>?,
    ) {
        val dns = root.optJSONObject("dns") ?: return
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        var protected = firstProtectedDnsServer(dns)
        if (protected == null) {
            protected = JSONObject()
                .put("type", "https")
                .put("tag", "sys-dns-remote")
                .put("server", "8.8.8.8")
            servers.put(protected)
        }
        val protectedTag = protected.optString("tag")
        if (egressTag != null) {
            val oldDetour = protected.optString("detour")
            if (oldDetour != egressTag) {
                if (isEmptyDirectOutbound(root, egressTag)) {
                    // B-6: the kernel rejects a detour onto an EMPTY
                    // direct outbound at startup ("detour to an empty
                    // direct outbound makes no sense", dialer/detour).
                    // Reachable whenever route.final is a plain direct:
                    // with vpnLeakProtect off the segment never moves
                    // the final, and even with it on, resolveMainTag
                    // may find no main group to move to. Skip the
                    // rewrite — the final override below still applies —
                    // and say so instead of poisoning the start.
                    notices?.add(
                        OverrideNotice(
                            title = "系统显式",
                            reason = "DNS 服务器「$protectedTag」的 detour 未改为当前出站「$egressTag」",
                            hint = "当前出站是未带任何拨号参数的直连出站，内核拒绝 detour 到空 direct（会停止启动）；本次保留其原 detour。",
                        ),
                    )
                } else {
                    protected.put("detour", egressTag)
                    if (oldDetour.isNotBlank()) {
                        notices?.add(
                            OverrideNotice(
                                title = "系统显式",
                                reason = "DNS 服务器「$protectedTag」的 detour 由「$oldDetour」改为当前出站「$egressTag」",
                                hint = "DNS 防泄漏要求解析出站与流量出站一致，否则查询仍从原 detour 泄露。",
                            ),
                        )
                    }
                }
            }
        }
        if (dns.optString("final") != protectedTag) {
            dns.put("final", protectedTag)
            if (finalBefore.isNotBlank()) {
                notices?.add(
                    OverrideNotice(
                        title = "系统显式",
                        reason = "DNS 最终解析由「$finalBefore」覆盖为「$protectedTag」",
                        hint = "DNS 防泄漏开启时，未匹配 dns.rules 的查询走受保护的远程服务器。如需保留订阅原值，在「系统显式」里打开「DNS 仅补齐空白」。",
                    ),
                )
            }
        }
    }

    private fun ensureFakeIpDns(dns: JSONObject, cnMirrorRules: JSONArray = JSONArray()) {
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        var fakeIpTag: String? = null
        var sawFakeIp = false
        var firstRealTag: String? = null
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("type") == "fakeip" || s.optString("tag") == "dns-fakeip") {
                sawFakeIp = true
                // The fakeip server's OWN tag, whatever it is: the
                // legacy conversion (ConfigCompat) names it "fakeip",
                // the bundled script names it "dns-fakeip", a
                // subscription may use anything else. The rule woven
                // below must name THIS tag — hardcoding "dns-fakeip"
                // here pointed the A/AAAA rule at a transport that
                // does not exist whenever the tags disagreed, and the
                // kernel logged `dns: transport not found: dns-fakeip`
                // for every such query (v1.0.135-beta field log).
                if (fakeIpTag == null) {
                    fakeIpTag = s.optString("tag").ifBlank { null }
                }
            } else if (firstRealTag == null) {
                firstRealTag = s.optString("tag").ifBlank { null }
            }
        }
        if (!sawFakeIp) {
            val fakeIp = JSONObject()
                .put("type", "fakeip")
                .put("tag", "dns-fakeip")
                .put("inet4_range", "198.18.0.0/15")
            val merged = JSONArray().put(fakeIp)
            for (i in 0 until servers.length()) merged.put(servers.get(i))
            dns.put("servers", merged)
        }
        // An untagged fakeip server cannot be referenced by any rule;
        // fall back to the canonical name (pre-fix behaviour) rather
        // than inventing a second fakeip server.
        val effectiveFakeIpTag = fakeIpTag ?: "dns-fakeip"
        if (firstRealTag == null) {
            // 没有任何真实上游：补一个走隧道（默认路由）的远程 DNS，
            // 否则 final 只能指向 fakeip，非 A/AAAA 查询会失败。
            val remote = JSONObject()
                .put("type", "tcp")
                .put("tag", "dns-remote")
                .put("server", "1.1.1.1")
                .put("server_port", 53)
            dns.getJSONArray("servers").put(remote)
            firstRealTag = "dns-remote"
        }
        if (dns.optString("final").isBlank()) {
            dns.put("final", firstRealTag)
        }
        // 规则顺序（内核按序首命中）固定为：ECH 查询路由 → HTTPS/SVCB reject →
        // 中国镜像（仅中国直连开启时）→ A/AAAA fakeip → 其余原规则。ECH 规则
        // 必须在 reject 之前：内核为节点取 ECH 配置发的正是 HTTPS 查询，先命中
        // reject 就永远轮不到 ECH 规则。ECH 规则按域名精确匹配
        // （ConfigIngest.prependEchRule），只有 ECH 查询域名放行到专用 ech-*
        // DNS，其它域名的 HTTPS/SVCB 照旧拒绝。中国镜像必须在 fakeip 之前：
        // A/AAAA 查询先被 fakeip 规则命中就只会拿到假地址，镜像永不触发。
        val rules = dns.optJSONArray("rules") ?: JSONArray().also { dns.put("rules", it) }
        val echRules = JSONArray()
        val rest = JSONArray()
        var rejectRule: JSONObject? = null
        var fakeIpRule: JSONObject? = null
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            when {
                r.optString("server").startsWith("ech-") -> echRules.put(r)
                // Stale mirror rules from an earlier pass are dropped here;
                // the canonical set is woven back in below.
                cnMirrorRules.length() > 0 &&
                    r.optString("server") == ConfigChinaDirect.SYS_CN_DNS_TAG -> Unit
                rejectRule == null && r.optString("action") == "reject" &&
                    queryTypesContain(r, "HTTPS") -> rejectRule = r
                fakeIpRule == null && r.optString("server") == effectiveFakeIpTag -> fakeIpRule = r
                // A rule naming the canonical "dns-fakeip" while the
                // actual fakeip server carries another tag can only
                // mean that server (the name is ours); retarget it
                // instead of leaving a dangling transport reference.
                fakeIpRule == null && effectiveFakeIpTag != "dns-fakeip" &&
                    r.optString("server") == "dns-fakeip" -> {
                    r.put("server", effectiveFakeIpTag)
                    fakeIpRule = r
                }
                else -> rest.put(r)
            }
        }
        val merged = JSONArray()
        for (i in 0 until echRules.length()) merged.put(echRules.get(i))
        merged.put(
            rejectRule ?: JSONObject()
                .put("query_type", JSONArray().put("HTTPS").put("SVCB"))
                .put("action", "reject"),
        )
        for (i in 0 until cnMirrorRules.length()) merged.put(cnMirrorRules.get(i))
        merged.put(
            fakeIpRule ?: JSONObject()
                .put("query_type", JSONArray().put("A").put("AAAA"))
                .put("server", effectiveFakeIpTag),
        )
        for (i in 0 until rest.length()) merged.put(rest.get(i))
        val unchanged = merged.length() == rules.length() &&
            (0 until merged.length()).all { merged.get(it) == rules.get(it) }
        if (!unchanged) dns.put("rules", merged)
    }

    private fun queryTypesContain(rule: JSONObject, type: String): Boolean {
        val qt = rule.optJSONArray("query_type") ?: return false
        for (i in 0 until qt.length()) {
            if (qt.optString(i).equals(type, ignoreCase = true)) return true
        }
        return false
    }

    /** The IPv6 reject rule (order table #4), built for the segment. */
    internal fun disableIpv6RouteRule(): JSONObject =
        JSONObject().put("ip_version", 6).put("action", "reject")

    /** The non-route-rule parts of IPv6 disabling: DNS strategy + TUN v6 strip. */
    internal fun applyDisableIpv6Mutations(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        dns.put("strategy", "ipv4_only")
        val inbounds = root.optJSONArray("inbounds") ?: return
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            if (ib.optString("type") == "tun") ib.remove("inet6_address")
        }
    }

    /** Historical entry point: mutations + reject rule prepended. */
    internal fun applyDisableIpv6(root: JSONObject) {
        applyDisableIpv6Mutations(root)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val old = route.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray().put(disableIpv6RouteRule())
        for (i in 0 until old.length()) merged.put(old.get(i))
        route.put("rules", merged)
    }
}
