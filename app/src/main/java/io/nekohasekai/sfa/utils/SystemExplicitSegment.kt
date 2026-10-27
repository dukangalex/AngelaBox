package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.chain.ChainRuntimeCompiler
import org.json.JSONArray
import org.json.JSONObject

/**
 * The system-explicit segment (design v2 §2.2): the LAST route/dns-mutating
 * stage of the startup pipeline. It runs after scripts, chain, kernel-compat
 * and clash modes, unconditionally — a bound script no longer switches these
 * toggles off (the pre-v2 `!scriptOn` gates are gone). Where the segment
 * overwrites a value the subscription or a script set (dns.final, a DNS
 * server's detour, route.final), it says so via [notices] (NF-7).
 *
 * Route rules are not written toggle by toggle. Each toggle registers its
 * rules into a slot and [assembleRouteRules] prepends ONE ordered block
 * (design §2.2 order table):
 *   #1  hijack-dns pair (written by the DNS stage, extracted to the head)
 *   #2  WebRTC rejects (a sniff-all rule at their head, NF-20)
 *   #3  discovery-protocol rejects (privacy)
 *   #4  IPv6 reject
 *   #5  DoT/DoQ port-853 reject (DNS overwrite mode only)
 *   #6  QUIC rules (CN exceptions first, then the UDP 443 reject)
 *   #7  LAN direct
 *   #8  China public DNS direct
 *   #9  China IP/domain direct
 *   #10 ads reject
 * ahead of whatever rules the config already had. Rules in the tail that are
 * identical to a block rule are dropped (the block copy wins the position),
 * which also keeps a second segment pass from stacking duplicates.
 *
 * The toggles arrive as an explicit [Config] so JVM tests can drive the
 * segment without the Android-backed Settings store; the pipeline builds it
 * from Settings in ConfigQuicOverride.apply.
 */
internal class SystemExplicitSegment(
    private val root: JSONObject,
    private val config: Config,
    private val chainTag: String?,
) {
    internal data class Config(
        val chinaIpDomainDirect: Boolean,
        val chinaPublicDnsDirect: Boolean,
        val lanDirect: Boolean,
        val privacyProtect: Boolean,
        val vpnLeakProtect: Boolean,
        val disableQuic: Boolean,
        val excludeCnQuic: Boolean,
        val dnsProtect: Boolean,
        val dnsProtectLegacyFillOnly: Boolean,
        val disableIpv6: Boolean,
        val webrtcProtect: Boolean,
        val adsBlock: Boolean,
    )

    val notices = mutableListOf<OverrideNotice>()

    private enum class Slot { HIJACK, WEBRTC, PRIVACY, IPV6, DOT, QUIC, LAN, PUBDNS, CHINA, ADS }

    private val blocks = mutableMapOf<Slot, JSONArray>()
    private var cnMirrorRules: JSONArray = JSONArray()
    private var dnsStageRan = false

    private fun register(slot: Slot, rules: JSONArray) {
        if (rules.length() > 0) blocks[slot] = rules
    }

    /**
     * China family: entities and definitions first (rule-set definitions,
     * sys-cn-dns, the guaranteed direct), blocks registered for assembly.
     * The legacy Settings.chinaDirect no longer drives anything here — the
     * three split switches gate their blocks independently.
     */
    fun applyChinaFamily() {
        if (!config.chinaIpDomainDirect && !config.chinaPublicDnsDirect && !config.lanDirect) return
        val direct = ConfigChinaDirect.ensureDetourableDirect(root)
        if (config.chinaIpDomainDirect) {
            val replaced = ConfigChinaDirect.ensureCnRuleSets(root)
            if (replaced.isNotEmpty()) {
                notices += OverrideNotice(
                    title = "系统显式",
                    reason = "订阅自带的同名规则集已换成应用钉版：${replaced.joinToString("、")}",
                    hint = "中国直连用应用钉版的规则集定义（订阅原文不改），避免订阅自带定义与钉版语义不一致。",
                )
            }
            ConfigChinaDirect.ensureSysCnDns(root, direct)
            register(Slot.CHINA, ConfigChinaDirect.chinaIpDomainRouteRules(direct))
            cnMirrorRules = ConfigChinaDirect.cnDnsMirrorRules()
        }
        if (config.lanDirect) {
            register(Slot.LAN, ConfigChinaDirect.lanRouteRules(direct))
        }
        if (config.chinaPublicDnsDirect) {
            register(Slot.PUBDNS, ConfigChinaDirect.publicDnsRouteRules(direct))
        }
        ConfigChinaDirect.dropLegacyChinaDns(root)
    }

    /**
     * VPN-leak final policy (D6): a missing or direct route.final is moved
     * to the profile's main proxy group. A chain-pinned final (written by
     * the chain stage) and a final that already names a proxy outbound/group
     * are left exactly as they are.
     */
    fun applyVpnLeakProtect() {
        if (!config.vpnLeakProtect) return
        if (!config.disableIpv6) {
            notices += OverrideNotice(
                title = "防 VPN 泄露",
                reason = "「禁用 IPv6」未开启：当前构建的 TUN 只捕获 IPv4，IPv6 流量不经隧道。",
                hint = "介意 IPv6 泄露时，在「系统显式」里同时打开「禁用 IPv6」。",
                error = true,
            )
        }
        val route = root.optJSONObject("route") ?: return
        val final = route.optString("final").trim()
        if (chainTag != null && final == chainTag) return
        val outs = root.optJSONArray("outbounds") ?: return
        val finalIsDirect = final.isNotEmpty() &&
            outboundType(outs, final).equals("direct", true)
        if (final.isNotEmpty() && !finalIsDirect) return
        val main = ChainRuntimeCompiler.resolveMainTag(outs, final) ?: return
        if (main == final) return
        route.put("final", main)
        notices += OverrideNotice(
            title = "系统显式",
            reason = if (final.isEmpty()) {
                "订阅未设最终出站，已改用当前配置的主分组「$main」"
            } else {
                "最终出站原为直连「$final」，已覆盖为当前配置的主分组「$main」"
            },
            hint = "防 VPN 泄露开启时，未命中规则的流量不会直连出门。链式已 pin 的最终出站不受此覆盖影响。",
        )
    }

    fun applyQuicToggle() {
        if (!config.disableQuic) return
        register(Slot.QUIC, ConfigQuicOverride.quicRouteRules(root, config.excludeCnQuic))
    }

    fun applyDnsProtectToggle() {
        if (!config.dnsProtect) return
        dnsStageRan = true
        ConfigQuicOverride.applyDnsProtect(
            root,
            cnMirrorRules = cnMirrorRules,
            overwriteFinal = !config.dnsProtectLegacyFillOnly,
            egressTag = currentEgressTag(),
            notices = notices,
        )
        // NF-19: app-originated DoT/DoQ connections are ordinary
        // connections judged by route rules — a port rule in dns.rules
        // would never see them. Legacy fill-only mode keeps the exact
        // pre-v2 DNS behavior, this reject included.
        if (!config.dnsProtectLegacyFillOnly) {
            register(
                Slot.DOT,
                JSONArray().put(
                    JSONObject()
                        .put("port", 853)
                        .put("network", JSONArray().put("tcp").put("udp"))
                        .put("action", "reject"),
                ),
            )
        }
    }

    fun applyDisableIpv6Toggle() {
        if (!config.disableIpv6) return
        ConfigQuicOverride.applyDisableIpv6Mutations(root)
        register(Slot.IPV6, JSONArray().put(ConfigQuicOverride.disableIpv6RouteRule()))
    }

    fun applyPrivacyToggle() {
        if (!config.privacyProtect) return
        register(
            Slot.PRIVACY,
            JSONArray().put(
                JSONObject()
                    .put("network", "udp")
                    .put("port", JSONArray().put(5353).put(5355).put(1900))
                    .put("action", "reject"),
            ),
        )
    }

    fun applyWebrtcToggle() {
        if (!config.webrtcProtect) return
        register(Slot.WEBRTC, ConfigQuicOverride.webrtcRouteRules())
    }

    fun applyAdsToggle() {
        if (!config.adsBlock) return
        register(Slot.ADS, ConfigAdBlock.applyBlock(root))
    }

    /** Prepend the collected blocks as one ordered block; see class docs. */
    fun assembleRouteRules() {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val old = route.optJSONArray("rules") ?: JSONArray()
        val tail = mutableListOf<Any?>()
        for (i in 0 until old.length()) tail += old.get(i)

        if (dnsStageRan) {
            // #1: the DNS stage's ensureHijackDns prepended its pair into the
            // rules; pull every top-level hijack-dns rule to the head slot.
            val hijack = JSONArray()
            val rest = mutableListOf<Any?>()
            for (item in tail) {
                val rule = item as? JSONObject
                if (rule != null && rule.optString("action").equals("hijack-dns", true)) {
                    hijack.put(rule)
                } else {
                    rest += item
                }
            }
            if (hijack.length() > 0) {
                blocks[Slot.HIJACK] = hijack
                tail.clear()
                tail.addAll(rest)
            }
        }

        // NF-20: a protocol rule only matches after the flow was sniffed,
        // and every rule the tail could offer sits behind this block — so
        // the WebRTC block carries its own sniff-all rule at its head. An
        // identical sniff-all further down is removed by the dedupe below.
        blocks[Slot.WEBRTC]?.let { webrtc ->
            val withSniff = JSONArray().put(JSONObject().put("action", "sniff"))
            for (i in 0 until webrtc.length()) withSniff.put(webrtc.get(i))
            blocks[Slot.WEBRTC] = withSniff
        }

        val block = JSONArray()
        for (slot in Slot.entries) {
            val rules = blocks[slot] ?: continue
            for (i in 0 until rules.length()) block.put(rules.get(i))
        }
        if (block.length() == 0) return
        val blockFingerprints = (0 until block.length())
            .map { ConfigChinaDirect.fingerprint(block.get(it)) }
            .toSet()
        val merged = JSONArray()
        for (i in 0 until block.length()) merged.put(block.get(i))
        for (item in tail) {
            if (item is JSONObject && ConfigChinaDirect.fingerprint(item) in blockFingerprints) {
                continue
            }
            merged.put(item)
        }
        route.put("rules", merged)
    }

    /** The effective egress at segment time: route.final after the chain stage (and after the VPN-leak stage, which runs earlier in the segment). */
    private fun currentEgressTag(): String? =
        root.optJSONObject("route")?.optString("final")?.trim()?.takeIf { it.isNotEmpty() }

    private fun outboundType(outs: JSONArray, tag: String): String {
        for (i in 0 until outs.length()) {
            val outbound = outs.optJSONObject(i) ?: continue
            if (outbound.optString("tag") == tag) return outbound.optString("type")
        }
        return ""
    }
}
