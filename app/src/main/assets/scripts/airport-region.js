/**
 * 默认覆写脚本。
 * overlay-revision: 23
 * 全地区识别分组。地区自动选择是对应地区选择组里的一个成员，不另做一张卡片。
 * 手动选择是 selector，自动选择是 urltest。sing-box 没有负载均衡和故障转移，这里不生成这两个组。
 * 设置里的配置覆盖开关走全局 overlay，默认开。不要在脚本里改这些开关。
 * 手机流量走 TUN（172.19.0.1/30，MTU 1500），不绑定本机 mixed 端口。
 * 禁用 QUIC 时拒绝 UDP 443，用复位，不用 drop。排除国内 QUIC 时，国内域名的 UDP 443 先走直连。
 * REJECT 用 socks 127.0.0.1:9，标签仍叫 REJECT。不要写 block 出站。
 * 叶节点去掉 detour / dialer-proxy。链式代理由应用组，不写在这份脚本里。
 */
var ruleOptionsEnable = {
  手动选择: true,
  自动选择: true,
  远控工具: true,
  FCM: true,
  YouTube: true,
  Google: true,
  AI: true,
  Microsoft: true,
  Apple: true,
  Telegram: true,
  Steam: true,
  TikTok: true,
  Twitter: true,
  Meta: true,
  Line: true,
  Netflix: true,
  Emby: true,
  PikPak: true,
  Spotify: true,
  Crypto: true,
  EHentai: true,
  AdBlock: true,
  极简模式: false,
  生成地区自动选择组: true,
  隐藏地区手动选择组: false,
  生成倍率组: true,
  分流组添加所有节点: false,
  过滤低倍率节点: false,
  过滤高倍率节点: false,
  过滤非地区节点: true,
  代理IPV4优先: false,
  代理IPV6优先: false
};

var customizeProxies = [];

var excludeFilter = /群|返利|循环|官网|客服|网站|网址|获取|订阅|流量|到期|机场|下次|版本|官址|备用|过期|已用|联系|邮箱|工单|贩卖|通知|倒卖|防止|国内|地址|频道|电报|无法|说明|使用|提示|访问|支持|教程|关注|更新|作者|加入|超时|收藏|优惠|福利|邀请|好友|失联|选择|剩余|公益|发布|DIZTNA|通路|登录|禁止|定时|渠道|牢记|永久|余额|阁下|本站|刷新|导航|建议|重置|以下|过滤|⚠️|@|t\.me\/\+|\bexpire\b|\bhttps?:\/\/|\btraffic\b/i;
var lowRateFilter = /0\.[0-5]|低倍|免费|\bfree\b/i;
var highRateFilter = /[2-9]\s*倍|\bx\s*[2-9]\b|\b[2-9]\s*x\b/i;

var GEOSITE = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/";
var GEOIP = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/";

var REGIONS = [
    { name: "香港", flag: "🇭🇰", pattern: "🇭🇰|香港|\\bHKG?\\b|hong[\\s_-]*kong" },
    { name: "台湾省", flag: "🇹🇼", pattern: "🇹🇼|台湾|\\bTWN?\\b|taiwan" },
    { name: "日本", flag: "🇯🇵", pattern: "🇯🇵|日本|\\bJPN?\\b|japan|tokyo|osaka|东京|大阪" },
    { name: "韩国", flag: "🇰🇷", pattern: "🇰🇷|韩国|\\bKR\\b|korea|seoul|首尔" },
    { name: "新加坡", flag: "🇸🇬", pattern: "🇸🇬|新加坡|狮城|\\bSGP?\\b|singapore" },
    { name: "美国", flag: "🇺🇸", pattern: "🇺🇸|美国|\\bUSA?\\b|america|united[\\s_-]*states|los[\\s_-]*angeles|洛杉矶|san[\\s_-]*jose|圣何塞" },
    { name: "英国", flag: "🇬🇧", pattern: "🇬🇧|英国|\\bGB\\b|united[\\s_-]*kingdom|london|伦敦" },
    { name: "德国", flag: "🇩🇪", pattern: "🇩🇪|德国|\\bDE\\b|germany|frankfurt|法兰克福" },
    { name: "荷兰", flag: "🇳🇱", pattern: "🇳🇱|荷兰|\\bNL\\b|nether?lands|amsterdam|阿姆斯特丹" },
    { name: "马来西亚", flag: "🇲🇾", pattern: "🇲🇾|马来西亚|\\bMY\\b|malaysia|kuala[\\s_-]*lumpur|吉隆坡" },
    { name: "泰国", flag: "🇹🇭", pattern: "🇹🇭|泰国|\\bTH\\b|thailand|bangkok|曼谷" },
    { name: "越南", flag: "🇻🇳", pattern: "🇻🇳|越南|\\bVN\\b|vietnam|hanoi|河内|ho[\\s_-]*chi[\\s_-]*minh|胡志明" },
    { name: "菲律宾", flag: "🇵🇭", pattern: "🇵🇭|菲律宾|\\bPH\\b|philippines|manila|马尼拉" },
    { name: "印尼", flag: "🇮🇩", pattern: "🇮🇩|印尼|印度尼西亚|\\bID\\b|indonesia|jakarta|雅加达" },
    { name: "印度", flag: "🇮🇳", pattern: "🇮🇳|印度|\\bIN\\b|india|mumbai|孟买|delhi|德里" },
    { name: "澳大利亚", flag: "🇦🇺", pattern: "🇦🇺|澳大利亚|澳洲|\\bAU\\b|australia|sydney|悉尼|melbourne|墨尔本" },
    { name: "法国", flag: "🇫🇷", pattern: "🇫🇷|法国|\\bFR\\b|france|paris|巴黎" },
    { name: "俄罗斯", flag: "🇷🇺", pattern: "🇷🇺|俄罗斯|\\bRU\\b|russia|moscow|莫斯科" },
    { name: "意大利", flag: "🇮🇹", pattern: "🇮🇹|意大利|\\bIT\\b|\\bitaly\\b|rome|罗马" },
    { name: "加拿大", flag: "🇨🇦", pattern: "🇨🇦|加拿大|\\bCA\\b|canada|toronto|多伦多" },
    { name: "阿根廷", flag: "🇦🇷", pattern: "🇦🇷|阿根廷|\\bAR\\b|argentina|buenos[\\s_-]*aires|布宜诺斯艾利斯" },
    { name: "巴西", flag: "🇧🇷", pattern: "🇧🇷|巴西|\\bBR\\b|brazil|sao[\\s_-]*paulo|圣保罗" },
    { name: "墨西哥", flag: "🇲🇽", pattern: "🇲🇽|墨西哥|\\bMX\\b|mexico" },
    { name: "沙特阿拉伯", flag: "🇸🇦", pattern: "🇸🇦|沙特阿拉伯|沙特|\\bSA\\b|saudi[\\s_-]*arabia" },
    { name: "南非", flag: "🇿🇦", pattern: "🇿🇦|南非|\\bZA\\b|south[\\s_-]*africa|johannesburg|约翰内斯堡" },
    { name: "土耳其", flag: "🇹🇷", pattern: "🇹🇷|土耳其|\\bTR\\b|turkey|istanbul|伊斯坦布尔" },
    { name: "文莱", flag: "🇧🇳", pattern: "🇧🇳|文莱|\\bBN\\b|brunei" },
    { name: "柬埔寨", flag: "🇰🇭", pattern: "🇰🇭|柬埔寨|\\bKH\\b|cambodia|phnom[\\s_-]*penh|金边" },
    { name: "老挝", flag: "🇱🇦", pattern: "🇱🇦|老挝|\\bLA\\b|\\blaos\\b|vientiane|万象" },
    { name: "缅甸", flag: "🇲🇲", pattern: "🇲🇲|缅甸|\\bMM\\b|myanmar|yangon|仰光" },
    { name: "奥地利", flag: "🇦🇹", pattern: "🇦🇹|奥地利|\\bAT\\b|austria|vienna|维也纳" },
    { name: "比利时", flag: "🇧🇪", pattern: "🇧🇪|比利时|\\bBE\\b|belgium|brussels|布鲁塞尔" },
    { name: "保加利亚", flag: "🇧🇬", pattern: "🇧🇬|保加利亚|\\bBG\\b|bulgaria|sofia|索非亚" },
    { name: "克罗地亚", flag: "🇭🇷", pattern: "🇭🇷|克罗地亚|\\bHR\\b|croatia" },
    { name: "塞浦路斯", flag: "🇨🇾", pattern: "🇨🇾|塞浦路斯|\\bCY\\b|cyprus" },
    { name: "捷克", flag: "🇨🇿", pattern: "🇨🇿|捷克|捷克共和国|\\bCZ\\b|czech|prague|布拉格" },
    { name: "丹麦", flag: "🇩🇰", pattern: "🇩🇰|丹麦|\\bDK\\b|denmark|copenhagen|哥本哈根" },
    { name: "爱沙尼亚", flag: "🇪🇪", pattern: "🇪🇪|爱沙尼亚|\\bEE\\b|estonia" },
    { name: "芬兰", flag: "🇫🇮", pattern: "🇫🇮|芬兰|\\bFI\\b|finland|helsinki|赫尔辛基" },
    { name: "希腊", flag: "🇬🇷", pattern: "🇬🇷|希腊|\\bGR\\b|greece|athens|雅典" },
    { name: "匈牙利", flag: "🇭🇺", pattern: "🇭🇺|匈牙利|\\bHU\\b|hungary|budapest|布达佩斯" },
    { name: "爱尔兰", flag: "🇮🇪", pattern: "🇮🇪|爱尔兰|\\bIE\\b|ireland|dublin|都柏林" },
    { name: "拉脱维亚", flag: "🇱🇻", pattern: "🇱🇻|拉脱维亚|\\bLV\\b|latvia" },
    { name: "立陶宛", flag: "🇱🇹", pattern: "🇱🇹|立陶宛|\\bLT\\b|lithuania" },
    { name: "卢森堡", flag: "🇱🇺", pattern: "🇱🇺|卢森堡|\\bLU\\b|luxembourg" },
    { name: "马耳他", flag: "🇲🇹", pattern: "🇲🇹|马耳他|\\bMT\\b|malta" },
    { name: "波兰", flag: "🇵🇱", pattern: "🇵🇱|波兰|\\bPL\\b|poland|warsaw|华沙" },
    { name: "葡萄牙", flag: "🇵🇹", pattern: "🇵🇹|葡萄牙|\\bPT\\b|portugal|lisbon|里斯本" },
    { name: "罗马尼亚", flag: "🇷🇴", pattern: "🇷🇴|罗马尼亚|\\bRO\\b|romania|bucharest|布加勒斯特" },
    { name: "斯洛伐克", flag: "🇸🇰", pattern: "🇸🇰|斯洛伐克|\\bSK\\b|slovakia" },
    { name: "斯洛文尼亚", flag: "🇸🇮", pattern: "🇸🇮|斯洛文尼亚|\\bSI\\b|slovenia" },
    { name: "西班牙", flag: "🇪🇸", pattern: "🇪🇸|西班牙|\\bES\\b|\\bspain\\b|madrid|马德里" },
    { name: "瑞典", flag: "🇸🇪", pattern: "🇸🇪|瑞典|\\bSE\\b|sweden|stockholm|斯德哥尔摩" },
    { name: "阿尔及利亚", flag: "🇩🇿", pattern: "🇩🇿|阿尔及利亚|\\bDZ\\b|algeria" },
    { name: "安哥拉", flag: "🇦🇴", pattern: "🇦🇴|安哥拉|\\bAO\\b|angola" },
    { name: "贝宁", flag: "🇧🇯", pattern: "🇧🇯|贝宁|\\bBJ\\b|benin" },
    { name: "博茨瓦纳", flag: "🇧🇼", pattern: "🇧🇼|博茨瓦纳|\\bBW\\b|botswana" },
    { name: "布基纳法索", flag: "🇧🇫", pattern: "🇧🇫|布基纳法索|\\bBF\\b|burkina[\\s_-]*faso" },
    { name: "布隆迪", flag: "🇧🇮", pattern: "🇧🇮|布隆迪|\\bBI\\b|burundi" },
    { name: "佛得角", flag: "🇨🇻", pattern: "🇨🇻|佛得角|\\bCV\\b|cabo[\\s_-]*verde|cape[\\s_-]*verde" },
    { name: "喀麦隆", flag: "🇨🇲", pattern: "🇨🇲|喀麦隆|\\bCM\\b|cameroon" },
    { name: "中非共和国", flag: "🇨🇫", pattern: "🇨🇫|中非共和国|中非|\\bCF\\b|central[\\s_-]*african" },
    { name: "乍得", flag: "🇹🇩", pattern: "🇹🇩|乍得|\\bTD\\b|\\bchad\\b" },
    { name: "科摩罗", flag: "🇰🇲", pattern: "🇰🇲|科摩罗|\\bKM\\b|comoros" },
    { name: "刚果共和国", flag: "🇨🇬", pattern: "🇨🇬|刚果共和国|刚果（布）|\\bCG\\b|\\bcongo\\b" },
    { name: "刚果民主共和国", flag: "🇨🇩", pattern: "🇨🇩|刚果民主共和国|刚果（金）|民主刚果|\\bCD\\b|dr[\\s_-]*congo|democratic[\\s_-]*republic[\\s_-]*of[\\s_-]*the[\\s_-]*congo" },
    { name: "科特迪瓦", flag: "🇨🇮", pattern: "🇨🇮|科特迪瓦|象牙海岸|\\bCI\\b|cote[\\s_-]*d.ivoire|ivory[\\s_-]*coast" },
    { name: "吉布提", flag: "🇩🇯", pattern: "🇩🇯|吉布提|\\bDJ\\b|djibouti" },
    { name: "埃及", flag: "🇪🇬", pattern: "🇪🇬|埃及|\\bEG\\b|egypt|cairo|开罗" },
    { name: "赤道几内亚", flag: "🇬🇶", pattern: "🇬🇶|赤道几内亚|\\bGQ\\b|equatorial[\\s_-]*guinea" },
    { name: "厄立特里亚", flag: "🇪🇷", pattern: "🇪🇷|厄立特里亚|\\bER\\b|eritrea" },
    { name: "斯威士兰", flag: "🇸🇿", pattern: "🇸🇿|斯威士兰|埃斯瓦蒂尼|\\bSZ\\b|eswatini|swaziland" },
    { name: "埃塞俄比亚", flag: "🇪🇹", pattern: "🇪🇹|埃塞俄比亚|\\bET\\b|ethiopia" },
    { name: "加蓬", flag: "🇬🇦", pattern: "🇬🇦|加蓬|\\bGA\\b|\\bgabon\\b" },
    { name: "冈比亚", flag: "🇬🇲", pattern: "🇬🇲|冈比亚|\\bGM\\b|gambia" },
    { name: "加纳", flag: "🇬🇭", pattern: "🇬🇭|加纳|\\bGH\\b|\\bghana\\b" },
    { name: "几内亚", flag: "🇬🇳", pattern: "🇬🇳|几内亚|\\bGN\\b|\\bguinea\\b" },
    { name: "几内亚比绍", flag: "🇬🇼", pattern: "🇬🇼|几内亚比绍|\\bGW\\b|guinea-bissau|guinea[\\s_-]*bissau" },
    { name: "肯尼亚", flag: "🇰🇪", pattern: "🇰🇪|肯尼亚|\\bKE\\b|kenya|nairobi|内罗毕" },
    { name: "莱索托", flag: "🇱🇸", pattern: "🇱🇸|莱索托|\\bLS\\b|lesotho" },
    { name: "利比里亚", flag: "🇱🇷", pattern: "🇱🇷|利比里亚|\\bLR\\b|liberia" },
    { name: "利比亚", flag: "🇱🇾", pattern: "🇱🇾|利比亚|\\bLY\\b|\\blibya\\b" },
    { name: "马达加斯加", flag: "🇲🇬", pattern: "🇲🇬|马达加斯加|\\bMG\\b|madagascar" },
    { name: "马拉维", flag: "🇲🇼", pattern: "🇲🇼|马拉维|\\bMW\\b|malawi" },
    { name: "马里", flag: "🇲🇱", pattern: "🇲🇱|马里|\\bML\\b|\\bmali\\b" },
    { name: "毛里塔尼亚", flag: "🇲🇷", pattern: "🇲🇷|毛里塔尼亚|\\bMR\\b|mauritania" },
    { name: "毛里求斯", flag: "🇲🇺", pattern: "🇲🇺|毛里求斯|\\bMU\\b|mauritius" },
    { name: "摩洛哥", flag: "🇲🇦", pattern: "🇲🇦|摩洛哥|\\bMA\\b|morocco|casablanca|卡萨布兰卡" },
    { name: "莫桑比克", flag: "🇲🇿", pattern: "🇲🇿|莫桑比克|\\bMZ\\b|mozambique" },
    { name: "纳米比亚", flag: "🇳🇦", pattern: "🇳🇦|纳米比亚|\\bNA\\b|\\bnamibia\\b" },
    { name: "尼日尔", flag: "🇳🇪", pattern: "🇳🇪|尼日尔|\\bNE\\b|\\bniger\\b" },
    { name: "尼日利亚", flag: "🇳🇬", pattern: "🇳🇬|尼日利亚|\\bNG\\b|nigeria|lagos|拉各斯" },
    { name: "卢旺达", flag: "🇷🇼", pattern: "🇷🇼|卢旺达|\\bRW\\b|rwanda" },
    { name: "圣多美和普林西比", flag: "🇸🇹", pattern: "🇸🇹|圣多美和普林西比|\\bST\\b|sao[\\s_-]*tome" },
    { name: "塞内加尔", flag: "🇸🇳", pattern: "🇸🇳|塞内加尔|\\bSN\\b|senegal" },
    { name: "塞舌尔", flag: "🇸🇨", pattern: "🇸🇨|塞舌尔|\\bSC\\b|seychelles" },
    { name: "塞拉利昂", flag: "🇸🇱", pattern: "🇸🇱|塞拉利昂|\\bSL\\b|sierra[\\s_-]*leone" },
    { name: "索马里", flag: "🇸🇴", pattern: "🇸🇴|索马里|\\bSO\\b|somalia" },
    { name: "南苏丹", flag: "🇸🇸", pattern: "🇸🇸|南苏丹|\\bSS\\b|south[\\s_-]*sudan" },
    { name: "苏丹", flag: "🇸🇩", pattern: "🇸🇩|苏丹|\\bSD\\b|\\bsudan\\b" },
    { name: "坦桑尼亚", flag: "🇹🇿", pattern: "🇹🇿|坦桑尼亚|\\bTZ\\b|tanzania" },
    { name: "多哥", flag: "🇹🇬", pattern: "🇹🇬|多哥|\\bTG\\b|\\btogo\\b" },
    { name: "突尼斯", flag: "🇹🇳", pattern: "🇹🇳|突尼斯|\\bTN\\b|tunisia" },
    { name: "乌干达", flag: "🇺🇬", pattern: "🇺🇬|乌干达|\\bUG\\b|uganda" },
    { name: "赞比亚", flag: "🇿🇲", pattern: "🇿🇲|赞比亚|\\bZM\\b|zambia" },
    { name: "津巴布韦", flag: "🇿🇼", pattern: "🇿🇼|津巴布韦|\\bZW\\b|zimbabwe" },
    { name: "阿富汗", flag: "🇦🇫", pattern: "🇦🇫|阿富汗|afghanistan" },
    { name: "亚美尼亚", flag: "🇦🇲", pattern: "🇦🇲|亚美尼亚|armenia" },
    { name: "阿塞拜疆", flag: "🇦🇿", pattern: "🇦🇿|阿塞拜疆|azerbaijan" },
    { name: "巴林", flag: "🇧🇭", pattern: "🇧🇭|巴林|bahrain" },
    { name: "孟加拉国", flag: "🇧🇩", pattern: "🇧🇩|孟加拉国|bangladesh" },
    { name: "不丹", flag: "🇧🇹", pattern: "🇧🇹|不丹|bhutan" },
    { name: "格鲁吉亚", flag: "🇬🇪", pattern: "🇬🇪|格鲁吉亚|georgia" },
    { name: "伊朗", flag: "🇮🇷", pattern: "🇮🇷|伊朗|iran" },
    { name: "伊拉克", flag: "🇮🇶", pattern: "🇮🇶|伊拉克|iraq" },
    { name: "以色列", flag: "🇮🇱", pattern: "🇮🇱|以色列|israel" },
    { name: "约旦", flag: "🇯🇴", pattern: "🇯🇴|约旦|jordan" },
    { name: "哈萨克斯坦", flag: "🇰🇿", pattern: "🇰🇿|哈萨克斯坦|kazakhstan" },
    { name: "科威特", flag: "🇰🇼", pattern: "🇰🇼|科威特|kuwait" },
    { name: "吉尔吉斯斯坦", flag: "🇰🇬", pattern: "🇰🇬|吉尔吉斯斯坦|kyrgyzstan" },
    { name: "黎巴嫩", flag: "🇱🇧", pattern: "🇱🇧|黎巴嫩|lebanon" },
    { name: "马尔代夫", flag: "🇲🇻", pattern: "🇲🇻|马尔代夫|maldives" },
    { name: "蒙古", flag: "🇲🇳", pattern: "🇲🇳|蒙古|mongolia" },
    { name: "尼泊尔", flag: "🇳🇵", pattern: "🇳🇵|尼泊尔|nepal" },
    { name: "朝鲜", flag: "🇰🇵", pattern: "🇰🇵|朝鲜|north[\\s_-]*korea|dprk" },
    { name: "阿曼", flag: "🇴🇲", pattern: "🇴🇲|阿曼|oman" },
    { name: "巴基斯坦", flag: "🇵🇰", pattern: "🇵🇰|巴基斯坦|pakistan" },
    { name: "巴勒斯坦", flag: "🇵🇸", pattern: "🇵🇸|巴勒斯坦|palestine|west[\\s_-]*bank|gaza" },
    { name: "卡塔尔", flag: "🇶🇦", pattern: "🇶🇦|卡塔尔|qatar" },
    { name: "斯里兰卡", flag: "🇱🇰", pattern: "🇱🇰|斯里兰卡|sri[\\s_-]*lanka" },
    { name: "叙利亚", flag: "🇸🇾", pattern: "🇸🇾|叙利亚|syria" },
    { name: "塔吉克斯坦", flag: "🇹🇯", pattern: "🇹🇯|塔吉克斯坦|tajikistan" },
    { name: "东帝汶", flag: "🇹🇱", pattern: "🇹🇱|东帝汶|timor[\\s_-]*leste|east[\\s_-]*timor" },
    { name: "土库曼斯坦", flag: "🇹🇲", pattern: "🇹🇲|土库曼斯坦|turkmenistan" },
    { name: "阿联酋", flag: "🇦🇪", pattern: "🇦🇪|阿联酋|迪拜|阿布扎比|dubai|abu[\s_-]*dhabi|uae|united[\\s_-]*arab[\\s_-]*emirates" },
    { name: "乌兹别克斯坦", flag: "🇺🇿", pattern: "🇺🇿|乌兹别克斯坦|uzbekistan" },
    { name: "也门", flag: "🇾🇪", pattern: "🇾🇪|也门|yemen" },
    { name: "澳门", flag: "🇲🇴", pattern: "🇲🇴|澳门|macau|macao" },
    { name: "阿尔巴尼亚", flag: "🇦🇱", pattern: "🇦🇱|阿尔巴尼亚|albania" },
    { name: "安道尔", flag: "🇦🇩", pattern: "🇦🇩|安道尔|andorra" },
    { name: "白俄罗斯", flag: "🇧🇾", pattern: "🇧🇾|白俄罗斯|belarus" },
    { name: "波斯尼亚和黑塞哥维那", flag: "🇧🇦", pattern: "🇧🇦|波斯尼亚和黑塞哥维那|bosnia|bosnia[\\s_-]*and[\\s_-]*herzegovina" },
    { name: "冰岛", flag: "🇮🇸", pattern: "🇮🇸|冰岛|iceland" },
    { name: "列支敦士登", flag: "🇱🇮", pattern: "🇱🇮|列支敦士登|liechtenstein" },
    { name: "摩尔多瓦", flag: "🇲🇩", pattern: "🇲🇩|摩尔多瓦|moldova" },
    { name: "摩纳哥", flag: "🇲🇨", pattern: "🇲🇨|摩纳哥|monaco" },
    { name: "黑山", flag: "🇲🇪", pattern: "🇲🇪|黑山|montenegro" },
    { name: "北马其顿", flag: "🇲🇰", pattern: "🇲🇰|北马其顿|north[\\s_-]*macedonia|macedonia" },
    { name: "挪威", flag: "🇳🇴", pattern: "🇳🇴|挪威|norway" },
    { name: "圣马力诺", flag: "🇸🇲", pattern: "🇸🇲|圣马力诺|san[\\s_-]*marino" },
    { name: "塞尔维亚", flag: "🇷🇸", pattern: "🇷🇸|塞尔维亚|serbia" },
    { name: "瑞士", flag: "🇨🇭", pattern: "🇨🇭|瑞士|switzerland" },
    { name: "乌克兰", flag: "🇺🇦", pattern: "🇺🇦|乌克兰|ukraine" },
    { name: "梵蒂冈", flag: "🇻🇦", pattern: "🇻🇦|梵蒂冈|vatican" },
    { name: "科索沃", flag: "🇽🇰", pattern: "🇽🇰|科索沃|kosovo" },
    { name: "巴哈马", flag: "🇧🇸", pattern: "🇧🇸|巴哈马|bahamas" },
    { name: "巴巴多斯", flag: "🇧🇧", pattern: "🇧🇧|巴巴多斯|barbados" },
    { name: "伯利兹", flag: "🇧🇿", pattern: "🇧🇿|伯利兹|belize" },
    { name: "哥斯达黎加", flag: "🇨🇷", pattern: "🇨🇷|哥斯达黎加|costa[\\s_-]*rica" },
    { name: "古巴", flag: "🇨🇺", pattern: "🇨🇺|古巴|cuba" },
    { name: "多米尼克", flag: "🇩🇲", pattern: "🇩🇲|多米尼克|dominica" },
    { name: "多米尼加", flag: "🇩🇴", pattern: "🇩🇴|多米尼加|dominican[\\s_-]*republic|dominican" },
    { name: "萨尔瓦多", flag: "🇸🇻", pattern: "🇸🇻|萨尔瓦多|el[\\s_-]*salvador" },
    { name: "格林纳达", flag: "🇬🇩", pattern: "🇬🇩|格林纳达|grenada" },
    { name: "危地马拉", flag: "🇬🇹", pattern: "🇬🇹|危地马拉|guatemala" },
    { name: "海地", flag: "🇭🇹", pattern: "🇭🇹|海地|haiti" },
    { name: "洪都拉斯", flag: "🇭🇳", pattern: "🇭🇳|洪都拉斯|honduras" },
    { name: "牙买加", flag: "🇯🇲", pattern: "🇯🇲|牙买加|jamaica" },
    { name: "尼加拉瓜", flag: "🇳🇮", pattern: "🇳🇮|尼加拉瓜|nicaragua" },
    { name: "巴拿马", flag: "🇵🇦", pattern: "🇵🇦|巴拿马|panama" },
    { name: "圣基茨和尼维斯", flag: "🇰🇳", pattern: "🇰🇳|圣基茨和尼维斯|saint[\\s_-]*kitts|st[\\s_-]*kitts" },
    { name: "圣卢西亚", flag: "🇱🇨", pattern: "🇱🇨|圣卢西亚|saint[\\s_-]*lucia|st[\\s_-]*lucia" },
    { name: "圣文森特和格林纳丁斯", flag: "🇻🇨", pattern: "🇻🇨|圣文森特和格林纳丁斯|saint[\\s_-]*vincent|st[\\s_-]*vincent" },
    { name: "特立尼达和多巴哥", flag: "🇹🇹", pattern: "🇹🇹|特立尼达和多巴哥|trinidad[\\s_-]*and[\\s_-]*tobago" },
    { name: "安提瓜和巴布达", flag: "🇦🇬", pattern: "🇦🇬|安提瓜和巴布达|antigua[\\s_-]*and[\\s_-]*barbuda" },
    { name: "哥伦比亚", flag: "🇨🇴", pattern: "🇨🇴|哥伦比亚|波哥大|麦德林|\\bCO\\b|bogot[aá]|medellin|colombia" },
    { name: "智利", flag: "🇨🇱", pattern: "🇨🇱|智利|chile" },
    { name: "秘鲁", flag: "🇵🇪", pattern: "🇵🇪|秘鲁|peru" },
    { name: "乌拉圭", flag: "🇺🇾", pattern: "🇺🇾|乌拉圭|uruguay" },
    { name: "巴拉圭", flag: "🇵🇾", pattern: "🇵🇾|巴拉圭|paraguay" },
    { name: "玻利维亚", flag: "🇧🇴", pattern: "🇧🇴|玻利维亚|bolivia" },
    { name: "厄瓜多尔", flag: "🇪🇨", pattern: "🇪🇨|厄瓜多尔|ecuador" },
    { name: "委内瑞拉", flag: "🇻🇪", pattern: "🇻🇪|委内瑞拉|venezuela" },
    { name: "圭亚那", flag: "🇬🇾", pattern: "🇬🇾|圭亚那|guyana" },
    { name: "苏里南", flag: "🇸🇷", pattern: "🇸🇷|苏里南|suriname" },
    { name: "新西兰", flag: "🇳🇿", pattern: "🇳🇿|新西兰|new[\\s_-]*zealand|auckland|奥克兰|wellington|惠灵顿" },
    { name: "斐济", flag: "🇫🇯", pattern: "🇫🇯|斐济|fiji" },
    { name: "巴布亚新几内亚", flag: "🇵🇬", pattern: "🇵🇬|巴布亚新几内亚|papua[\\s_-]*new[\\s_-]*guinea" },
    { name: "所罗门群岛", flag: "🇸🇧", pattern: "🇸🇧|所罗门群岛|solomon[\\s_-]*islands" },
    { name: "瓦努阿图", flag: "🇻🇺", pattern: "🇻🇺|瓦努阿图|vanuatu" },
    { name: "萨摩亚", flag: "🇼🇸", pattern: "🇼🇸|萨摩亚|samoa" },
    { name: "汤加", flag: "🇹🇴", pattern: "🇹🇴|汤加|tonga" },
    { name: "基里巴斯", flag: "🇰🇮", pattern: "🇰🇮|基里巴斯|kiribati" },
    { name: "图瓦卢", flag: "🇹🇻", pattern: "🇹🇻|图瓦卢|tuvalu" },
    { name: "瑙鲁", flag: "🇳🇷", pattern: "🇳🇷|瑙鲁|nauru" },
    { name: "帕劳", flag: "🇵🇼", pattern: "🇵🇼|帕劳|palau" },
    { name: "马绍尔群岛", flag: "🇲🇭", pattern: "🇲🇭|马绍尔群岛|marshall[\\s_-]*islands" },
    { name: "密克罗尼西亚", flag: "🇫🇲", pattern: "🇫🇲|密克罗尼西亚|micronesia|federated[\\s_-]*states[\\s_-]*of[\\s_-]*micronesia" },
    { name: "关岛", flag: "🇬🇺", pattern: "🇬🇺|关岛|guam" },
    { name: "波多黎各", flag: "🇵🇷", pattern: "🇵🇷|波多黎各|puerto[\\s_-]*rico" },
    { name: "百慕大", flag: "🇧🇲", pattern: "🇧🇲|百慕大|bermuda" },
    { name: "格陵兰", flag: "🇬🇱", pattern: "🇬🇱|格陵兰|greenland" },
    { name: "库拉索", flag: "🇨🇼", pattern: "🇨🇼|库拉索|curacao|curaçao" },
    { name: "阿鲁巴", flag: "🇦🇼", pattern: "🇦🇼|阿鲁巴|aruba" },
    { name: "开曼群岛", flag: "🇰🇾", pattern: "🇰🇾|开曼群岛|cayman[\\s_-]*islands" },
    { name: "英属维尔京群岛", flag: "🇻🇬", pattern: "🇻🇬|英属维尔京群岛|british[\\s_-]*virgin[\\s_-]*islands" },
    { name: "美属维尔京群岛", flag: "🇻🇮", pattern: "🇻🇮|美属维尔京群岛|us[\\s_-]*virgin[\\s_-]*islands|u\\.s\\.[\\s_-]*virgin[\\s_-]*islands" },
    { name: "新喀里多尼亚", flag: "🇳🇨", pattern: "🇳🇨|新喀里多尼亚|new[\\s_-]*caledonia" },
    { name: "法属波利尼西亚", flag: "🇵🇫", pattern: "🇵🇫|法属波利尼西亚|french[\\s_-]*polynesia" },
    { name: "库克群岛", flag: "🇨🇰", pattern: "🇨🇰|库克群岛|cook[\\s_-]*islands" },
    { name: "纽埃", flag: "🇳🇺", pattern: "🇳🇺|纽埃|niue" }
];

function on(key) {
  if (Object.prototype.hasOwnProperty.call(ruleOptionsEnable, key)) {
    return !!ruleOptionsEnable[key];
  }
  var ov = (typeof overlay === "object" && overlay) ? overlay : null;
  if (!ov || typeof ov[key] === "undefined") return true;
  var v = ov[key];
  return v !== false && v !== 0 && v !== "false";
}
function rejectSink() {
  return { type: "socks", tag: "REJECT", server: "127.0.0.1", server_port: 9 };
}
function isArray(v) {
  return Object.prototype.toString.call(v) === "[object Array]";
}
function tagOf(item) {
  if (!item || typeof item !== "object") return "";
  return ("" + (item.tag || item.name || "")).trim();
}
function typeOf(item) {
  if (!item || typeof item !== "object") return "";
  return ("" + (item.type || "")).toLowerCase();
}
function pushUnique(list, item) {
  var t = tagOf(item);
  if (!t) return;
  for (var i = 0; i < list.length; i++) {
    if (tagOf(list[i]) === t) return;
  }
  list.push(item);
}
function hasTag(list, tag) {
  for (var i = 0; i < list.length; i++) {
    if (tagOf(list[i]) === tag) return true;
  }
  return false;
}
function copyTags(list) {
  var out = [];
  for (var i = 0; i < list.length; i++) out.push(list[i]);
  return out;
}
function concatTags(a, b) {
  var out = copyTags(a);
  for (var i = 0; i < b.length; i++) {
    var found = false;
    for (var j = 0; j < out.length; j++) {
      if (out[j] === b[i]) found = true;
    }
    if (!found) out.push(b[i]);
  }
  return out;
}

var GROUP = {
  selector: 1, urltest: 1, direct: 1, block: 1, dns: 1, relay: 1, chain: 1
};

function isLeaf(item) {
  var t = typeOf(item);
  if (!t || GROUP[t]) return false;
  if (t === "direct" || t === "block" || t === "dns") return false;
  return true;
}

function selector(tag, members, def) {
  var o = {
    type: "selector",
    tag: tag,
    outbounds: copyTags(members),
    interrupt_exist_connections: false
  };
  if (def) {
    for (var i = 0; i < members.length; i++) {
      if (members[i] === def) {
        o["default"] = def;
        break;
      }
    }
  }
  return o;
}
function urltest(tag, members) {
  return {
    type: "urltest",
    tag: tag,
    outbounds: copyTags(members),
    url: "https://www.gstatic.com/generate_204",
    interval: "10m",
    tolerance: 50,
    idle_timeout: "30m",
    interrupt_exist_connections: false
  };
}
function directOut(tag, strategy) {
  var o = { type: "direct", tag: tag, connect_timeout: "8s" };
  if (strategy) o.domain_strategy = strategy;
  return o;
}
function ruleSet(tag, file, geoip) {
  return {
    type: "remote",
    tag: tag,
    format: "binary",
    url: (geoip ? GEOIP : GEOSITE) + file,
    update_interval: "24h",
    http_client: "http-direct"
  };
}
function useSet(sets, rules, tag, file, geoip, outbound) {
  ensureSet(sets, tag, file, geoip);
  rules.push({ rule_set: tag, outbound: outbound });
}
function ensureSet(sets, tag, file, geoip) {
  for (var i = 0; i < sets.length; i++) {
    if (sets[i].tag === tag) return;
  }
  sets.push(ruleSet(tag, file, geoip));
}

function isAnnouncement(name) {
  try {
    return excludeFilter.test(name);
  } catch (e) {
    return false;
  }
}
function matchedRegions(name, compiled) {
  var hits = [];
  for (var i = 0; i < compiled.length; i++) {
    if (compiled[i].re.test(name)) hits.push(compiled[i]);
  }
  if (hits.length < 2) return hits;
  var best = 0;
  for (var j = 0; j < hits.length; j++) {
    if (hits[j].pattern.length > best) best = hits[j].pattern.length;
  }
  var kept = [];
  for (var k = 0; k < hits.length; k++) {
    if (hits[k].pattern.length === best) kept.push(hits[k]);
  }
  return kept;
}

function applyTransport(config) {
  var strictRoute = on("strictRoute");
  var inbounds = isArray(config.inbounds) ? config.inbounds : [];
  var kept = [];
  var hasTun = false;
  for (var ib = 0; ib < inbounds.length; ib++) {
    var inbound = inbounds[ib];
    if (!inbound || typeof inbound !== "object") continue;
    if (typeOf(inbound) === "mixed") continue;
    if (typeOf(inbound) === "tun") {
      hasTun = true;
      inbound.tag = inbound.tag || "tun-in";
      inbound.address = ["172.19.0.1/30"];
      inbound.mtu = 1500;
      inbound.auto_route = true;
      inbound.strict_route = !!strictRoute;
      inbound.sniff = true;
      if (inbound.stack) delete inbound.stack;
      if (inbound.inet6_address) delete inbound.inet6_address;
    }
    kept.push(inbound);
  }
  if (!hasTun) {
    kept.push({
      type: "tun",
      tag: "tun-in",
      address: ["172.19.0.1/30"],
      auto_route: true,
      strict_route: !!strictRoute,
      mtu: 1500,
      sniff: true
    });
  }
  config.inbounds = kept;
}
function main(config) {
  if (!config || typeof config !== "object") return config;
  var outbounds = isArray(config.outbounds) ? config.outbounds : [];
  var compiled = [];
  for (var r = 0; r < REGIONS.length; r++) {
    compiled.push({
      name: REGIONS[r].name,
      flag: REGIONS[r].flag,
      pattern: REGIONS[r].pattern,
      re: new RegExp(REGIONS[r].pattern, "i")
    });
  }

  var leaves = [];
  var leafTags = [];
  var strategy = "";
  if (on("代理IPV4优先") && !on("代理IPV6优先")) strategy = "prefer_ipv4";
  if (on("代理IPV6优先") && !on("代理IPV4优先")) strategy = "prefer_ipv6";

  for (var i = 0; i < outbounds.length; i++) {
    var item = outbounds[i];
    if (!isLeaf(item)) continue;
    var name = tagOf(item);
    if (!name) continue;
    if (isAnnouncement(name)) continue;
    if (on("过滤低倍率节点") && lowRateFilter.test(name)) continue;
    if (on("过滤高倍率节点") && highRateFilter.test(name)) continue;
    var regions = matchedRegions(name, compiled);
    if (on("过滤非地区节点") && regions.length === 0 && excludeFilter.test(name)) continue;
    if (item.detour) delete item.detour;
    if (item["dialer-proxy"]) delete item["dialer-proxy"];
    if (strategy) item.domain_strategy = strategy;
    item.tcp_keep_alive = "60s";
    if (!item.tcp_keep_alive_interval) item.tcp_keep_alive_interval = "60s";
    leaves.push(item);
    leafTags.push(name);
    item._regions = regions;
  }
  for (var c = 0; c < customizeProxies.length; c++) {
    var custom = customizeProxies[c];
    if (!custom || typeof custom !== "object") continue;
    if (!custom.tag && custom.name) custom.tag = custom.name;
    if (custom.detour) delete custom.detour;
    if (custom["dialer-proxy"]) delete custom["dialer-proxy"];
    var ct = tagOf(custom);
    if (!ct || hasTag(leaves, ct)) continue;
    custom._regions = matchedRegions(ct, compiled);
    leaves.push(custom);
    leafTags.push(ct);
  }
  if (leafTags.length === 0) {
    applyTransport(config);
    return config;
  }

  var buckets = {};
  var other = [];
  for (var b = 0; b < REGIONS.length; b++) buckets[REGIONS[b].name] = [];
  for (var n = 0; n < leaves.length; n++) {
    var hit = leaves[n]._regions || [];
    if (hit.length === 0) other.push(tagOf(leaves[n]));
    for (var h = 0; h < hit.length; h++) buckets[hit[h].name].push(tagOf(leaves[n]));
    delete leaves[n]._regions;
  }

  var regionSelect = [];
  var regionGroups = [];
  for (var g = 0; g < REGIONS.length; g++) {
    var members = buckets[REGIONS[g].name];
    if (!members || members.length === 0) continue;
    var label = (REGIONS[g].flag ? REGIONS[g].flag + " " : "") + REGIONS[g].name;
    var manualMembers = copyTags(members);
    if (on("生成地区自动选择组")) {
      var autoName = label + "-自动选择";
      regionGroups.push(urltest(autoName, members));
      manualMembers.push(autoName);
    }
    if (!on("隐藏地区手动选择组")) {
      regionGroups.push(selector(label, manualMembers));
      regionSelect.push(label);
    } else if (on("生成地区自动选择组")) {
      regionSelect.push(label + "-自动选择");
    }
  }
  if (other.length) {
    var otherMembers = copyTags(other);
    if (on("生成地区自动选择组")) {
      regionGroups.push(urltest("其他节点-自动选择", other));
      otherMembers.push("其他节点-自动选择");
    }
    if (!on("隐藏地区手动选择组")) {
      regionGroups.push(selector("其他节点", otherMembers));
      regionSelect.push("其他节点");
    }
  }
  if (on("生成倍率组")) {
    var low = [];
    var high = [];
    for (var n2 = 0; n2 < leafTags.length; n2++) {
      if (lowRateFilter.test(leafTags[n2])) low.push(leafTags[n2]);
      if (highRateFilter.test(leafTags[n2])) high.push(leafTags[n2]);
    }
    if (low.length) {
      regionGroups.push(selector("低倍率节点", low));
      regionSelect.push("低倍率节点");
    }
    if (high.length) {
      regionGroups.push(selector("高倍率节点", high));
      regionSelect.push("高倍率节点");
    }
  }

  var baseNames = [];
  var baseGroups = [];
  if (on("手动选择")) {
    baseGroups.push(selector("手动选择", leafTags));
    baseNames.push("手动选择");
  }
  if (on("自动选择")) {
    baseGroups.push(urltest("自动选择", leafTags));
    baseNames.push("自动选择");
  }

  var directTag = "direct";
  var existingDirect = null;
  var directNameTaken = false;
  for (var d = 0; d < outbounds.length; d++) {
    if (tagOf(outbounds[d]) !== "direct") continue;
    if (typeOf(outbounds[d]) === "direct") existingDirect = outbounds[d];
    else directNameTaken = true;
  }
  if (!existingDirect && directNameTaken) directTag = "angela-direct";
  var directVariants = [
    directOut("🇨🇳 直连 | 双栈", ""),
    directOut("🇨🇳 直连 | IPv4优先", "prefer_ipv4"),
    directOut("🇨🇳 直连 | IPv6优先", "prefer_ipv6"),
    directOut("🇨🇳 直连 | 仅IPv4", "ipv4_only"),
    directOut("🇨🇳 直连 | 仅IPv6", "ipv6_only")
  ];
  if (!existingDirect) directVariants.unshift(directOut(directTag, ""));
  if (existingDirect && !existingDirect.connect_timeout) existingDirect.connect_timeout = "8s";
  var directNames = [];
  for (var dv = 0; dv < directVariants.length; dv++) {
    if (directVariants[dv].tag === "direct" && existingDirect) continue;
    directNames.push(directVariants[dv].tag);
  }
  if (existingDirect) directNames.unshift("direct");
  var directGroup = selector("直连", directNames.length ? directNames : [directTag]);

  var head = concatTags(regionSelect, baseNames);
  var minimal = on("极简模式");
  var defaultMembers = minimal ? copyTags(leafTags) : head;
  var defaultGroup = selector("默认代理", defaultMembers.length ? defaultMembers : leafTags);

  var chinaDirect = on("chinaDirect");
  var adsBlock = on("adsBlock");
  var webrtcProtect = on("webrtcProtect");
  var disableQuic = on("disableQuic");
  var excludeCnQuic = on("excludeCnQuic");
  var disableIpv6 = on("disableIpv6");
  var dnsProtect = on("dnsProtect");
  var strictRoute = on("strictRoute");

  var sets = [];
  var rules = [];
  rules.push({ protocol: "dns", action: "hijack-dns" });
  rules.push({ port: 53, network: ["udp", "tcp"], action: "hijack-dns" });
  rules.push({ clash_mode: "Global", outbound: "默认代理" });
  rules.push({ clash_mode: "Direct", outbound: directTag });
  if (disableIpv6) rules.push({ ip_version: 6, action: "reject" });
  rules.push({ ip_is_private: true, outbound: "直连" });
  if (chinaDirect) {
    useSet(sets, rules, "geosite-private", "geosite-private.srs", false, "直连");
    useSet(sets, rules, "geoip-cn", "geoip-cn.srs", true, "直连");
    useSet(sets, rules, "geosite-geolocation-cn", "geosite-geolocation-cn.srs", false, "直连");
    useSet(sets, rules, "geosite-cn", "geosite-cn.srs", false, "直连");
    useSet(sets, rules, "geosite-category-games@cn", "geosite-category-games@cn.srs", false, "直连");
    useSet(sets, rules, "geosite-epicgames", "geosite-epicgames.srs", false, "直连");
    useSet(sets, rules, "geosite-nvidia@cn", "geosite-nvidia@cn.srs", false, "直连");
    useSet(sets, rules, "geosite-apple@cn", "geosite-apple@cn.srs", false, "直连");
    useSet(sets, rules, "geosite-microsoft@cn", "geosite-microsoft@cn.srs", false, "直连");
    rules.push({ domain: ["fsend.cn", "international-gfe.download.nvidia.com"], outbound: "直连" });
  }
  if (webrtcProtect) {
    rules.push({ network: "udp", port_range: "3478:3481", action: "reject" });
    rules.push({ network: "tcp", port_range: "3478:3481", action: "reject" });
    rules.push({ network: "udp", port_range: "3478:3497", action: "reject" });
    rules.push({ network: "tcp", port_range: "3478:3497", action: "reject" });
    rules.push({ network: "udp", port_range: "5349:5355", action: "reject" });
    rules.push({ network: "tcp", port_range: "5349:5355", action: "reject" });
    rules.push({ network: "udp", port_range: "19302:19310", action: "reject" });
    rules.push({ network: "tcp", port_range: "19302:19310", action: "reject" });
  }
  if (dnsProtect) {
    ensureSet(sets, "geoip-cn", "geoip-cn.srs", true);
    rules.push({
      type: "logical",
      mode: "and",
      rules: [{ port: 53, network: ["udp", "tcp"] }, { rule_set: "geoip-cn", invert: true }],
      action: "reject"
    });
    rules.push({
      type: "logical",
      mode: "and",
      rules: [{ port: 853, network: ["udp", "tcp"] }, { rule_set: "geoip-cn", invert: true }],
      action: "reject"
    });
  }
  if (disableQuic && excludeCnQuic) {
    ensureSet(sets, "geosite-cn", "geosite-cn.srs", false);
    ensureSet(sets, "geosite-geolocation-cn", "geosite-geolocation-cn.srs", false);
    rules.push({
      network: "udp",
      port: 443,
      rule_set: ["geosite-cn", "geosite-geolocation-cn"],
      outbound: "直连"
    });
  }
  if (disableQuic) {
    rules.push({ network: "udp", port: 443, action: "reject" });
  }

  function serviceMembers(spec) {
    if (spec.fixed) return copyTags(spec.fixed);
    if (spec.reject) return ["REJECT"];
    var members = ["默认代理"];
    members = concatTags(members, baseNames);
    members = concatTags(members, regionSelect);
    if (spec.direct) members.push("直连");
    if (on("分流组添加所有节点")) members = concatTags(members, leafTags);
    return members;
  }
  function addService(spec) {
    if (!on(spec.name)) return;
    var group = selector(spec.name, serviceMembers(spec), spec.def || "");
    baseGroups.push(group);
    if (spec.domains) {
      for (var di = 0; di < spec.domains.length; di++) {
        rules.push(spec.domains[di]);
      }
    }
    if (spec.sets) {
      for (var si = 0; si < spec.sets.length; si++) {
        var s = spec.sets[si];
        useSet(sets, rules, s.tag, s.file, !!s.geoip, s.out);
      }
    }
    if (spec.extra) {
      for (var ei = 0; ei < spec.extra.length; ei++) rules.push(spec.extra[ei]);
    }
  }

  if (!minimal) {
    if (adsBlock) {
      addService({
        name: "AdBlock",
        reject: true,
        sets: [{ tag: "geosite-category-ads-all", file: "geosite-category-ads-all.srs", out: "AdBlock" }]
      });
    }
    addService({
      name: "FCM",
      direct: true,
      def: "直连",
      domains: [{ domain_suffix: ["mtalk.google.com", "android.googleapis.com"], outbound: "FCM" }]
    });
    addService({
      name: "YouTube",
      sets: [{ tag: "geosite-youtube", file: "geosite-youtube.srs", out: "YouTube" }],
      domains: [{ domain_suffix: ["youtubei.googleapis.com", "youtube.googleapis.com"], outbound: "YouTube" }]
    });
    addService({ name: "Google", sets: [{ tag: "geosite-google", file: "geosite-google.srs", out: "Google" }] });
    addService({
      name: "AI",
      def: "🇺🇸 美国",
      sets: [{ tag: "geosite-category-ai-!cn", file: "geosite-category-ai-!cn.srs", out: "AI" }],
      domains: [{ domain_suffix: ["openai.com", "chatgpt.com", "anthropic.com", "claude.ai", "gemini.google.com", "generativelanguage.googleapis.com", "aistudio.google.com"], outbound: "AI" }],
      extra: [{ package_name: ["com.google.android.apps.bard"], outbound: "AI" }]
    });
    addService({
      name: "Microsoft",
      direct: true,
      sets: [
        { tag: "geosite-github", file: "geosite-github.srs", out: "默认代理" },
        { tag: "geosite-microsoft", file: "geosite-microsoft.srs", out: "Microsoft" }
      ]
    });
    addService({ name: "Apple", direct: true, sets: [{ tag: "geosite-apple", file: "geosite-apple.srs", out: "Apple" }] });
    addService({ name: "Telegram", sets: [{ tag: "geosite-telegram", file: "geosite-telegram.srs", out: "Telegram" }] });
    addService({ name: "Steam", direct: true, sets: [{ tag: "geosite-steam", file: "geosite-steam.srs", out: "Steam" }] });
    addService({ name: "TikTok", def: "🇯🇵 日本", sets: [{ tag: "geosite-tiktok", file: "geosite-tiktok.srs", out: "TikTok" }] });
    addService({ name: "Twitter", sets: [{ tag: "geosite-twitter", file: "geosite-twitter.srs", out: "Twitter" }] });
    addService({
      name: "Meta",
      sets: [
        { tag: "geosite-facebook", file: "geosite-facebook.srs", out: "Meta" },
        { tag: "geosite-instagram", file: "geosite-instagram.srs", out: "Meta" },
        { tag: "geosite-whatsapp", file: "geosite-whatsapp.srs", out: "Meta" },
        { tag: "geosite-threads", file: "geosite-threads.srs", out: "Meta" },
        { tag: "geosite-messenger", file: "geosite-messenger.srs", out: "Meta" },
        { tag: "geosite-meta", file: "geosite-meta.srs", out: "Meta" },
        { tag: "geosite-oculus", file: "geosite-oculus.srs", out: "Meta" }
      ],
      domains: [{ domain_suffix: ["facebook.com", "fb.com", "instagram.com", "whatsapp.com", "threads.net", "messenger.com", "meta.com", "oculus.com"], outbound: "Meta" }]
    });
    addService({
      name: "Line",
      domains: [{ domain_suffix: ["line.me", "line-apps.com", "line.naver.jp"], outbound: "Line" }]
    });
    addService({ name: "Netflix", sets: [{ tag: "geosite-netflix", file: "geosite-netflix.srs", out: "Netflix" }] });
    addService({
      name: "Emby",
      direct: true,
      domains: [
        { domain_suffix: ["mb3admin.com", "nubebelle.com", "emby.media"], outbound: "Emby" },
        { domain_keyword: ["emby"], outbound: "Emby" }
      ]
    });
    addService({
      name: "PikPak",
      direct: true,
      domains: [{ domain_suffix: ["mypikpak.com", "pikpak.com"], outbound: "PikPak" }]
    });
    addService({ name: "Spotify", direct: true, sets: [{ tag: "geosite-spotify", file: "geosite-spotify.srs", out: "Spotify" }] });
    addService({
      name: "Crypto",
      def: "🇯🇵 日本",
      domains: [{ domain_suffix: ["binance.com", "okx.com", "bybit.com", "htx.com"], outbound: "Crypto" }]
    });
    addService({
      name: "EHentai",
      direct: true,
      def: "🇺🇸 美国",
      domains: [{ domain_suffix: ["e-hentai.org", "exhentai.org", "ehgt.org"], outbound: "EHentai" }]
    });
    addService({
      name: "远控工具",
      fixed: ["REJECT", "默认代理", "直连"],
      extra: [
        {
          process_name: ["anydesk", "ToDesk", "TeamViewer", "rustdesk", "tailscaled", "zerotier-one", "ngrok", "frpc", "frps", "cloudflared"],
          outbound: "远控工具"
        },
        {
          package_name: ["com.anydesk.anydeskandroid", "com.oray.todesk", "com.teamviewer.teamviewer.market.mobile", "com.carriez.flutter_hbb", "com.tailscale.ipn", "com.zerotier.one"],
          outbound: "远控工具"
        }
      ]
    });
    if (chinaDirect) ensureSet(sets, "geoip-cn", "geoip-cn.srs", true);
    useSet(sets, rules, "geosite-geolocation-!cn", "geosite-geolocation-!cn.srs", false, "默认代理");
    if (chinaDirect) {
      var alreadyGeoip = false;
      for (var gx = 0; gx < rules.length; gx++) {
        if (rules[gx].rule_set === "geoip-cn") alreadyGeoip = true;
      }
      if (!alreadyGeoip) rules.push({ rule_set: "geoip-cn", outbound: "直连" });
    }
  }

  var fishMembers = concatTags(["默认代理", "直连"], regionSelect);
  var fish = selector("漏网之鱼", fishMembers);
  var finalTag = minimal ? "默认代理" : "漏网之鱼";

  var next = [];
  for (var k = 0; k < leaves.length; k++) pushUnique(next, leaves[k]);
  if (existingDirect) pushUnique(next, existingDirect);
  for (var dv2 = 0; dv2 < directVariants.length; dv2++) pushUnique(next, directVariants[dv2]);
  next.push(rejectSink());
  next.push(defaultGroup);
  for (var bg = 0; bg < baseGroups.length; bg++) next.push(baseGroups[bg]);
  next.push(directGroup);
  for (var rg = 0; rg < regionGroups.length; rg++) next.push(regionGroups[rg]);
  if (!minimal) next.push(fish);
  var cleaned = [];
  for (var ci = 0; ci < next.length; ci++) {
    var ob = next[ci];
    if (!ob || !tagOf(ob)) continue;
    var ot = typeOf(ob);
    if (ot === "block" || ot === "dns") continue;
    if ((ot === "selector" || ot === "urltest") && (!isArray(ob.outbounds) || ob.outbounds.length === 0)) continue;
    cleaned.push(ob);
  }
  config.outbounds = cleaned;

  if (!config.route || typeof config.route !== "object") config.route = {};
  config.route.rules = rules;
  config.route.rule_set = sets;
  config.route.final = finalTag;
  config.route.auto_detect_interface = true;
  config.route.find_process = false;
  config.route.default_domain_resolver = "dns-cn";
  config.http_clients = [{ tag: "http-direct" }];
  config.route.default_http_client = "http-direct";

  var remoteDns = {
    type: "tcp",
    tag: "dns-remote",
    server: "1.1.1.1",
    server_port: 53
  };
  remoteDns.detour = "默认代理";
  var cnDns = {
    type: "udp",
    tag: "dns-cn",
    server: "223.5.5.5",
    server_port: 53
  };
  cnDns.detour = directTag;
  var dnsServers = [
    { type: "fakeip", tag: "dns-fakeip", inet4_range: "198.18.0.0/15" },
    cnDns,
    remoteDns
  ];
  var dnsRules = [];
  if (!disableIpv6) {
    dnsServers.unshift({
      type: "hosts",
      tag: "dns-hosts",
      predefined: {
        "dns.alidns.com": ["223.5.5.5", "2400:3200::1"],
        "dns.google": ["8.8.8.8", "2001:4860:4860::8888"]
      }
    });
    dnsRules.push({ domain: ["dns.alidns.com", "dns.google"], server: "dns-hosts" });
  }
  if (chinaDirect) {
    dnsRules.push({ rule_set: ["geosite-cn", "geosite-geolocation-cn"], server: "dns-cn" });
  }
  dnsRules.push({ query_type: ["A", "AAAA"], server: "dns-fakeip" });
  config.dns = {
    servers: dnsServers,
    rules: dnsRules,
    final: "dns-remote",
    strategy: disableIpv6 ? "ipv4_only" : "prefer_ipv4",
    independent_cache: true
  };

  applyTransport(config);
  if (!config.log || typeof config.log !== "object") config.log = {};
  config.log.level = "info";
  return config;
}
