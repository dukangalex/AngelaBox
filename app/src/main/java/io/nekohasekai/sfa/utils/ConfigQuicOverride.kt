package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject

class ChainApplyException(message: String) : IllegalStateException(message)

object ConfigQuicOverride {

    suspend fun apply(
        content: String,
        skipScripts: Boolean = false,
        replaceRuleSetNeedles: Collection<String> = emptyList(),
    ): String {
        OverrideStatus.clear()
        val warnings = mutableListOf<OverrideNotice>()
        // Pipeline (one set of rules at a time, no overlapping routing):
        // 1. 配置规范化 / sanitize — kernel syntax only, keep nodes/groups/routes.
        //    Silent on first start; BoxService prompts only after a failed start.
        // 2. overlay script — if bound, it owns routing (China/ads/QUIC skipped)
        // 3. chain — if bound, scripts were already turned off for this profile
        // 4. China Direct / ads / QUIC — skipped while a script is on
        // 5. DNS / IPv6 / strict route — leak shields, still apply with scripts
        // 6. WebRTC last so STUN reject sits in front of China Direct
        var out = if (Settings.configNormalize) {
            ConfigNormalize.heal(content).content
        } else {
            ConfigCompat.sanitize(content)
        }

        val profileId = Settings.selectedProfile
        val binding = ChainBindings.get(profileId)
        val savedEntry = binding?.entryTag?.trim().orEmpty()
        val entryMissing = binding != null && savedEntry.isNotEmpty() && !outboundExists(out, savedEntry)

        try {
            var root = JSONObject(out)
            applyLogLevel(root)
            val scriptOn = !skipScripts && OverlayScripts.enabledFor(profileId).isNotEmpty()
            if (!skipScripts) {
                applyOne(warnings, "覆写脚本") {
                    ConfigScriptOverride.apply(root, profileId)
                }
            }
            out = ConfigCompat.sanitize(root.toString())
            if (binding != null) {
                try {
                    out = ConfigChainReapply.apply(out)
                    if (entryMissing) {
                        warnings += OverrideNotice(
                            title = "链式入口已随订阅更新",
                            reason = "保存的入口「$savedEntry」在新订阅里不存在，已自动改用当前配置的主分组。落地绑定仍有效。",
                            hint = "不必重新配链式。若入口不对，到「工具 → 链式代理」重选一次即可。",
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
            if (scriptOn && (Settings.chinaDirect || Settings.adsBlock || Settings.disableQuic)) {
                warnings += OverrideNotice(
                    title = "脚本分流中",
                    reason = "当前配置只跑脚本这一套规则：国内直连、国外走代理。",
                    hint = "关掉脚本后，中国直连 / 广告拦截 / 禁用 QUIC 会重新生效。DNS 防泄漏仍写入。",
                )
            }
            applyOne(warnings, "中国直连") {
                if (Settings.chinaDirect && !scriptOn) ConfigChinaDirect.apply(root)
            }
            applyOne(warnings, "禁用 QUIC") {
                if (Settings.disableQuic && !scriptOn) applyQuic(root)
            }
            applyOne(warnings, "严格路由") {
                if (Settings.strictRoute) applyStrictRoute(root)
            }
            applyOne(warnings, "DNS 防泄漏") {
                if (Settings.dnsProtect) applyDnsProtect(root)
            }
            applyOne(warnings, "禁用 IPv6") {
                if (Settings.disableIpv6) applyDisableIpv6(root)
            }
            // WebRTC last so reject rules prepend in front of China Direct.
            applyOne(warnings, "防 WebRTC 泄露") {
                if (Settings.webrtcProtect) applyWebrtc(root)
            }
            applyOne(warnings, "广告拦截") {
                if (Settings.adsBlock && !scriptOn) ConfigAdBlock.apply(root)
            }
            // After scripts and chain merge: rewrite 404 remote rule-sets to
            // official testingcf geosite/geoip URLs so APP routing still
            // matches the original tags. Chain landing is untouched.
            ConfigInboundCompat.apply(root)
            ConfigCompat.stripBrokenDnsDetours(root)
            if (replaceRuleSetNeedles.isNotEmpty()) {
                ConfigInboundCompat.replaceRemoteRuleSetsMatching(root, replaceRuleSetNeedles)
            }
            out = root.toString()
        } catch (e: ChainApplyException) {
            throw e
        } catch (e: Exception) {
            warnings += OverrideNotice(
                title = "网络增强开关部分未生效",
                reason = e.message ?: "覆盖失败",
                hint = "请检查配置是否含 TUN/路由段，或临时关闭对应开关。",
                error = true,
            )
        }

        OverrideStatus.set(warnings)
        return out
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

    private fun applyWebrtc(root: JSONObject) {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val old = route.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray()
        val extra = ConfigNormalize.webrtcRejectRules()
        for (i in 0 until extra.length()) merged.put(extra.get(i))
        for (i in 0 until old.length()) merged.put(old.get(i))
        route.put("rules", merged)
    }

    private fun applyQuic(root: JSONObject) {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val oldRules = route.optJSONArray("rules") ?: JSONArray()
        val injected = JSONArray()
        if (Settings.excludeCnQuic && Settings.chinaDirect) {
            injected.put(
                JSONObject()
                    .put("network", "udp")
                    .put("port", 443)
                    .put("domain_suffix", ConfigNormalize.cnDomainSuffixArray())
                    .put("outbound", ConfigChinaDirect.findOrCreateDirect(ensureOutbounds(root))),
            )
        }
        injected.put(
            JSONObject().put("network", "udp").put("port", 443).put("action", "reject"),
        )
        val merged = JSONArray()
        for (i in 0 until injected.length()) merged.put(injected.get(i))
        for (i in 0 until oldRules.length()) merged.put(oldRules.get(i))
        route.put("rules", merged)
    }

    internal fun applyStrictRoute(root: JSONObject) {
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also { root.put("inbounds", it) }
        var touched = false
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            if (ib.optString("type") != "tun") continue
            ib.put("strict_route", true)
            touched = true
        }
        if (!touched) {
            throw IllegalStateException("当前配置没有 TUN 入站，严格路由无法写入")
        }
    }

    internal fun applyDnsProtect(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        dns.put("independent_cache", true)
        if (!Settings.disableIpv6 && dns.optString("strategy").isBlank()) {
            dns.put("strategy", "prefer_ipv4")
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        route.put("auto_detect_interface", true)
    }

    internal fun applyDisableIpv6(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        dns.put("strategy", "ipv4_only")
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val old = route.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray().put(JSONObject().put("ip_version", 6).put("action", "reject"))
        for (i in 0 until old.length()) merged.put(old.get(i))
        route.put("rules", merged)
        val inbounds = root.optJSONArray("inbounds") ?: return
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            if (ib.optString("type") == "tun") ib.remove("inet6_address")
        }
    }

    private fun ensureOutbounds(root: JSONObject): JSONArray {
        return root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
    }

    private fun outboundExists(content: String, tag: String): Boolean {
        if (content.length > ConfigCompat.MAX_CONFIG_CHARS) return false
        return try {
            val outs = JSONObject(content).optJSONArray("outbounds") ?: return false
            for (i in 0 until outs.length()) {
                if (outs.optJSONObject(i)?.optString("tag") == tag) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
