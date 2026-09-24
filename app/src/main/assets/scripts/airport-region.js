/**
 * 默认覆写脚本。
 * overlay-revision: 19
 * 配置覆盖开关通过全局 overlay 控制本脚本对应功能，默认全开。
 * 不要在脚本里改开关：到「设置 → 配置覆盖」即可。全程只跑这一套规则。
 * 国内 IP/域名（含 IPv6）先直连，国外走代理。国内 DNS 直连 223.5.5.5。
 * 非国内的 A/AAAA 用 fake-ip，连接不必先等 8.8.8.8。HTTPS/SVCB 仍走节点上的 TCP DNS。
 * ECH 查询由应用在脚本之后改到直连解析，避免节点自己等自己。
 * 手机流量走 TUN（172.19.0.1/30，MTU 1500），不额外绑定本机 mixed 端口。
 * 不丢弃 UDP 443，也不拒绝 HTTPS/SVCB。YouTube 可以走 QUIC，避免只剩 TCP 时缓冲很久。
 * 广告拦截与远控可切到 DIRECT，但节点选择不提供 DIRECT。
 * 叶节点去掉 detour / dialer-proxy，避免订阅把链式带进来。
 * urltest 对齐常见习惯：10 分钟测一次、空闲 30 分钟停测、
 * 不打断已有连接；负载均衡测地区组而不是每个节点。
 * 覆盖原配置的分组与分流，只保留节点。function main(config)。
 */
function main(config) {
  if (!config || typeof config !== "object") return config;
  var ov = (typeof overlay === "object" && overlay) ? overlay : {};
  function on(key) {
    if (!Object.prototype.hasOwnProperty.call(ov, key)) return true;
    var v = ov[key];
    return v !== false && v !== 0 && v !== "false";
  }
  var chinaDirect = on("chinaDirect");
  var adsBlock = on("adsBlock");
  var webrtcProtect = on("webrtcProtect");
  var disableQuic = on("disableQuic");
  var excludeCnQuic = on("excludeCnQuic");
  var disableIpv6 = on("disableIpv6");
  var dnsProtect = on("dnsProtect");
  var strictRoute = on("strictRoute");

  function hasOwn(obj, key) {
    return obj && Object.prototype.hasOwnProperty.call(obj, key);
  }
  function arr(v) {
    if (!v) return [];
    if (Object.prototype.toString.call(v) === "[object Array]") return v;
    return [];
  }
  function tagOf(item) {
    if (!item || typeof item !== "object") return "";
    var t = item.tag || item.name || "";
    return ("" + t).trim();
  }
  function typeOf(item) {
    if (!item || typeof item !== "object") return "";
    return ("" + (item.type || "")).toLowerCase();
  }
  function indexByTag(list) {
    var map = {};
    for (var i = 0; i < list.length; i++) {
      var t = tagOf(list[i]);
      if (t) map[t] = i;
    }
    return map;
  }
  function ensureArray(obj, key) {
    if (!obj[key] || Object.prototype.toString.call(obj[key]) !== "[object Array]") {
      obj[key] = [];
    }
    return obj[key];
  }
  function pushUniqueTag(list, item) {
    var t = tagOf(item);
    if (!t) return;
    for (var i = 0; i < list.length; i++) {
      if (tagOf(list[i]) === t) return;
    }
    list.push(item);
  }
  function existingTag(list, candidates) {
    var map = indexByTag(list);
    for (var i = 0; i < candidates.length; i++) {
      if (hasOwn(map, candidates[i])) return candidates[i];
    }
    return "";
  }
  function looksGroup(t) {
    return t === "selector" || t === "urltest" || t === "url-test" ||
      t === "load-balance" || t === "fallback" || t === "relay" ||
      t === "chain" || t === "direct" || t === "block" || t === "dns" ||
      t === "selector" || t === "urltest";
  }

  var GROUP_TYPES = {
    selector: 1, urltest: 1, "url-test": 1, chain: 1, direct: 1,
    block: 1, dns: 1, "load-balance": 1, fallback: 1, relay: 1
  };

  var REGIONS = [
    { key: "hk", name: "🇭🇰 香港节点", pattern: "🇭🇰|香港|\\bHKG?\\b|hong[\\s_-]*kong" },
    { key: "tw", name: "🇹🇼 台湾节点", pattern: "🇹🇼|台湾|\\bTWN?\\b|taiwan" },
    { key: "jp", name: "🇯🇵 日本节点", pattern: "🇯🇵|日本|\\bJPN?\\b|japan|tokyo|osaka|东京|大阪" },
    { key: "kr", name: "🇰🇷 韩国节点", pattern: "🇰🇷|韩国|\\bKR\\b|korea|seoul|首尔" },
    { key: "sg", name: "🇸🇬 新加坡节点", pattern: "🇸🇬|新加坡|狮城|\\bSGP?\\b|singapore" },
    { key: "us", name: "🇺🇸 美国节点", pattern: "🇺🇸|美国|\\bUSA?\\b|america|united[\\s_-]*states|los[\\s_-]*angeles|洛杉矶|san[\\s_-]*jose|圣何塞" },
    { key: "uk", name: "🇬🇧 英国节点", pattern: "🇬🇧|英国|\\bGB\\b|united[\\s_-]*kingdom|london|伦敦" },
    { key: "de", name: "🇩🇪 德国节点", pattern: "🇩🇪|德国|\\bDE\\b|germany|frankfurt|法兰克福" },
    { key: "nl", name: "🇳🇱 荷兰节点", pattern: "🇳🇱|荷兰|\\bNL\\b|nether?lands|amsterdam|阿姆斯特丹" },
    { key: "my", name: "🇲🇾 马来西亚节点", pattern: "🇲🇾|马来西亚|\\bMY\\b|malaysia|kuala[\\s_-]*lumpur|吉隆坡" },
    { key: "th", name: "🇹🇭 泰国节点", pattern: "🇹🇭|泰国|\\bTH\\b|thailand|bangkok|曼谷" },
    { key: "vn", name: "🇻🇳 越南节点", pattern: "🇻🇳|越南|\\bVN\\b|vietnam|hanoi|河内|ho[\\s_-]*chi[\\s_-]*minh|胡志明" },
    { key: "ph", name: "🇵🇭 菲律宾节点", pattern: "🇵🇭|菲律宾|\\bPH\\b|philippines|manila|马尼拉" },
    { key: "id", name: "🇮🇩 印尼节点", pattern: "🇮🇩|印尼|印度尼西亚|\\bID\\b|indonesia|jakarta|雅加达" },
    { key: "in", name: "🇮🇳 印度节点", pattern: "🇮🇳|印度|\\bIN\\b|india|mumbai|孟买|delhi|德里" },
    { key: "au", name: "🇦🇺 澳大利亚节点", pattern: "🇦🇺|澳大利亚|澳洲|\\bAU\\b|australia|sydney|悉尼|melbourne|墨尔本" },
    { key: "fr", name: "🇫🇷 法国节点", pattern: "🇫🇷|法国|\\bFR\\b|france|paris|巴黎" },
    { key: "ru", name: "🇷🇺 俄罗斯节点", pattern: "🇷🇺|俄罗斯|\\bRU\\b|russia|moscow|莫斯科" },
    { key: "it", name: "🇮🇹 意大利节点", pattern: "🇮🇹|意大利|\\bIT\\b|\\bitaly\\b|rome|罗马" },
    { key: "ca", name: "🇨🇦 加拿大节点", pattern: "🇨🇦|加拿大|\\bCA\\b|canada|toronto|多伦多" },
    { key: "ar", name: "🇦🇷 阿根廷节点", pattern: "🇦🇷|阿根廷|\\bAR\\b|argentina|buenos[\\s_-]*aires|布宜诺斯艾利斯" },
    { key: "br", name: "🇧🇷 巴西节点", pattern: "🇧🇷|巴西|\\bBR\\b|brazil|sao[\\s_-]*paulo|圣保罗" },
    { key: "mx", name: "🇲🇽 墨西哥节点", pattern: "🇲🇽|墨西哥|\\bMX\\b|mexico" },
    { key: "sa", name: "🇸🇦 沙特阿拉伯节点", pattern: "🇸🇦|沙特阿拉伯|沙特|\\bSA\\b|saudi[\\s_-]*arabia" },
    { key: "za", name: "🇿🇦 南非节点", pattern: "🇿🇦|南非|\\bZA\\b|south[\\s_-]*africa|johannesburg|约翰内斯堡" },
    { key: "tr", name: "🇹🇷 土耳其节点", pattern: "🇹🇷|土耳其|\\bTR\\b|turkey|istanbul|伊斯坦布尔" },
    { key: "bn", name: "🇧🇳 文莱节点", pattern: "🇧🇳|文莱|\\bBN\\b|brunei" },
    { key: "kh", name: "🇰🇭 柬埔寨节点", pattern: "🇰🇭|柬埔寨|\\bKH\\b|cambodia|phnom[\\s_-]*penh|金边" },
    { key: "la", name: "🇱🇦 老挝节点", pattern: "🇱🇦|老挝|\\bLA\\b|\\blaos\\b|vientiane|万象" },
    { key: "mm", name: "🇲🇲 缅甸节点", pattern: "🇲🇲|缅甸|\\bMM\\b|myanmar|yangon|仰光" },
    { key: "at", name: "🇦🇹 奥地利节点", pattern: "🇦🇹|奥地利|\\bAT\\b|austria|vienna|维也纳" },
    { key: "be", name: "🇧🇪 比利时节点", pattern: "🇧🇪|比利时|\\bBE\\b|belgium|brussels|布鲁塞尔" },
    { key: "bg", name: "🇧🇬 保加利亚节点", pattern: "🇧🇬|保加利亚|\\bBG\\b|bulgaria|sofia|索非亚" },
    { key: "hr", name: "🇭🇷 克罗地亚节点", pattern: "🇭🇷|克罗地亚|\\bHR\\b|croatia" },
    { key: "cy", name: "🇨🇾 塞浦路斯节点", pattern: "🇨🇾|塞浦路斯|\\bCY\\b|cyprus" },
    { key: "cz", name: "🇨🇿 捷克节点", pattern: "🇨🇿|捷克|捷克共和国|\\bCZ\\b|czech|prague|布拉格" },
    { key: "dk", name: "🇩🇰 丹麦节点", pattern: "🇩🇰|丹麦|\\bDK\\b|denmark|copenhagen|哥本哈根" },
    { key: "ee", name: "🇪🇪 爱沙尼亚节点", pattern: "🇪🇪|爱沙尼亚|\\bEE\\b|estonia" },
    { key: "fi", name: "🇫🇮 芬兰节点", pattern: "🇫🇮|芬兰|\\bFI\\b|finland|helsinki|赫尔辛基" },
    { key: "gr", name: "🇬🇷 希腊节点", pattern: "🇬🇷|希腊|\\bGR\\b|greece|athens|雅典" },
    { key: "hu", name: "🇭🇺 匈牙利节点", pattern: "🇭🇺|匈牙利|\\bHU\\b|hungary|budapest|布达佩斯" },
    { key: "ie", name: "🇮🇪 爱尔兰节点", pattern: "🇮🇪|爱尔兰|\\bIE\\b|ireland|dublin|都柏林" },
    { key: "lv", name: "🇱🇻 拉脱维亚节点", pattern: "🇱🇻|拉脱维亚|\\bLV\\b|latvia" },
    { key: "lt", name: "🇱🇹 立陶宛节点", pattern: "🇱🇹|立陶宛|\\bLT\\b|lithuania" },
    { key: "lu", name: "🇱🇺 卢森堡节点", pattern: "🇱🇺|卢森堡|\\bLU\\b|luxembourg" },
    { key: "mt", name: "🇲🇹 马耳他节点", pattern: "🇲🇹|马耳他|\\bMT\\b|malta" },
    { key: "pl", name: "🇵🇱 波兰节点", pattern: "🇵🇱|波兰|\\bPL\\b|poland|warsaw|华沙" },
    { key: "pt", name: "🇵🇹 葡萄牙节点", pattern: "🇵🇹|葡萄牙|\\bPT\\b|portugal|lisbon|里斯本" },
    { key: "ro", name: "🇷🇴 罗马尼亚节点", pattern: "🇷🇴|罗马尼亚|\\bRO\\b|romania|bucharest|布加勒斯特" },
    { key: "sk", name: "🇸🇰 斯洛伐克节点", pattern: "🇸🇰|斯洛伐克|\\bSK\\b|slovakia" },
    { key: "si", name: "🇸🇮 斯洛文尼亚节点", pattern: "🇸🇮|斯洛文尼亚|\\bSI\\b|slovenia" },
    { key: "es", name: "🇪🇸 西班牙节点", pattern: "🇪🇸|西班牙|\\bES\\b|\\bspain\\b|madrid|马德里" },
    { key: "se", name: "🇸🇪 瑞典节点", pattern: "🇸🇪|瑞典|\\bSE\\b|sweden|stockholm|斯德哥尔摩" },
    { key: "dz", name: "🇩🇿 阿尔及利亚节点", pattern: "🇩🇿|阿尔及利亚|\\bDZ\\b|algeria" },
    { key: "ao", name: "🇦🇴 安哥拉节点", pattern: "🇦🇴|安哥拉|\\bAO\\b|angola" },
    { key: "bj", name: "🇧🇯 贝宁节点", pattern: "🇧🇯|贝宁|\\bBJ\\b|benin" },
    { key: "bw", name: "🇧🇼 博茨瓦纳节点", pattern: "🇧🇼|博茨瓦纳|\\bBW\\b|botswana" },
    { key: "bf", name: "🇧🇫 布基纳法索节点", pattern: "🇧🇫|布基纳法索|\\bBF\\b|burkina[\\s_-]*faso" },
    { key: "bi", name: "🇧🇮 布隆迪节点", pattern: "🇧🇮|布隆迪|\\bBI\\b|burundi" },
    { key: "cv", name: "🇨🇻 佛得角节点", pattern: "🇨🇻|佛得角|\\bCV\\b|cabo[\\s_-]*verde|cape[\\s_-]*verde" },
    { key: "cm", name: "🇨🇲 喀麦隆节点", pattern: "🇨🇲|喀麦隆|\\bCM\\b|cameroon" },
    { key: "cf", name: "🇨🇫 中非共和国节点", pattern: "🇨🇫|中非共和国|中非|central[\\s_-]*african" },
    { key: "td", name: "🇹🇩 乍得节点", pattern: "🇹🇩|乍得|\\bTD\\b|\\bchad\\b" },
    { key: "km", name: "🇰🇲 科摩罗节点", pattern: "🇰🇲|科摩罗|\\bKM\\b|comoros" },
    { key: "cg", name: "🇨🇬 刚果共和国节点", pattern: "🇨🇬|刚果共和国|刚果（布）|\\bCG\\b|\\bcongo\\b" },
    { key: "cd", name: "🇨🇩 刚果民主共和国节点", pattern: "🇨🇩|刚果民主共和国|刚果（金）|民主刚果|\\bCD\\b|dr[\\s_-]*congo|democratic[\\s_-]*republic[\\s_-]*of[\\s_-]*the[\\s_-]*congo" },
    { key: "ci", name: "🇨🇮 科特迪瓦节点", pattern: "🇨🇮|科特迪瓦|象牙海岸|\\bCI\\b|cote[\\s_-]*d.ivoire|ivory[\\s_-]*coast" },
    { key: "dj", name: "🇩🇯 吉布提节点", pattern: "🇩🇯|吉布提|\\bDJ\\b|djibouti" },
    { key: "eg", name: "🇪🇬 埃及节点", pattern: "🇪🇬|埃及|\\bEG\\b|egypt|cairo|开罗" },
    { key: "gq", name: "🇬🇶 赤道几内亚节点", pattern: "🇬🇶|赤道几内亚|\\bGQ\\b|equatorial[\\s_-]*guinea" },
    { key: "er", name: "🇪🇷 厄立特里亚节点", pattern: "🇪🇷|厄立特里亚|\\bER\\b|eritrea" },
    { key: "sz", name: "🇸🇿 斯威士兰节点", pattern: "🇸🇿|斯威士兰|埃斯瓦蒂尼|\\bSZ\\b|eswatini|swaziland" },
    { key: "et", name: "🇪🇹 埃塞俄比亚节点", pattern: "🇪🇹|埃塞俄比亚|\\bET\\b|ethiopia" },
    { key: "ga", name: "🇬🇦 加蓬节点", pattern: "🇬🇦|加蓬|\\bGA\\b|\\bgabon\\b" },
    { key: "gm", name: "🇬🇲 冈比亚节点", pattern: "🇬🇲|冈比亚|\\bGM\\b|gambia" },
    { key: "gh", name: "🇬🇭 加纳节点", pattern: "🇬🇭|加纳|\\bGH\\b|\\bghana\\b" },
    { key: "gn", name: "🇬🇳 几内亚节点", pattern: "🇬🇳|几内亚|\\bGN\\b|\\bguinea\\b" },
    { key: "gw", name: "🇬🇼 几内亚比绍节点", pattern: "🇬🇼|几内亚比绍|\\bGW\\b|guinea-bissau|guinea[\\s_-]*bissau" },
    { key: "ke", name: "🇰🇪 肯尼亚节点", pattern: "🇰🇪|肯尼亚|\\bKE\\b|kenya|nairobi|内罗毕" },
    { key: "ls", name: "🇱🇸 莱索托节点", pattern: "🇱🇸|莱索托|\\bLS\\b|lesotho" },
    { key: "lr", name: "🇱🇷 利比里亚节点", pattern: "🇱🇷|利比里亚|\\bLR\\b|liberia" },
    { key: "ly", name: "🇱🇾 利比亚节点", pattern: "🇱🇾|利比亚|\\bLY\\b|\\blibya\\b" },
    { key: "mg", name: "🇲🇬 马达加斯加节点", pattern: "🇲🇬|马达加斯加|\\bMG\\b|madagascar" },
    { key: "mw", name: "🇲🇼 马拉维节点", pattern: "🇲🇼|马拉维|\\bMW\\b|malawi" },
    { key: "ml", name: "🇲🇱 马里节点", pattern: "🇲🇱|马里|\\bML\\b|\\bmali\\b" },
    { key: "mr", name: "🇲🇷 毛里塔尼亚节点", pattern: "🇲🇷|毛里塔尼亚|\\bMR\\b|mauritania" },
    { key: "mu", name: "🇲🇺 毛里求斯节点", pattern: "🇲🇺|毛里求斯|\\bMU\\b|mauritius" },
    { key: "ma", name: "🇲🇦 摩洛哥节点", pattern: "🇲🇦|摩洛哥|\\bMA\\b|morocco|casablanca|卡萨布兰卡" },
    { key: "mz", name: "🇲🇿 莫桑比克节点", pattern: "🇲🇿|莫桑比克|\\bMZ\\b|mozambique" },
    { key: "na", name: "🇳🇦 纳米比亚节点", pattern: "🇳🇦|纳米比亚|\\bNA\\b|\\bnamibia\\b" },
    { key: "ne", name: "🇳🇪 尼日尔节点", pattern: "🇳🇪|尼日尔|\\bNE\\b|\\bniger\\b" },
    { key: "ng", name: "🇳🇬 尼日利亚节点", pattern: "🇳🇬|尼日利亚|\\bNG\\b|nigeria|lagos|拉各斯" },
    { key: "rw", name: "🇷🇼 卢旺达节点", pattern: "🇷🇼|卢旺达|\\bRW\\b|rwanda" },
    { key: "st", name: "🇸🇹 圣多美和普林西比节点", pattern: "🇸🇹|圣多美和普林西比|\\bST\\b|sao[\\s_-]*tome" },
    { key: "sn", name: "🇸🇳 塞内加尔节点", pattern: "🇸🇳|塞内加尔|\\bSN\\b|senegal" },
    { key: "sc", name: "🇸🇨 塞舌尔节点", pattern: "🇸🇨|塞舌尔|\\bSC\\b|seychelles" },
    { key: "sl", name: "🇸🇱 塞拉利昂节点", pattern: "🇸🇱|塞拉利昂|\\bSL\\b|sierra[\\s_-]*leone" },
    { key: "so", name: "🇸🇴 索马里节点", pattern: "🇸🇴|索马里|\\bSO\\b|somalia" },
    { key: "ss", name: "🇸🇸 南苏丹节点", pattern: "🇸🇸|南苏丹|\\bSS\\b|south[\\s_-]*sudan" },
    { key: "sd", name: "🇸🇩 苏丹节点", pattern: "🇸🇩|苏丹|\\bSD\\b|\\bsudan\\b" },
    { key: "tz", name: "🇹🇿 坦桑尼亚节点", pattern: "🇹🇿|坦桑尼亚|\\bTZ\\b|tanzania" },
    { key: "tg", name: "🇹🇬 多哥节点", pattern: "🇹🇬|多哥|\\bTG\\b|\\btogo\\b" },
    { key: "tn", name: "🇹🇳 突尼斯节点", pattern: "🇹🇳|突尼斯|\\bTN\\b|tunisia" },
    { key: "ug", name: "🇺🇬 乌干达节点", pattern: "🇺🇬|乌干达|\\bUG\\b|uganda" },
    { key: "zm", name: "🇿🇲 赞比亚节点", pattern: "🇿🇲|赞比亚|\\bZM\\b|zambia" },
    { key: "zw", name: "🇿🇼 津巴布韦节点", pattern: "🇿🇼|津巴布韦|\\bZW\\b|zimbabwe" }
    ];

  var CN_DOMAINS = [
    "alipay.com", "alipayobjects.com", "antpay.com", "taobao.com", "tmall.com", "jd.com", "jdpay.com",
    "pinduoduo.com", "pddpic.com", "meituan.com", "dianping.com", "ele.me", "amap.com", "autonavi.com",
    "baidu.com", "weixin.qq.com", "weixin.com", "wx.qq.com", "wxs.qq.com", "qq.com", "tencent.com",
    "servicewechat.com", "work.weixin.qq.com", "meeting.tencent.com", "weixinbridge.com", "url.cn",
    "long.weixin.qq.com", "short.weixin.qq.com", "sz.weixin.qq.com", "szshort.weixin.qq.com",
    "szlong.weixin.qq.com", "szminorshort.weixin.qq.com", "wechat.com", "wechatpay.cn",
    "aliyun.com", "aliyuncs.com", "alicdn.com", "tbcdn.cn", "myqcloud.com", "qpic.cn", "qlogo.cn",
    "gtimg.com", "gdtimg.com", "weibo.com", "zhihu.com", "12306.cn", "railway12306.cn", "chinatax.gov.cn",
    "gjzwfw.gov.cn", "unionpay.com", "unionpaysecure.com", "chinapay.com", "yeepay.com", "95516.com",
    "icbc.com.cn", "ccb.com", "boc.cn", "bankofchina.com", "abchina.com", "abchina.com.cn",
    "cmbchina.com", "cmbi.com.cn", "bankcomm.com", "psbc.com", "spdb.com.cn", "cib.com.cn", "cmbc.com.cn",
    "pingan.com", "pingan.com.cn", "cgbchina.com.cn", "cebbank.com", "citicbank.com", "ecitic.com",
    "hxb.com.cn", "netsunion.org.cn", "tenpay.com", "99bill.com", "eastmoney.com", "htsc.com.cn", "gtja.com",
    "dingtalk.com", "feishu.cn", "xiaohongshu.com", "kuaishou.com", "bilibili.com", "bilivideo.cn",
    "163.com", "126.net", "netease.com", "ctrip.com", "qunar.com", "sf-express.com",
    "xiaojukeji.com", "didichuxing.com", "xuexi.cn", "chsi.com.cn",
    "umeng.com", "umengcloud.com", "geetest.com", "tongdun.net", "tongduncdn.com",
    "rongcloud.cn", "rongcloud.com", "jpush.cn", "jpush.io", "jiguang.cn", "getui.com", "getui.net", "gepush.com",
    "samsunghealth.com", "samsungosp.com", "mi.com", "xiaomi.com", "miui.com", "micloud.com",
    "huawei.com", "hicloud.com", "vivo.com", "oppo.com", "meizu.com",
    "iqiyi.com", "youku.com", "douyin.com", "toutiao.com", "bytedance.com",
    "deepseek.com", "deepseek.ai", "moonshot.cn", "zhipuai.cn", "iflytek.com",
    "kimichat.com", "chatglm.cn", "baichuan-ai.com", "sensetime.com", "minimax.chat", "stepfun.com",
    "1688.com", "tencent-cloud.com", "byteimg.com", "tosv.com", "iesdouyin.com",
    "pstatp.com", "snssdk.com", "volccdn.com", "ixigua.com", "feishu.net", "volces.com",
    "bdstatic.com", "bdimg.com", "bcebos.com", "iqiyipic.com", "baidubce.com",
    "mi-img.com", "miwifi.com", "xiaomiev.com", "huaweicloud.com", "vmall.com", "honor.com",
    "vivoglobal.com", "oppomobile.com", "samsung.com.cn", "samsungapps.com",
    "samsungcloud.com", "samsungknox.com", "samsungdm.com",
    "aegis.qq.com", "ishumei.com", "riskradar.net", "trustdevice.net",
    "dingxiangyun.com", "dingxiangyun.cn", "rong360.com", "gfbazc.com", "fzuol.com",
    "yzf.com.cn", "cpic.com.cn", "zhongan.com", "fuwu.nhsa.gov.cn",
    "midea.com", "smartmidea.net", "haier.net", "haier.com", "hisense.com",
    "yeelight.com", "aqara.com", "tuya.com", "tuyaus.com", "tcl.com",
    "msftconnecttest.com", "msftncsi.com", "captive.apple.com",
    "router.asus.com", "tplogin.cn", "hiwifi.com", "phicomm.me",
    "local", "lan", "home.arpa"
  ];

  var outbounds = ensureArray(config, "outbounds");
  var leafTags = [];
  var groupTags = {};
  for (var oi = 0; oi < outbounds.length; oi++) {
    var ob = outbounds[oi];
    var tg = tagOf(ob);
    var ty = typeOf(ob);
    if (!tg) continue;
    if (hasOwn(GROUP_TYPES, ty) || tg.toLowerCase() === "direct" || tg.toLowerCase() === "block") {
      groupTags[tg] = ty;
    } else {
      leafTags.push(tg);
    }
  }
  var endpoints = ensureArray(config, "endpoints");
  for (var ei = 0; ei < endpoints.length; ei++) {
    var ep = endpoints[ei];
    var etg = tagOf(ep);
    var ety = typeOf(ep);
    if (!etg) continue;
    if (ety === "wireguard" || ety === "tailscale") leafTags.push(etg);
  }

  for (var si = 0; si < outbounds.length; si++) {
    var sob = outbounds[si];
    if (!sob || typeof sob !== "object") continue;
    var sty = typeOf(sob);
    if (hasOwn(GROUP_TYPES, sty)) continue;
    if (sob.detour) delete sob.detour;
    if (sob["dialer-proxy"] != null) delete sob["dialer-proxy"];
    if (sob["proxy-dialer"] != null) delete sob["proxy-dialer"];
    if (sty && sty !== "direct" && sty !== "block" && sty !== "dns" && sty !== "tun") {
      sob.tcp_keep_alive = "60s";
      if (!sob.tcp_keep_alive_interval) sob.tcp_keep_alive_interval = "60s";
    }
  }

  var REPLACE_GROUP_TYPES = {
    selector: 1, urltest: 1, "url-test": 1,
    "load-balance": 1, fallback: 1, relay: 1, chain: 1
  };
  var rebuilt = [];
  for (var rj0 = 0; rj0 < outbounds.length; rj0++) {
    if (hasOwn(REPLACE_GROUP_TYPES, typeOf(outbounds[rj0]))) continue;
    rebuilt.push(outbounds[rj0]);
  }
  outbounds = rebuilt;
  groupTags = {};
  for (var gj = 0; gj < outbounds.length; gj++) {
    var gob = outbounds[gj];
    var gtg = tagOf(gob);
    var gty = typeOf(gob);
    if (!gtg) continue;
    if (hasOwn(GROUP_TYPES, gty) || gtg.toLowerCase() === "direct" || gtg.toLowerCase() === "block") {
      groupTags[gtg] = gty;
    }
  }

  function isCloudflareName(name) {
    return /cloudflare|cloudflera|\bwarp\b|wg[\s_-]*cf|1\.1\.1\.1/i.test(name);
  }
  function matchRegion(name) {
    var hits = [];
    var skipCf = isCloudflareName(name);
    for (var ri = 0; ri < REGIONS.length; ri++) {
      var r = REGIONS[ri];
      if (skipCf && r.key === "cf") continue;
      try {
        var re = new RegExp(r.pattern, "i");
        if (re.test(name)) hits.push(r);
      } catch (e) {}
    }
    return hits;
  }

  var regionMembers = {};
  var otherMembers = [];
  function isAnnouncement(name) {
    try {
      return /群|返利|官网|客服|网站|网址|订阅|流量|到期|机场|过期|工单|通知|倒卖|防失联|expire|traffic/i.test(name);
    } catch (e) {
      return false;
    }
  }
  for (var li = 0; li < leafTags.length; li++) {
    var name = leafTags[li];
    if (isAnnouncement(name)) continue;
    var matched = matchRegion(name);
    if (matched.length > 0) {
      for (var mi = 0; mi < matched.length; mi++) {
        var key = matched[mi].name;
        if (!regionMembers[key]) regionMembers[key] = [];
        regionMembers[key].push(name);
      }
    } else {
      otherMembers.push(name);
    }
  }

  var OTHER_NAME = "其他地区";
  var AUTO_NAME = "♻️ 自动选择";
  var LB_NAME = "⚖️ 负载均衡";
  var FAILOVER_NAME = "🛡️ 故障转移";
  var SELECT_NAME = "🔰 节点选择";
  var regionNames = [];
  var activeRegions = [];
  for (var rj = 0; rj < REGIONS.length; rj++) {
    var rn = REGIONS[rj].name;
    if (regionMembers[rn] && regionMembers[rn].length > 0) {
      activeRegions.push(REGIONS[rj]);
      regionNames.push(rn);
    }
  }
  if (otherMembers.length > 0) regionNames.push(OTHER_NAME);

  function makeUrltest(tag, members) {
    return {
      type: "urltest",
      tag: tag,
      outbounds: members.slice(0),
      url: "https://www.gstatic.com/generate_204",
      interval: "10m",
      tolerance: 150,
      idle_timeout: "30m",
      interrupt_exist_connections: false
    };
  }
  function makeSelector(tag, members, defaultTag) {
    var g = {
      type: "selector",
      tag: tag,
      outbounds: members.slice(0),
      interrupt_exist_connections: false
    };
    if (defaultTag) g["default"] = defaultTag;
    return g;
  }

  function outboundByTag(tag) {
    var map = indexByTag(outbounds);
    if (!hasOwn(map, tag)) return null;
    return outbounds[map[tag]];
  }
  var directTag = "";
  var directCand = existingTag(outbounds, ["direct", "DIRECT"]);
  var directItem = directCand ? outboundByTag(directCand) : null;
  if (directItem && typeOf(directItem) === "direct") {
    directTag = directCand;
    if (directItem.detour) delete directItem.detour;
  } else {
    var cleanDirect = (directCand && directItem && typeOf(directItem) !== "direct") ? "angela-direct" : "direct";
    if (!existingTag(outbounds, [cleanDirect])) {
      pushUniqueTag(outbounds, { type: "direct", tag: cleanDirect });
    }
    directTag = cleanDirect;
  }
  groupTags[directTag] = "direct";
  var bypass = outboundByTag(directTag);
  if (bypass && typeOf(bypass) === "direct" && !bypass.connect_timeout) {
    bypass.connect_timeout = "8s";
  }
  var dropTag = "REJECT-DROP";
  var rejectTag = "REJECT";
  function makeBlackhole(tag) {
    return {
      type: "socks",
      tag: tag,
      server: "127.0.0.1",
      server_port: 9
    };
  }
  if (!existingTag(outbounds, [dropTag])) {
    pushUniqueTag(outbounds, makeBlackhole(dropTag));
  }
  if (!existingTag(outbounds, [rejectTag])) {
    pushUniqueTag(outbounds, makeBlackhole(rejectTag));
  }

  var regionGroups = [];
  for (var ak = 0; ak < activeRegions.length; ak++) {
    var ar = activeRegions[ak];
    var members = regionMembers[ar.name];
    if (!members || members.length === 0) continue;
    regionGroups.push(makeUrltest(ar.name, members));
    groupTags[ar.name] = "urltest";
  }
  if (otherMembers.length > 0) {
    regionGroups.push(makeUrltest(OTHER_NAME, otherMembers));
    groupTags[OTHER_NAME] = "urltest";
  }

  var autoMembers = regionNames.slice(0);
  if (autoMembers.length === 0) autoMembers = leafTags.slice(0);
  var autoGroup = null;
  if (autoMembers.length > 0) {
    autoGroup = makeUrltest(AUTO_NAME, autoMembers);
    groupTags[AUTO_NAME] = "urltest";
  }
  var lbGroup = null;
  if (autoMembers.length > 0) {
    lbGroup = makeUrltest(LB_NAME, autoMembers);
    groupTags[LB_NAME] = "urltest";
  }
  var failoverGroup = null;
  if (autoMembers.length > 0) {
    failoverGroup = makeUrltest(FAILOVER_NAME, autoMembers);
    groupTags[FAILOVER_NAME] = "urltest";
  }
  var selectMembers = [];
  if (autoGroup) selectMembers.push(AUTO_NAME);
  if (lbGroup) selectMembers.push(LB_NAME);
  if (failoverGroup) selectMembers.push(FAILOVER_NAME);
  for (var sn = 0; sn < regionNames.length; sn++) selectMembers.push(regionNames[sn]);
  var selectGroup = null;
  if (selectMembers.length > 0) {
    selectGroup = makeSelector(SELECT_NAME, selectMembers);
    groupTags[SELECT_NAME] = "selector";
  }

  var regionNamesNoHK = [];
  for (var nh = 0; nh < regionNames.length; nh++) {
    if (regionNames[nh] !== "🇭🇰 香港节点" && regionNames[nh] !== "🇹🇼 台湾节点") {
      regionNamesNoHK.push(regionNames[nh]);
    }
  }
  function serviceMembers(extraFirst, noHK) {
    var list = [];
    if (extraFirst) {
      for (var i = 0; i < extraFirst.length; i++) list.push(extraFirst[i]);
    }
    var src = noHK ? regionNamesNoHK : regionNames;
    for (var j = 0; j < src.length; j++) list.push(src[j]);
    return list;
  }
  var serviceGroups = [];
  function addService(tag, members) {
    if (!members || members.length === 0) return existingTag(outbounds, [SELECT_NAME, AUTO_NAME]) || tag;
    serviceGroups.push(makeSelector(tag, members));
    groupTags[tag] = "selector";
    return tag;
  }

  var pickSelect = SELECT_NAME;
  if (!selectGroup && autoGroup) pickSelect = AUTO_NAME;
  var adsTag = "🛑 广告拦截";
  var adsGroup = makeSelector(adsTag, [dropTag, rejectTag, directTag], dropTag);
  groupTags[adsTag] = "selector";
  var aiTag = addService("🤖 AI服务", serviceMembers([pickSelect, AUTO_NAME], true));
  var mediaTag = addService("📺 Media", serviceMembers([pickSelect, AUTO_NAME], false));
  addService("📺 YouTube", serviceMembers([pickSelect, AUTO_NAME], false));
  addService("🔍 Google", serviceMembers([pickSelect, AUTO_NAME], false));
  addService("📲 Telegram", serviceMembers([pickSelect, AUTO_NAME], false));
  addService("🪟 Microsoft", serviceMembers([pickSelect, AUTO_NAME], false));
  addService("🍎 Apple", serviceMembers([pickSelect, AUTO_NAME], false));
  addService("🎮 Steam", serviceMembers([pickSelect, AUTO_NAME], false));
  addService("📱 TikTok", serviceMembers([pickSelect, AUTO_NAME], false));
  addService("🐦 Twitter", serviceMembers([pickSelect, AUTO_NAME], false));
  addService("🎵 Spotify", serviceMembers([pickSelect, AUTO_NAME], false));
  var globalTag = addService("🌍 国外服务", serviceMembers([pickSelect, AUTO_NAME], false));
  var finalTag = addService("🐟 漏网之鱼", serviceMembers([pickSelect, AUTO_NAME], false));
  var remoteTag = "🔧 远控工具";
  var remoteGroup = makeSelector(remoteTag, [dropTag, globalTag, directTag], dropTag);
  groupTags[remoteTag] = "selector";

  var ordered = [];
  if (selectGroup) ordered.push(selectGroup);
  if (autoGroup) ordered.push(autoGroup);
  if (lbGroup) ordered.push(lbGroup);
  if (failoverGroup) ordered.push(failoverGroup);
  ordered.push(adsGroup);
  for (var sg = 0; sg < serviceGroups.length; sg++) ordered.push(serviceGroups[sg]);
  ordered.push(remoteGroup);
  for (var rg = 0; rg < regionGroups.length; rg++) ordered.push(regionGroups[rg]);
  for (var og = 0; og < ordered.length; og++) pushUniqueTag(outbounds, ordered[og]);
  config.outbounds = outbounds;

  var RULE_SETS = [
    { tag: "geosite-category-ads-all", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-category-ads-all.srs" },
    { tag: "geosite-category-ai-!cn", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-category-ai-!cn.srs" },
    { tag: "geosite-openai", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-openai.srs" },
    { tag: "geosite-bilibili", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-bilibili.srs" },
    { tag: "geosite-geolocation-cn", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-geolocation-cn.srs" },
    { tag: "geosite-geolocation-!cn", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-geolocation-!cn.srs" },
    { tag: "geosite-cn", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs" },
    { tag: "geosite-youtube", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-youtube.srs" },
    { tag: "geosite-netflix", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-netflix.srs" },
    { tag: "geosite-hulu", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-hulu.srs" },
    { tag: "geosite-disney", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-disney.srs" },
    { tag: "geosite-hbo", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-hbo.srs" },
    { tag: "geosite-amazon", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-amazon.srs" },
    { tag: "geosite-bahamut", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-bahamut.srs" },
    { tag: "geosite-spotify", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-spotify.srs" },
    { tag: "geosite-tiktok", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-tiktok.srs" },
    { tag: "geosite-abema", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-abema.srs" },
    { tag: "geosite-bbc", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-bbc.srs" },
    { tag: "geosite-google", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-google.srs" },
    { tag: "geosite-github", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-github.srs" },
    { tag: "geosite-gitlab", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-gitlab.srs" },
    { tag: "geosite-apple", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-apple.srs" },
    { tag: "geosite-icloud", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-icloud.srs" },
    { tag: "geosite-microsoft", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-microsoft.srs" },
    { tag: "geosite-microsoft@cn", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-microsoft@cn.srs" },
    { tag: "geosite-facebook", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-facebook.srs" },
    { tag: "geosite-instagram", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-instagram.srs" },
    { tag: "geosite-twitter", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-twitter.srs" },
    { tag: "geosite-linkedin", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-linkedin.srs" },
    { tag: "geosite-discord", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-discord.srs" },
    { tag: "geosite-snap", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-snap.srs" },
    { tag: "geosite-telegram", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-telegram.srs" },
    { tag: "geosite-steam", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-steam.srs" },
    { tag: "geosite-epicgames", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-epicgames.srs" },
    { tag: "geosite-ea", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-ea.srs" },
    { tag: "geosite-ubisoft", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-ubisoft.srs" },
    { tag: "geosite-blizzard", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-blizzard.srs" },
    { tag: "geosite-steam@cn", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-steam@cn.srs" },
    { tag: "geosite-category-games@cn", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-category-games@cn.srs" },
    { tag: "geosite-paypal", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-paypal.srs" },
    { tag: "geosite-aws", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-aws.srs" },
    { tag: "geosite-azure", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-azure.srs" },
    { tag: "geosite-dropbox", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-dropbox.srs" },
    { tag: "geosite-onedrive", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-onedrive.srs" },
    { tag: "geosite-category-scholar-!cn", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-category-scholar-!cn.srs" },
    { tag: "geoip-cn", url: "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs" }
  ];

  if (!config.route || typeof config.route !== "object") config.route = {};
  var route = config.route;
  var oldSets = ensureArray(route, "rule_set");
  var ruleSets = [];
  var haveSet = {};
  for (var rsi = 0; rsi < oldSets.length; rsi++) {
    var oldSet = oldSets[rsi];
    var rst = tagOf(oldSet);
    var rty = typeOf(oldSet);
    if (!rst) continue;
    if (rty === "local" || rty === "inline") {
      ruleSets.push(oldSet);
      haveSet[rst] = 1;
    }
  }
  for (var rsk = 0; rsk < RULE_SETS.length; rsk++) {
    var rs = RULE_SETS[rsk];
    if (haveSet[rs.tag]) continue;
    ruleSets.push({
      tag: rs.tag,
      type: "remote",
      format: "binary",
      url: rs.url,
      http_client: "http-direct"
    });
    haveSet[rs.tag] = 1;
  }
  route.rule_set = ruleSets;
  config.http_clients = [{ tag: "http-direct" }];
  route.default_http_client = "http-direct";

  function mapTarget(name) {
    var TARGET_MAP = {
      "AI服务": aiTag,
      "🤖 AI服务": aiTag,
      "国外服务": globalTag,
      "🌍 国外服务": globalTag,
      "流媒体": mediaTag,
      "📺 Media": mediaTag,
      "漏网之鱼": finalTag,
      "🐟 漏网之鱼": finalTag,
      "远控工具": remoteTag,
      "🔧 远控工具": remoteTag,
      "自动选择": AUTO_NAME,
      "♻️ 自动选择": AUTO_NAME,
      "负载均衡": LB_NAME,
      "⚖️ 负载均衡": LB_NAME,
      "故障转移": FAILOVER_NAME,
      "🛡️ 故障转移": FAILOVER_NAME,
      "节点选择": SELECT_NAME,
      "🔰 节点选择": SELECT_NAME
    };
    if (TARGET_MAP[name]) {
      var mapped = TARGET_MAP[name];
      if (existingTag(outbounds, [mapped])) return mapped;
    }
    if (existingTag(outbounds, [name])) return name;
    return existingTag(outbounds, [SELECT_NAME, AUTO_NAME, finalTag]) || name;
  }

  function hasRuleSet(tag) { return !!haveSet[tag]; }
  function rule(rsTag, outbound, extra) {
    if (!hasRuleSet(rsTag)) return null;
    var item = { rule_set: rsTag, outbound: outbound };
    if (extra) {
      for (var ek in extra) {
        if (hasOwn(extra, ek)) item[ek] = extra[ek];
      }
    }
    return item;
  }

  var googleTag = existingTag(outbounds, ["🔍 Google", pickSelect]) || pickSelect;
  var youtubeTag = existingTag(outbounds, ["📺 YouTube", mediaTag]) || mapTarget("📺 Media");
  var mediaOut = mapTarget("📺 Media");
  var steamTag = existingTag(outbounds, ["🎮 Steam", pickSelect]) || pickSelect;
  var appleTag = existingTag(outbounds, ["🍎 Apple", pickSelect]) || pickSelect;
  var aiOut = mapTarget("🤖 AI服务");

  var prepend = [];
  function addRule(item) { if (item) prepend.push(item); }

  addRule({ clash_mode: "Global", outbound: pickSelect });
  addRule({ clash_mode: "Direct", outbound: directTag });

  if (disableIpv6) {
    addRule({ ip_version: 6, action: "reject" });
  }
  if (webrtcProtect) {
    addRule({
      network: ["udp", "tcp"],
      port_range: "3478:3481",
      action: "reject",
      method: "drop"
    });
    addRule({
      network: ["udp", "tcp"],
      port_range: "5349:5355",
      action: "reject",
      method: "drop"
    });
    addRule({
      network: ["udp", "tcp"],
      port_range: "19302:19310",
      action: "reject",
      method: "drop"
    });
    addRule({
      domain_regex: [
        "^(stun|turn|stuns|turns)\\.",
        ".*[-.]stun[-.].*",
        ".*[-.]turn[-.].*",
        ".*[-.]stuns[-.].*",
        ".*[-.]turns[-.].*"
      ],
      action: "reject",
      method: "drop"
    });
  }
  if (chinaDirect) {
    addRule({ ip_is_private: true, outbound: directTag });
    addRule(rule("geoip-cn", directTag));
    addRule({
      ip_cidr: [
        "fe80::/10", "fc00::/7", "::1/128",
        "101.226.0.0/16", "140.207.0.0/16",
        "52.80.0.0/16", "54.223.0.0/16",
        "223.5.5.5/32", "223.6.6.6/32", "1.12.12.12/32", "120.53.53.53/32",
        "114.114.114.114/32", "114.114.115.115/32",
        "180.76.76.76/32", "119.29.29.29/32",
        "2400:3200::1/128", "2400:3200:baba::1/128",
        "2402:4e00::/128", "2400:da00::6666/128",
        "240c::6666/128", "240c::6644/128"
      ],
      outbound: directTag
    });
    addRule({ ip_cidr: ["ff00::/8"], action: "reject", method: "drop" });
    addRule({ domain_suffix: CN_DOMAINS, outbound: directTag });
    addRule({ domain: ["connectivitycheck.gstatic.com"], outbound: directTag });
    addRule(rule("geosite-microsoft@cn", directTag));
    addRule(rule("geosite-steam@cn", directTag));
    addRule(rule("geosite-category-games@cn", directTag));
    addRule(rule("geosite-bilibili", directTag));
    addRule(rule("geosite-geolocation-cn", directTag));
    addRule(rule("geosite-cn", directTag));
    addRule({
      package_name: [
        "com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay",
        "com.chinatelecom.bestpayclient", "com.MobileTicket", "cn.gov.tax.its",
        "com.icbc.androidclient", "com.chinamworld.main", "com.chinamworld.bocmbci",
        "com.android.bankabc", "cmb.pb", "com.yitong.mbank.psbc",
        "com.cgb.mobilebank", "com.czbank.mbank", "com.pingan.paces.ccmsapp",
        "com.greenpoint.android.mc10086", "com.sinovatech.unicom.ui", "com.ct.client",
        "cn.hsa.app", "com.service.android.gov.cn", "com.hicorenational.antifraud"
      ],
      outbound: directTag
    });
  }
  if (adsBlock) {
    addRule({ domain_suffix: ["teg.tencent-cloud.net"], outbound: dropTag });
    addRule({ rule_set: "geosite-category-ads-all", outbound: adsTag });
  }
  if (dnsProtect) {
    addRule({
      type: "logical",
      mode: "and",
      rules: [
        { port: 53, network: ["udp", "tcp"] },
        { rule_set: "geoip-cn", invert: true }
      ],
      action: "reject",
      method: "drop"
    });
    addRule({
      type: "logical",
      mode: "and",
      rules: [
        { port: 853, network: ["udp", "tcp"] },
        { rule_set: "geoip-cn", invert: true }
      ],
      action: "reject",
      method: "drop"
    });
    addRule({
      type: "logical",
      mode: "and",
      rules: [
        { port: [21, 23, 25, 110, 143], network: "tcp" },
        { rule_set: "geoip-cn", invert: true }
      ],
      action: "reject",
      method: "drop"
    });
    addRule({
      type: "logical",
      mode: "and",
      rules: [
        { port: [1900, 5353], network: "udp" },
        { rule_set: "geoip-cn", invert: true }
      ],
      action: "reject",
      method: "drop"
    });
  }
  // UDP 443 stays open. Rejecting it blackholed YouTube / X. HTTPS/SVCB
  // stays too: rejecting those lookups forced TCP and made YouTube buffer.
  // overlay.disableQuic / excludeCnQuic are still read; they no longer drop QUIC.
  if (disableQuic || excludeCnQuic) { /* QUIC stays open */ }
  var YT_SUFFIX = [
    "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com",
    "youtubekids.com", "youtube-nocookie.com", "youtubei.googleapis.com",
    "youtube.googleapis.com"
  ];
  addRule({
    package_name: [
      "com.anydesk.anydeskandroid", "com.oray.todesk",
      "com.teamviewer.teamviewer.market.mobile", "com.carriez.flutter_hbb",
      "com.tailscale.ipn", "com.zerotier.one"
    ],
    outbound: remoteTag
  });
  addRule({
    process_name: [
      "AnyDesk", "ToDesk", "TeamViewer", "RustDesk", "rustdesk",
      "tailscale", "tailscaled", "zerotier", "ngrok", "frpc", "frps", "cloudflared",
      "natapp", "nblink"
    ],
    outbound: remoteTag
  });
  addRule({
    package_name: [
      "com.google.android.youtube", "com.google.android.apps.youtube.music",
      "app.revanced.android.youtube", "com.vanced.android.youtube"
    ],
    outbound: youtubeTag
  });
  addRule({
    domain_suffix: YT_SUFFIX,
    outbound: youtubeTag
  });
  addRule({
    package_name: [
      "com.google.android.apps.bard",
      "com.google.android.apps.googleassistant",
      "com.google.android.googlequicksearchbox"
    ],
    outbound: aiOut
  });
  addRule(rule("geosite-category-ai-!cn", aiOut));
  addRule(rule("geosite-openai", aiOut));
  addRule({
    domain_suffix: [
      "gemini.google.com", "aistudio.google.com", "deepmind.com", "deepmind.google",
      "generativelanguage.googleapis.com", "ai.google.dev",
      "makersuite.google.com", "alkalimakersuite-pa.clients6.google.com",
      "chatgpt.com", "oaistatic.com", "oaiusercontent.com",
      "claude.ai", "anthropic.com"
    ],
    outbound: aiOut
  });
  addRule(rule("geosite-youtube", youtubeTag));
  addRule(rule("geosite-netflix", mediaOut));
  addRule(rule("geosite-disney", mediaOut));
  addRule(rule("geosite-hulu", mediaOut));
  addRule(rule("geosite-hbo", mediaOut));
  addRule(rule("geosite-amazon", mediaOut));
  addRule(rule("geosite-bahamut", mediaOut));
  addRule(rule("geosite-abema", mediaOut));
  addRule(rule("geosite-bbc", mediaOut));
  addRule(rule("geosite-spotify", existingTag(outbounds, ["🎵 Spotify", mediaTag]) || mediaOut));
  addRule(rule("geosite-tiktok", existingTag(outbounds, ["📱 TikTok", mediaTag]) || mediaOut));
  addRule(rule("geosite-telegram", existingTag(outbounds, ["📲 Telegram", pickSelect]) || pickSelect));
  addRule(rule("geosite-google", googleTag));
  addRule({
    domain_suffix: ["googleapis.com", "googleusercontent.com", "gvt1.com", "gvt2.com"],
    outbound: googleTag
  });
  addRule(rule("geosite-github", pickSelect));
  addRule(rule("geosite-gitlab", pickSelect));
  addRule(rule("geosite-microsoft", existingTag(outbounds, ["🪟 Microsoft", pickSelect]) || pickSelect));
  addRule(rule("geosite-apple", appleTag));
  addRule(rule("geosite-icloud", appleTag));
  addRule(rule("geosite-twitter", existingTag(outbounds, ["🐦 Twitter", pickSelect]) || pickSelect));
  addRule(rule("geosite-facebook", pickSelect));
  addRule(rule("geosite-instagram", pickSelect));
  addRule(rule("geosite-discord", pickSelect));
  addRule(rule("geosite-linkedin", pickSelect));
  addRule(rule("geosite-snap", pickSelect));
  addRule(rule("geosite-steam", steamTag));
  addRule(rule("geosite-epicgames", steamTag));
  addRule(rule("geosite-ea", steamTag));
  addRule(rule("geosite-ubisoft", steamTag));
  addRule(rule("geosite-blizzard", steamTag));
  addRule(rule("geosite-paypal", pickSelect));
  addRule(rule("geosite-aws", pickSelect));
  addRule(rule("geosite-azure", pickSelect));
  addRule(rule("geosite-dropbox", pickSelect));
  addRule(rule("geosite-onedrive", pickSelect));
  addRule(rule("geosite-category-scholar-!cn", pickSelect));
  addRule(rule("geosite-geolocation-!cn", mapTarget("🌍 国外服务")));
  addRule({
    domain_suffix: [
      "browserleaks.com", "browserleaks.org", "browserleaks.info",
      "ipleak.net", "ipleak.com", "dnsleaktest.com", "dnsleaktest.org", "dnsleak.com",
      "whoer.net", "whatismyipaddress.com", "whatismyip.com",
      "ipinfo.io", "ip-api.com", "ipify.org", "ipapi.co", "ipwho.is",
      "myip.com", "ifconfig.me", "ifconfig.co", "ipecho.net", "ip.sb", "ident.me"
    ],
    outbound: globalTag
  });
  addRule({
    domain_suffix: [
      "a-cdn.anthropic.com", "assets-proxy.anthropic.com"
    ],
    outbound: aiTag
  });
  addRule({
    domain_suffix: ["bing.com", "recaptcha.net", "crashlytics.com"],
    outbound: globalTag
  });

  function isInfraRule(item) {
    if (!item || typeof item !== "object") return false;
    var action = ("" + (item.action || "")).toLowerCase();
    if (action === "sniff" || action === "resolve" || action === "hijack-dns") return true;
    if (("" + (item.protocol || "")).toLowerCase() === "dns") return true;
    return false;
  }
  var oldRules = ensureArray(route, "rules");
  var merged = [];
  var seenInfra = {};
  function pushInfra(item) {
    if (!item) return;
    var action = ("" + (item.action || "")).toLowerCase();
    var key = action + "|" + (item.protocol || "") + "|" + JSON.stringify(item.port || "");
    if (seenInfra[key]) return;
    seenInfra[key] = 1;
    merged.push(item);
  }
  pushInfra({ protocol: "dns", action: "hijack-dns" });
  pushInfra({ port: 53, network: ["udp", "tcp"], action: "hijack-dns" });
  for (var o = 0; o < oldRules.length; o++) {
    if (isInfraRule(oldRules[o])) pushInfra(oldRules[o]);
  }
  for (var p = 0; p < prepend.length; p++) merged.push(prepend[p]);
  route.rules = merged;
  route.final = mapTarget("🐟 漏网之鱼");
  route.find_process = false;
  if (typeof route.auto_detect_interface === "undefined") {
    route.auto_detect_interface = true;
  }

  var inbounds = ensureArray(config, "inbounds");
  var hasTun = false;
  function isV4Cidr(s) {
    var text = "" + s;
    var slash = text.indexOf("/");
    if (slash <= 0) return false;
    var prefix = parseInt(text.substring(slash + 1), 10);
    if (!(prefix >= 8 && prefix <= 30)) return false;
    var parts = text.substring(0, slash).split(".");
    if (parts.length !== 4) return false;
    for (var pi = 0; pi < 4; pi++) {
      if (parts[pi] === "" || isNaN(parts[pi])) return false;
      var n = parseInt(parts[pi], 10);
      if (n < 0 || n > 255) return false;
      if (("" + n) !== parts[pi]) return false;
    }
    return true;
  }
  var keptInbounds = [];
  for (var ib = 0; ib < inbounds.length; ib++) {
    var inbound = inbounds[ib];
    var ity = typeOf(inbound);
    if (ity === "mixed") continue;
    if (ity === "tun" || ity === "socks" || ity === "http" || ity === "redirect" || ity === "tproxy") {
      inbound.sniff = true;
    }
    if (ity === "tun") {
      if (hasTun) continue;
      hasTun = true;
      inbound.auto_route = true;
      if (strictRoute) inbound.strict_route = true;
      if (inbound.stack) delete inbound.stack;
      if (inbound.gso) delete inbound.gso;
      if (inbound.inet6_address) delete inbound.inet6_address;
      var addr = inbound.address;
      var v4 = [];
      if (Object.prototype.toString.call(addr) === "[object Array]") {
        for (var ai = 0; ai < addr.length; ai++) {
          if (isV4Cidr(addr[ai])) v4.push("" + addr[ai]);
        }
      } else if (isV4Cidr(addr)) {
        v4.push("" + addr);
      }
      inbound.address = v4.length ? v4 : ["172.19.0.1/30"];
      inbound.mtu = 1500;
    }
    keptInbounds.push(inbound);
  }
  if (!hasTun) {
    var tunIn = {
      type: "tun",
      tag: "tun-in",
      address: ["172.19.0.1/30"],
      auto_route: true,
      mtu: 1500,
      sniff: true
    };
    if (strictRoute) tunIn.strict_route = true;
    keptInbounds.push(tunIn);
  }
  config.inbounds = keptInbounds;

  if (!config.dns || typeof config.dns !== "object") config.dns = {};
  var dns = config.dns;
  var remoteDns = {
    type: "tcp",
    tag: "dns-remote",
    server: "8.8.8.8",
    server_port: 53
  };
  var remoteDetour = existingTag(outbounds, [pickSelect, AUTO_NAME, SELECT_NAME]);
  if (remoteDetour) remoteDns.detour = remoteDetour;
  var cnDns = {
    type: "udp",
    tag: "dns-cn",
    server: "223.5.5.5",
    server_port: 53,
    detour: directTag
  };
  dns.servers = [
    {
      type: "hosts",
      tag: "dns-hosts",
      predefined: disableIpv6 ? {
        "dns.alidns.com": ["223.5.5.5", "223.6.6.6"],
        "doh.pub": ["1.12.12.12", "120.53.53.53"],
        "dns.google": ["8.8.8.8", "8.8.4.4"],
        "cloudflare-dns.com": ["1.1.1.1", "1.0.0.1"]
      } : {
        "dns.alidns.com": ["223.5.5.5", "223.6.6.6", "2400:3200::1", "2400:3200:baba::1"],
        "doh.pub": ["1.12.12.12", "120.53.53.53", "2402:4e00::"],
        "dns.google": ["8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"],
        "cloudflare-dns.com": ["1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"]
      }
    },
    { type: "local", tag: "dns-local" },
    { type: "fakeip", tag: "dns-fakeip", inet4_range: "198.18.0.0/15" },
    cnDns,
    remoteDns
  ];
  var extraDns = [];
  extraDns.push({ domain: ["dns.alidns.com", "doh.pub", "dns.google", "cloudflare-dns.com"], server: "dns-hosts" });
  extraDns.push({ domain: ["testingcf.jsdelivr.net"], server: "dns-cn" });
  if (chinaDirect) {
    extraDns.push({ domain_suffix: CN_DOMAINS, server: "dns-cn" });
    if (hasRuleSet("geosite-cn")) extraDns.push({ rule_set: "geosite-cn", server: "dns-cn" });
    if (hasRuleSet("geosite-geolocation-cn")) extraDns.push({ rule_set: "geosite-geolocation-cn", server: "dns-cn" });
    extraDns.push({ domain_suffix: [".cn", ".中国"], server: "dns-cn" });
  }
  extraDns.push({ query_type: ["A", "AAAA"], server: "dns-fakeip" });
  dns.rules = extraDns;
  dns.final = "dns-remote";
  dns.independent_cache = true;
  dns.strategy = disableIpv6 ? "ipv4_only" : "prefer_ipv4";
  route.default_domain_resolver = "dns-local";
  if (dnsProtect) route.auto_detect_interface = true;

  if (!config.log || typeof config.log !== "object") config.log = {};
  if (!config.log.level) config.log.level = "info";

  return config;
}
