package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared overlay helpers (WebRTC STUN reject, CN domain list for QUIC).
 * This is not a config rewriter — subscription JSON is left intact.
 */
object ConfigNormalize {

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

    private fun toArray(ports: IntArray): JSONArray {
        val a = JSONArray()
        ports.forEach { a.put(it) }
        return a
    }
}
