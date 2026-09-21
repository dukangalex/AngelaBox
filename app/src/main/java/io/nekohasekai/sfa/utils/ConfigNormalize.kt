package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Compatibility rewriter for the current libbox. Nodes, selector/urltest
 * groups and user routing stay intact; only fields this kernel cannot
 * decode are rewritten. This is not China Direct / ads / QUIC — those
 * overlays run later and can be skipped when a script owns routing.
 * First start is silent; BoxService only prompts after a failed start.
 */
object ConfigNormalize {

    data class HealResult(val content: String, val notes: List<String>) {
        val changed: Boolean get() = notes.isNotEmpty()
    }

    val CN_DOMAIN_SUFFIXES: List<String> = listOf(
        "cn",
        "qq.com", "weixin.com", "wechat.com", "qpic.cn", "gtimg.cn", "idqqimg.com",
        "tencent.com", "tencent-cloud.net", "qcloud.com", "myqcloud.com",
        "baidu.com", "bdstatic.com", "bdimg.com",
        "alibaba.com", "alicdn.com", "aliyun.com", "alipay.com", "aliyuncs.com",
        "taobao.com", "tmall.com", "1688.com",
        "163.com", "126.com", "127.net", "netease.com",
        "jd.com", "360buyimg.com",
        "bilibili.com", "hdslb.com", "biliapi.net",
        "iqiyi.com", "iqiyipic.com",
        "youku.com", "ykimg.com",
        "douyin.com", "amemv.com", "toutiao.com", "bytedance.com", "pstatp.com", "snssdk.com",
        "weibo.com", "sina.com.cn", "sinaimg.cn",
        "zhihu.com", "zhimg.com",
        "meituan.com", "dianping.com", "sankuai.com",
        "pinduoduo.com", "yangkeduo.com",
        "xiaomi.com", "mi.com", "miui.com",
        "huawei.com", "honor.com", "hicloud.com", "vmall.com",
        "oppo.com", "heytap.com", "realme.com", "oneplus.com", "vivo.com",
        "ctrip.com", "qunar.com",
        "suning.com", "smzdm.com",
        "kugou.com", "kuwo.cn",
        "migu.cn", "10086.cn", "10010.com", "189.cn",
        "gov.cn", "edu.cn", "ac.cn", "org.cn", "com.cn", "net.cn",
        "douban.com", "csdn.net", "gitee.com",
        "ele.me", "dingtalk.com", "feishu.cn",
        "wps.cn", "unionpay.com", "unionpaysecure.com", "chinapay.com", "yeepay.com",
        "jdpay.com", "tenpay.com", "icbc.com.cn", "ccb.com", "boc.cn", "bankofchina.com",
        "abchina.com", "cmbchina.com", "bankcomm.com", "psbc.com", "spdb.com.cn",
        "cib.com.cn", "cmbc.com.cn", "citicbank.com", "cebbank.com", "cgbchina.com.cn",
        "pingan.com",
        "alidns.com", "dnspod.cn", "360.cn",
        "sogou.com", "so.com", "uc.cn",
        "cctv.com", "people.com.cn", "xinhuanet.com",
        "coolapk.com", "thepaper.cn",
    )

    val STUN_UDP_PORTS: IntArray = intArrayOf(
        3478, 3479, 3480, 3481,
        5349, 5350, 5351,
        19302, 19303, 19304, 19305, 19306, 19307, 19308, 19309, 19310,
    )

    val STUN_TCP_PORTS: IntArray = intArrayOf(
        3478, 3479, 3480, 3481,
        5349, 5350, 5351,
    )

    fun cnDomainSuffixArray(): JSONArray {
        val a = JSONArray()
        CN_DOMAIN_SUFFIXES.forEach { a.put(it) }
        return a
    }

    /**
     * Highest-priority leak shield. Must be prepended *after* China Direct
     * so these reject rules sit in front of geo/domain bypasses. Otherwise
     * Chinese STUN (bilibili/hitv/miwifi:3478) matches 中国直连 → DIRECT
     * and the real ISP IP leaks, while global STUN on 19302 is still
     * rejected — exactly the IPPure split we saw.
     */
    fun webrtcRejectRules(): JSONArray {
        val rules = JSONArray()
        rules.put(
            JSONObject()
                .put("network", "udp")
                .put("port", toArray(STUN_UDP_PORTS))
                .put("action", "reject"),
        )
        rules.put(
            JSONObject()
                .put("network", "tcp")
                .put("port", toArray(STUN_TCP_PORTS))
                .put("action", "reject"),
        )
        rules.put(
            JSONObject()
                .put("domain_keyword", JSONArray().put("stun.").put("turn.").put("stuns.").put("turns."))
                .put("action", "reject"),
        )
        return rules
    }

    /**
     * Rewrite [root] in place for the current kernel. Returns Chinese notes
     * for each actual change so the dashboard can tell the user. Idempotent.
     */
    fun apply(root: JSONObject): List<String> {
        val notes = mutableListOf<String>()
        fun mark(changed: Boolean, message: String) {
            if (changed) notes += message
        }
        val outs = root.optJSONArray("outbounds")
        if (outs != null) {
            var n = 0
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                if (ConfigCompat.sanitizeOutbound(o)) n++
            }
            if (n > 0) notes += "已修正 $n 个节点的插件选项格式"
        }
        mark(ConfigCompat.migrateLegacyDns(root), "旧版 DNS / fakeip 已转为当前内核格式")
        mark(ConfigInboundCompat.migrateLegacyInbounds(root), "入站 sniff 已转为路由动作")
        // tun.stack strip is forward-compat for 1.15. Silent: default
        // subscriptions still emit the field, and removing it is not a defect.
        ConfigInboundCompat.stripDeprecatedTunStack(root)
        mark(ConfigInboundCompat.stripSniffOverrideDestination(root), "已去掉内核不再支持的 sniff 覆盖字段")
        mark(ConfigInboundCompat.healDirectDestinationOverride(root), "直连节点已去掉已删除字段")
        mark(ConfigInboundCompat.migrateSpecialOutbounds(root), "dns/block 出站已转为路由动作")
        mark(ConfigInboundCompat.rewriteRuleSetUrls(root), "规则集地址已换成可用镜像")
        mark(ConfigInboundCompat.healRemoteRuleSets(root), "无效规则集已换成官方地址")
        // 1.14 download client + hijack-dns are always-safe plumbing. Do not
        // emit notes: most valid subscriptions lack these fields, and a
        // standing「已修正」banner would be a lie when nothing was wrong.
        ConfigInboundCompat.healDownloadClients(root)
        mark(ConfigInboundCompat.healMissingOutboundRefs(root), "已清理指向不存在出站的引用")
        ConfigInboundCompat.ensureHijackDns(root)
        mark(ConfigCompat.stripBrokenDnsDetours(root), "已去掉会阻止启动的空 direct DNS 出口")
        return notes
    }

    /**
     * Clash mode chips only appear for names present in route/DNS rules.
     * Scripts that rebuild route.rules drop the subscription's clash_mode
     * entries, so the kernel reports only "Rule". Prepend Global/Direct
     * without changing default Rule routing. Idempotent.
     */
    fun ensureClashModes(root: JSONObject): Boolean {
        val outs = root.optJSONArray("outbounds") ?: return false
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
        val existing = clashModesIn(rules) + clashModesIn(root.optJSONObject("dns")?.optJSONArray("rules"))
        val needGlobal = existing.none { it.equals("Global", true) || it == "全局" }
        val needDirect = existing.none { it.equals("Direct", true) || it == "直连" }
        if (!needGlobal && !needDirect) return false
        val directTag = findDirectTag(outs)
        val globalTag = findGlobalTag(root, outs, directTag)
        val merged = JSONArray()
        val rest = JSONArray()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (isInfraRule(rule)) merged.put(rule) else rest.put(rule)
        }
        if (needGlobal) {
            merged.put(JSONObject().put("clash_mode", "Global").put("outbound", globalTag))
        }
        if (needDirect) {
            merged.put(JSONObject().put("clash_mode", "Direct").put("outbound", directTag))
        }
        for (i in 0 until rest.length()) merged.put(rest.get(i))
        route.put("rules", merged)
        return true
    }

    private fun clashModesIn(rules: JSONArray?): Set<String> {
        if (rules == null) return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until rules.length()) {
            val mode = rules.optJSONObject(i)?.optString("clash_mode")?.trim().orEmpty()
            if (mode.isNotEmpty()) out += mode
        }
        return out
    }

    private fun isInfraRule(rule: JSONObject): Boolean {
        val action = rule.optString("action").lowercase()
        if (action == "sniff" || action == "resolve" || action == "hijack-dns") return true
        return rule.optString("protocol").equals("dns", true)
    }

    private fun findDirectTag(outs: JSONArray): String {
        for (i in 0 until outs.length()) {
            val item = outs.optJSONObject(i) ?: continue
            if (item.optString("type").equals("direct", true)) {
                val tag = item.optString("tag").trim()
                if (tag.isNotEmpty()) return tag
            }
        }
        return "direct"
    }

    private fun findGlobalTag(root: JSONObject, outs: JSONArray, directTag: String): String {
        val finalTag = root.optJSONObject("route")?.optString("final")?.trim().orEmpty()
        if (finalTag.isNotEmpty() && !finalTag.equals(directTag, true) &&
            !finalTag.equals("block", true) && !finalTag.equals("REJECT", true)
        ) {
            return finalTag
        }
        for (wanted in listOf("selector", "urltest", "url-test")) {
            for (i in 0 until outs.length()) {
                val item = outs.optJSONObject(i) ?: continue
                if (!item.optString("type").equals(wanted, true)) continue
                val tag = item.optString("tag").trim()
                if (tag.isNotEmpty()) return tag
            }
        }
        for (i in 0 until outs.length()) {
            val item = outs.optJSONObject(i) ?: continue
            val tag = item.optString("tag").trim()
            val type = item.optString("type")
            if (tag.isNotEmpty() && !type.equals("direct", true) && !type.equals("block", true) &&
                !type.equals("dns", true)
            ) {
                return tag
            }
        }
        return finalTag.ifBlank { directTag }
    }

    fun heal(content: String): HealResult {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed[0] != '{') return HealResult(content, emptyList())
        if (trimmed.length > ConfigCompat.MAX_CONFIG_CHARS) return HealResult(content, emptyList())
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return HealResult(content, emptyList())
        }
        val notes = apply(root)
        return if (notes.isEmpty()) HealResult(content, emptyList()) else HealResult(root.toString(), notes)
    }

    fun healString(content: String): String = heal(content).content

    private fun toArray(ports: IntArray): JSONArray {
        val a = JSONArray()
        ports.forEach { a.put(it) }
        return a
    }
}
