/**
 * 默认覆写脚本。
 * overlay-revision: 27
 * 全地区识别分组。地区自动选择是对应地区选择组里的一个成员，不另做一张卡片。
 * 手动选择是 selector，自动选择是 urltest。sing-box 没有负载均衡和故障转移，这里不生成这两个组。
 * 设置里的配置覆盖开关走全局 overlay，默认开。不要在脚本里改这些开关。
 * 手机流量走 TUN（172.19.0.1/30，MTU 1500），不绑定本机 mixed 端口。
 * 禁用 QUIC 时拒绝 UDP 443，用复位，不用 drop。同时拒绝 HTTPS/SVCB 查询，避免油管 App 死守 HTTP/3。排除国内 QUIC 时，国内域名的 UDP 443 先走直连。
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

// 公告 / 信息节点。硬标记一律丢弃；软词只在名字里认不出地区时才丢（受「过滤非地区节点」控制）。
var infoHardFilter = /剩余|到期|过期|官网|订阅|重置|流量|已用|余额|有效期|\bexpire|\btraffic\b|\bhttps?:\/\/|t\.me\/|\bwww\./i;
var infoSoftFilter = /群|返利|循环|客服|网站|网址|获取|机场|下次|版本|官址|备用|联系|邮箱|工单|贩卖|通知|倒卖|防止|国内|地址|频道|电报|无法|说明|提示|访问|教程|关注|作者|加入|超时|收藏|优惠|福利|邀请|好友|失联|选择|公益|发布|DIZTNA|通路|登录|禁止|定时|渠道|牢记|永久|阁下|本站|刷新|导航|建议|以下|过滤|⚠️|@|\.(?:com|net|org|top|xyz|cc|io)\b/i;
// 倍率：数字 + x / × / 倍 / 倍率，或 x / × / 倍率 + 数字。低倍 = 免费 / 低倍 / free / 小于 1；高倍 = 高倍 / 不小于 2。两者互斥。
var rateSuffixRe = /(\d+(?:\.\d+)?)\s*(?:倍率|倍(?!率)|[x×])(?![a-z])(?!\s*[:：]?\s*\d)/i;
var ratePrefixRe = /(?:倍率|(?:^|[^a-z])x|×)\s*[:：]?\s*(\d+(?:\.\d+)?)/i;
var lowRateWords = /免费|低倍|\bfree\b/i;
var highRateWords = /高倍/;

var GEOSITE = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/";
var GEOIP = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/";

var REGIONS = [
    { name: "香港", flag: "🇭🇰", zh: "香港|九龙|九龍", en: "hong kong|hongkong", codes: "HK|HKG" },
    { name: "台湾省", flag: "🇹🇼", zh: "台湾|臺灣|台灣|台北|臺北|新北|台中|臺中|高雄|彰化|桃园|桃園", en: "taiwan|taipei|taichung|kaohsiung|changhua|hinet|hi net", codes: "TW|TWN" },
    { name: "澳门", flag: "🇲🇴", zh: "澳门|澳門", en: "macau|macao", codes: "MO" },
    { name: "日本", flag: "🇯🇵", zh: "日本|东京|大阪|東京|埼玉|名古屋|福冈|福岡|横滨|橫濱", en: "japan|tokyo|osaka|saitama|nagoya|fukuoka|yokohama", codes: "JP|JPN" },
    { name: "韩国", flag: "🇰🇷", zh: "韩国|首尔|韓國|南韩|南韓|首爾|釜山|春川|仁川", en: "korea|seoul|south korea|busan|chuncheon|incheon|southkorea", codes: "KR|KOR" },
    { name: "新加坡", flag: "🇸🇬", zh: "新加坡|狮城|獅城", en: "singapore", codes: "SG|SGP" },
    { name: "美国", flag: "🇺🇸", zh: "美国|洛杉矶|圣何塞|美國|洛杉磯|聖何塞|硅谷|矽谷|旧金山|舊金山|西雅图|西雅圖|芝加哥|纽约|紐約|达拉斯|達拉斯|凤凰城|鳳凰城|波特兰|波特蘭|弗里蒙特|阿什本|迈阿密|邁阿密|亚特兰大|拉斯维加斯|华盛顿|華盛頓|俄勒冈|弗吉尼亚|加州|马里兰|新墨西哥|新泽西|夏威夷|檀香山|奥克兰市", en: "america|united states|los angeles|san jose|usa|silicon valley|san francisco|seattle|chicago|new york|dallas|portland|fremont|ashburn|miami|atlanta|las vegas|washington|virginia|oregon|california|hawaii|honolulu|denver|santa clara|ohio|new jersey|new mexico|maryland|oakland|kansas city|unitedstates|losangeles|sanjose|siliconvalley|sanfrancisco|newyork|lasvegas|santaclara|newjersey|newmexico|kansascity", codes: "US|USA" },
    { name: "英国", flag: "🇬🇧", zh: "英国|伦敦|英國|倫敦|曼彻斯特|曼徹斯特", en: "united kingdom|london|great britain|britain|england|manchester|unitedkingdom|greatbritain", codes: "UK|GB|GBR" },
    { name: "德国", flag: "🇩🇪", zh: "德国|法兰克福|德國|法蘭克福|柏林|慕尼黑|杜塞尔多夫|纽伦堡", en: "germany|frankfurt|deutschland|berlin|munich|dusseldorf|nuremberg", codes: "DE|DEU" },
    { name: "荷兰", flag: "🇳🇱", zh: "荷兰|阿姆斯特丹|荷蘭", en: "netherlands|nederland|holland|amsterdam", codes: "NL|NLD" },
    { name: "马来西亚", flag: "🇲🇾", zh: "马来西亚|吉隆坡|馬來西亞", en: "malaysia|kuala lumpur|kualalumpur", codes: "MY|MYS" },
    { name: "泰国", flag: "🇹🇭", zh: "泰国|曼谷|泰國", en: "thailand|bangkok", codes: "TH|THA" },
    { name: "越南", flag: "🇻🇳", zh: "越南|河内|胡志明|河內", en: "vietnam|hanoi|ho chi minh|viet nam|hochiminh", codes: "VN|VNM" },
    { name: "菲律宾", flag: "🇵🇭", zh: "菲律宾|马尼拉|菲律賓|馬尼拉", en: "philippines|manila", codes: "PH|PHL" },
    { name: "印尼", flag: "🇮🇩", zh: "印尼|印度尼西亚|雅加达|印度尼西亞|雅加達", en: "indonesia|jakarta", codes: "ID|IDN" },
    { name: "印度", flag: "🇮🇳", zh: "印度|孟买|德里|孟買|班加罗尔", en: "india|mumbai|delhi|new delhi|bangalore|chennai|newdelhi", codes: "IN|IND" },
    { name: "澳大利亚", flag: "🇦🇺", zh: "澳大利亚|澳洲|悉尼|墨尔本|澳大利亞|雪梨|墨爾本", en: "australia|sydney|melbourne", codes: "AU|AUS" },
    { name: "法国", flag: "🇫🇷", zh: "法国|巴黎|法國|马赛|馬賽", en: "france|paris|marseille", codes: "FR|FRA" },
    { name: "俄罗斯", flag: "🇷🇺", zh: "俄罗斯|莫斯科|俄羅斯|圣彼得堡|聖彼得堡|新西伯利亚|伯力|海参崴|符拉迪沃斯托克", en: "russia|moscow|saint petersburg|st petersburg|novosibirsk|khabarovsk|vladivostok|saintpetersburg|stpetersburg", codes: "RU|RUS" },
    { name: "意大利", flag: "🇮🇹", zh: "意大利|罗马|義大利|米兰|米蘭", en: "italy|rome|milan", codes: "IT" },
    { name: "加拿大", flag: "🇨🇦", zh: "加拿大|多伦多|多倫多|温哥华|溫哥華|蒙特利尔", en: "canada|toronto|vancouver|montreal", codes: "CA|CAN" },
    { name: "阿根廷", flag: "🇦🇷", zh: "阿根廷|布宜诺斯艾利斯", en: "argentina|buenos aires|buenosaires", codes: "AR|ARG" },
    { name: "巴西", flag: "🇧🇷", zh: "巴西|圣保罗|聖保羅", en: "brazil|sao paulo|saopaulo", codes: "BR|BRA" },
    { name: "墨西哥", flag: "🇲🇽", zh: "墨西哥", en: "mexico", codes: "MX|MEX" },
    { name: "沙特阿拉伯", flag: "🇸🇦", zh: "沙特阿拉伯|沙特", en: "saudi arabia|saudiarabia" },
    { name: "南非", flag: "🇿🇦", zh: "南非|约翰内斯堡", en: "south africa|johannesburg|southafrica", codes: "ZA" },
    { name: "土耳其", flag: "🇹🇷", zh: "土耳其|伊斯坦布尔|伊斯坦堡", en: "turkey|istanbul|turkiye|ankara", codes: "TR|TUR" },
    { name: "文莱", flag: "🇧🇳", zh: "文莱", en: "brunei", codes: "BN" },
    { name: "柬埔寨", flag: "🇰🇭", zh: "柬埔寨|金边", en: "cambodia|phnom penh|phnompenh", codes: "KH" },
    { name: "老挝", flag: "🇱🇦", zh: "老挝|万象", en: "laos|vientiane" },
    { name: "缅甸", flag: "🇲🇲", zh: "缅甸|仰光", en: "myanmar|yangon", codes: "MM" },
    { name: "奥地利", flag: "🇦🇹", zh: "奥地利|维也纳", en: "austria|vienna", codes: "AT" },
    { name: "比利时", flag: "🇧🇪", zh: "比利时|布鲁塞尔", en: "belgium|brussels", codes: "BE" },
    { name: "保加利亚", flag: "🇧🇬", zh: "保加利亚|索非亚", en: "bulgaria|sofia", codes: "BG" },
    { name: "克罗地亚", flag: "🇭🇷", zh: "克罗地亚", en: "croatia", codes: "HR" },
    { name: "塞浦路斯", flag: "🇨🇾", zh: "塞浦路斯", en: "cyprus", codes: "CY" },
    { name: "捷克", flag: "🇨🇿", zh: "捷克|捷克共和国|布拉格", en: "czech|prague", codes: "CZ" },
    { name: "丹麦", flag: "🇩🇰", zh: "丹麦|哥本哈根", en: "denmark|copenhagen", codes: "DK" },
    { name: "爱沙尼亚", flag: "🇪🇪", zh: "爱沙尼亚", en: "estonia", codes: "EE" },
    { name: "芬兰", flag: "🇫🇮", zh: "芬兰|赫尔辛基", en: "finland|helsinki", codes: "FI" },
    { name: "希腊", flag: "🇬🇷", zh: "希腊|雅典", en: "greece|athens", codes: "GR" },
    { name: "匈牙利", flag: "🇭🇺", zh: "匈牙利|布达佩斯", en: "hungary|budapest", codes: "HU" },
    { name: "爱尔兰", flag: "🇮🇪", zh: "爱尔兰|都柏林", en: "ireland|dublin", codes: "IE" },
    { name: "拉脱维亚", flag: "🇱🇻", zh: "拉脱维亚", en: "latvia", codes: "LV" },
    { name: "立陶宛", flag: "🇱🇹", zh: "立陶宛", en: "lithuania", codes: "LT" },
    { name: "卢森堡", flag: "🇱🇺", zh: "卢森堡", en: "luxembourg", codes: "LU" },
    { name: "马耳他", flag: "🇲🇹", zh: "马耳他", en: "malta", codes: "MT" },
    { name: "波兰", flag: "🇵🇱", zh: "波兰|华沙", en: "poland|warsaw", codes: "PL" },
    { name: "葡萄牙", flag: "🇵🇹", zh: "葡萄牙|里斯本", en: "portugal|lisbon", codes: "PT" },
    { name: "罗马尼亚", flag: "🇷🇴", zh: "罗马尼亚|布加勒斯特", en: "romania|bucharest", codes: "RO" },
    { name: "斯洛伐克", flag: "🇸🇰", zh: "斯洛伐克", en: "slovakia", codes: "SK" },
    { name: "斯洛文尼亚", flag: "🇸🇮", zh: "斯洛文尼亚", en: "slovenia", codes: "SI" },
    { name: "西班牙", flag: "🇪🇸", zh: "西班牙|马德里|巴塞罗那", en: "spain|madrid|barcelona", codes: "ES" },
    { name: "瑞典", flag: "🇸🇪", zh: "瑞典|斯德哥尔摩", en: "sweden|stockholm", codes: "SE" },
    { name: "阿尔及利亚", flag: "🇩🇿", zh: "阿尔及利亚", en: "algeria" },
    { name: "安哥拉", flag: "🇦🇴", zh: "安哥拉", en: "angola" },
    { name: "贝宁", flag: "🇧🇯", zh: "贝宁", en: "benin" },
    { name: "博茨瓦纳", flag: "🇧🇼", zh: "博茨瓦纳", en: "botswana" },
    { name: "布基纳法索", flag: "🇧🇫", zh: "布基纳法索", en: "burkina faso|burkinafaso" },
    { name: "布隆迪", flag: "🇧🇮", zh: "布隆迪", en: "burundi" },
    { name: "佛得角", flag: "🇨🇻", zh: "佛得角", en: "cabo verde|cape verde|caboverde|capeverde" },
    { name: "喀麦隆", flag: "🇨🇲", zh: "喀麦隆", en: "cameroon" },
    { name: "中非共和国", flag: "🇨🇫", zh: "中非共和国|中非", en: "central african|centralafrican" },
    { name: "乍得", flag: "🇹🇩", zh: "乍得", en: "chad" },
    { name: "科摩罗", flag: "🇰🇲", zh: "科摩罗", en: "comoros" },
    { name: "刚果共和国", flag: "🇨🇬", zh: "刚果共和国|刚果（布）", en: "congo" },
    { name: "刚果民主共和国", flag: "🇨🇩", zh: "刚果民主共和国|刚果（金）|民主刚果", en: "dr congo|democratic republic of the congo|drcongo|democraticrepublicofthecongo" },
    { name: "科特迪瓦", flag: "🇨🇮", zh: "科特迪瓦|象牙海岸", en: "cote d ivoire|cote divoire|ivory coast|cotedivoire|ivorycoast" },
    { name: "吉布提", flag: "🇩🇯", zh: "吉布提", en: "djibouti" },
    { name: "埃及", flag: "🇪🇬", zh: "埃及|开罗", en: "egypt|cairo", codes: "EG" },
    { name: "赤道几内亚", flag: "🇬🇶", zh: "赤道几内亚", en: "equatorial guinea|equatorialguinea" },
    { name: "厄立特里亚", flag: "🇪🇷", zh: "厄立特里亚", en: "eritrea" },
    { name: "斯威士兰", flag: "🇸🇿", zh: "斯威士兰|埃斯瓦蒂尼", en: "eswatini|swaziland" },
    { name: "埃塞俄比亚", flag: "🇪🇹", zh: "埃塞俄比亚", en: "ethiopia" },
    { name: "加蓬", flag: "🇬🇦", zh: "加蓬", en: "gabon" },
    { name: "冈比亚", flag: "🇬🇲", zh: "冈比亚", en: "gambia" },
    { name: "加纳", flag: "🇬🇭", zh: "加纳", en: "ghana" },
    { name: "几内亚", flag: "🇬🇳", zh: "几内亚", en: "guinea" },
    { name: "几内亚比绍", flag: "🇬🇼", zh: "几内亚比绍", en: "guinea bissau|guineabissau" },
    { name: "肯尼亚", flag: "🇰🇪", zh: "肯尼亚|内罗毕", en: "kenya|nairobi", codes: "KE" },
    { name: "莱索托", flag: "🇱🇸", zh: "莱索托", en: "lesotho" },
    { name: "利比里亚", flag: "🇱🇷", zh: "利比里亚", en: "liberia" },
    { name: "利比亚", flag: "🇱🇾", zh: "利比亚", en: "libya" },
    { name: "马达加斯加", flag: "🇲🇬", zh: "马达加斯加", en: "madagascar" },
    { name: "马拉维", flag: "🇲🇼", zh: "马拉维", en: "malawi" },
    { name: "马里", flag: "🇲🇱", zh: "马里", en: "mali" },
    { name: "毛里塔尼亚", flag: "🇲🇷", zh: "毛里塔尼亚", en: "mauritania" },
    { name: "毛里求斯", flag: "🇲🇺", zh: "毛里求斯", en: "mauritius" },
    { name: "摩洛哥", flag: "🇲🇦", zh: "摩洛哥|卡萨布兰卡", en: "morocco|casablanca" },
    { name: "莫桑比克", flag: "🇲🇿", zh: "莫桑比克", en: "mozambique" },
    { name: "纳米比亚", flag: "🇳🇦", zh: "纳米比亚", en: "namibia" },
    { name: "尼日尔", flag: "🇳🇪", zh: "尼日尔", en: "niger" },
    { name: "尼日利亚", flag: "🇳🇬", zh: "尼日利亚|拉各斯", en: "nigeria|lagos", codes: "NG" },
    { name: "卢旺达", flag: "🇷🇼", zh: "卢旺达", en: "rwanda" },
    { name: "圣多美和普林西比", flag: "🇸🇹", zh: "圣多美和普林西比", en: "sao tome|saotome" },
    { name: "塞内加尔", flag: "🇸🇳", zh: "塞内加尔", en: "senegal" },
    { name: "塞舌尔", flag: "🇸🇨", zh: "塞舌尔", en: "seychelles" },
    { name: "塞拉利昂", flag: "🇸🇱", zh: "塞拉利昂", en: "sierra leone|sierraleone" },
    { name: "索马里", flag: "🇸🇴", zh: "索马里", en: "somalia" },
    { name: "南苏丹", flag: "🇸🇸", zh: "南苏丹", en: "south sudan|southsudan" },
    { name: "苏丹", flag: "🇸🇩", zh: "苏丹", en: "sudan" },
    { name: "坦桑尼亚", flag: "🇹🇿", zh: "坦桑尼亚", en: "tanzania" },
    { name: "多哥", flag: "🇹🇬", zh: "多哥", en: "togo" },
    { name: "突尼斯", flag: "🇹🇳", zh: "突尼斯", en: "tunisia" },
    { name: "乌干达", flag: "🇺🇬", zh: "乌干达", en: "uganda" },
    { name: "赞比亚", flag: "🇿🇲", zh: "赞比亚", en: "zambia" },
    { name: "津巴布韦", flag: "🇿🇼", zh: "津巴布韦", en: "zimbabwe" },
    { name: "阿富汗", flag: "🇦🇫", zh: "阿富汗", en: "afghanistan" },
    { name: "亚美尼亚", flag: "🇦🇲", zh: "亚美尼亚", en: "armenia" },
    { name: "阿塞拜疆", flag: "🇦🇿", zh: "阿塞拜疆", en: "azerbaijan" },
    { name: "巴林", flag: "🇧🇭", zh: "巴林", en: "bahrain" },
    { name: "孟加拉国", flag: "🇧🇩", zh: "孟加拉国", en: "bangladesh", codes: "BD" },
    { name: "不丹", flag: "🇧🇹", zh: "不丹", en: "bhutan" },
    { name: "格鲁吉亚", flag: "🇬🇪", zh: "格鲁吉亚", en: "georgia" },
    { name: "伊朗", flag: "🇮🇷", zh: "伊朗", en: "iran" },
    { name: "伊拉克", flag: "🇮🇶", zh: "伊拉克", en: "iraq" },
    { name: "以色列", flag: "🇮🇱", zh: "以色列|特拉维夫", en: "israel|tel aviv|telaviv", codes: "IL" },
    { name: "约旦", flag: "🇯🇴", zh: "约旦", en: "jordan" },
    { name: "哈萨克斯坦", flag: "🇰🇿", zh: "哈萨克斯坦|阿拉木图", en: "kazakhstan|almaty", codes: "KZ" },
    { name: "科威特", flag: "🇰🇼", zh: "科威特", en: "kuwait" },
    { name: "吉尔吉斯斯坦", flag: "🇰🇬", zh: "吉尔吉斯斯坦", en: "kyrgyzstan" },
    { name: "黎巴嫩", flag: "🇱🇧", zh: "黎巴嫩", en: "lebanon" },
    { name: "马尔代夫", flag: "🇲🇻", zh: "马尔代夫", en: "maldives" },
    { name: "蒙古", flag: "🇲🇳", zh: "蒙古", en: "mongolia", codes: "MN" },
    { name: "尼泊尔", flag: "🇳🇵", zh: "尼泊尔", en: "nepal" },
    { name: "朝鲜", flag: "🇰🇵", zh: "朝鲜|北韩|北韓", en: "north korea|dprk|northkorea" },
    { name: "阿曼", flag: "🇴🇲", zh: "阿曼", en: "oman" },
    { name: "巴基斯坦", flag: "🇵🇰", zh: "巴基斯坦", en: "pakistan", codes: "PK" },
    { name: "巴勒斯坦", flag: "🇵🇸", zh: "巴勒斯坦", en: "palestine|west bank|gaza|westbank" },
    { name: "卡塔尔", flag: "🇶🇦", zh: "卡塔尔", en: "qatar" },
    { name: "斯里兰卡", flag: "🇱🇰", zh: "斯里兰卡", en: "sri lanka|srilanka", codes: "LK" },
    { name: "叙利亚", flag: "🇸🇾", zh: "叙利亚", en: "syria" },
    { name: "塔吉克斯坦", flag: "🇹🇯", zh: "塔吉克斯坦", en: "tajikistan" },
    { name: "东帝汶", flag: "🇹🇱", zh: "东帝汶", en: "timor leste|east timor|timorleste|easttimor" },
    { name: "土库曼斯坦", flag: "🇹🇲", zh: "土库曼斯坦", en: "turkmenistan" },
    { name: "阿联酋", flag: "🇦🇪", zh: "阿联酋|迪拜|阿布扎比|阿聯酋", en: "dubai|abu dhabi|uae|united arab emirates|abudhabi|unitedarabemirates", codes: "AE" },
    { name: "乌兹别克斯坦", flag: "🇺🇿", zh: "乌兹别克斯坦", en: "uzbekistan" },
    { name: "也门", flag: "🇾🇪", zh: "也门", en: "yemen" },
    { name: "阿尔巴尼亚", flag: "🇦🇱", zh: "阿尔巴尼亚", en: "albania" },
    { name: "安道尔", flag: "🇦🇩", zh: "安道尔", en: "andorra" },
    { name: "白俄罗斯", flag: "🇧🇾", zh: "白俄罗斯", en: "belarus" },
    { name: "波斯尼亚和黑塞哥维那", flag: "🇧🇦", zh: "波斯尼亚和黑塞哥维那", en: "bosnia|bosnia and herzegovina|bosniaandherzegovina" },
    { name: "冰岛", flag: "🇮🇸", zh: "冰岛", en: "iceland" },
    { name: "列支敦士登", flag: "🇱🇮", zh: "列支敦士登", en: "liechtenstein" },
    { name: "摩尔多瓦", flag: "🇲🇩", zh: "摩尔多瓦", en: "moldova" },
    { name: "摩纳哥", flag: "🇲🇨", zh: "摩纳哥", en: "monaco" },
    { name: "黑山", flag: "🇲🇪", zh: "黑山", en: "montenegro" },
    { name: "北马其顿", flag: "🇲🇰", zh: "北马其顿", en: "north macedonia|macedonia|northmacedonia" },
    { name: "挪威", flag: "🇳🇴", zh: "挪威", en: "norway" },
    { name: "圣马力诺", flag: "🇸🇲", zh: "圣马力诺", en: "san marino|sanmarino" },
    { name: "塞尔维亚", flag: "🇷🇸", zh: "塞尔维亚", en: "serbia", codes: "RS" },
    { name: "瑞士", flag: "🇨🇭", zh: "瑞士|苏黎世|蘇黎世", en: "switzerland|zurich|geneva", codes: "CH" },
    { name: "乌克兰", flag: "🇺🇦", zh: "乌克兰|烏克蘭|基辅", en: "ukraine|kyiv|kiev", codes: "UA" },
    { name: "梵蒂冈", flag: "🇻🇦", zh: "梵蒂冈", en: "vatican" },
    { name: "科索沃", flag: "🇽🇰", zh: "科索沃", en: "kosovo" },
    { name: "巴哈马", flag: "🇧🇸", zh: "巴哈马", en: "bahamas" },
    { name: "巴巴多斯", flag: "🇧🇧", zh: "巴巴多斯", en: "barbados" },
    { name: "伯利兹", flag: "🇧🇿", zh: "伯利兹", en: "belize" },
    { name: "哥斯达黎加", flag: "🇨🇷", zh: "哥斯达黎加", en: "costa rica|costarica" },
    { name: "古巴", flag: "🇨🇺", zh: "古巴", en: "cuba" },
    { name: "多米尼克", flag: "🇩🇲", zh: "多米尼克", en: "dominica" },
    { name: "多米尼加", flag: "🇩🇴", zh: "多米尼加", en: "dominican republic|dominican|dominicanrepublic" },
    { name: "萨尔瓦多", flag: "🇸🇻", zh: "萨尔瓦多", en: "el salvador|elsalvador" },
    { name: "格林纳达", flag: "🇬🇩", zh: "格林纳达", en: "grenada" },
    { name: "危地马拉", flag: "🇬🇹", zh: "危地马拉", en: "guatemala" },
    { name: "海地", flag: "🇭🇹", zh: "海地", en: "haiti" },
    { name: "洪都拉斯", flag: "🇭🇳", zh: "洪都拉斯", en: "honduras" },
    { name: "牙买加", flag: "🇯🇲", zh: "牙买加", en: "jamaica" },
    { name: "尼加拉瓜", flag: "🇳🇮", zh: "尼加拉瓜", en: "nicaragua" },
    { name: "巴拿马", flag: "🇵🇦", zh: "巴拿马", en: "panama" },
    { name: "圣基茨和尼维斯", flag: "🇰🇳", zh: "圣基茨和尼维斯", en: "saint kitts|st kitts|saintkitts|stkitts" },
    { name: "圣卢西亚", flag: "🇱🇨", zh: "圣卢西亚", en: "saint lucia|st lucia|saintlucia|stlucia" },
    { name: "圣文森特和格林纳丁斯", flag: "🇻🇨", zh: "圣文森特和格林纳丁斯", en: "saint vincent|st vincent|saintvincent|stvincent" },
    { name: "特立尼达和多巴哥", flag: "🇹🇹", zh: "特立尼达和多巴哥", en: "trinidad and tobago|trinidadandtobago" },
    { name: "安提瓜和巴布达", flag: "🇦🇬", zh: "安提瓜和巴布达", en: "antigua and barbuda|antiguaandbarbuda" },
    { name: "哥伦比亚", flag: "🇨🇴", zh: "哥伦比亚|波哥大|麦德林", en: "bogota|medellin|colombia", codes: "CO" },
    { name: "智利", flag: "🇨🇱", zh: "智利", en: "chile", codes: "CL" },
    { name: "秘鲁", flag: "🇵🇪", zh: "秘鲁", en: "peru", codes: "PE" },
    { name: "乌拉圭", flag: "🇺🇾", zh: "乌拉圭", en: "uruguay" },
    { name: "巴拉圭", flag: "🇵🇾", zh: "巴拉圭", en: "paraguay" },
    { name: "玻利维亚", flag: "🇧🇴", zh: "玻利维亚", en: "bolivia" },
    { name: "厄瓜多尔", flag: "🇪🇨", zh: "厄瓜多尔", en: "ecuador" },
    { name: "委内瑞拉", flag: "🇻🇪", zh: "委内瑞拉", en: "venezuela" },
    { name: "圭亚那", flag: "🇬🇾", zh: "圭亚那", en: "guyana" },
    { name: "苏里南", flag: "🇸🇷", zh: "苏里南", en: "suriname" },
    { name: "新西兰", flag: "🇳🇿", zh: "新西兰|奥克兰|惠灵顿|紐西蘭", en: "new zealand|auckland|wellington|newzealand", codes: "NZ" },
    { name: "斐济", flag: "🇫🇯", zh: "斐济", en: "fiji" },
    { name: "巴布亚新几内亚", flag: "🇵🇬", zh: "巴布亚新几内亚", en: "papua new guinea|papuanewguinea" },
    { name: "所罗门群岛", flag: "🇸🇧", zh: "所罗门群岛", en: "solomon islands|solomonislands" },
    { name: "瓦努阿图", flag: "🇻🇺", zh: "瓦努阿图", en: "vanuatu" },
    { name: "萨摩亚", flag: "🇼🇸", zh: "萨摩亚", en: "samoa" },
    { name: "汤加", flag: "🇹🇴", zh: "汤加", en: "tonga" },
    { name: "基里巴斯", flag: "🇰🇮", zh: "基里巴斯", en: "kiribati" },
    { name: "图瓦卢", flag: "🇹🇻", zh: "图瓦卢", en: "tuvalu" },
    { name: "瑙鲁", flag: "🇳🇷", zh: "瑙鲁", en: "nauru" },
    { name: "帕劳", flag: "🇵🇼", zh: "帕劳", en: "palau" },
    { name: "马绍尔群岛", flag: "🇲🇭", zh: "马绍尔群岛", en: "marshall islands|marshallislands" },
    { name: "密克罗尼西亚", flag: "🇫🇲", zh: "密克罗尼西亚", en: "micronesia|federated states of micronesia|federatedstatesofmicronesia" },
    { name: "关岛", flag: "🇬🇺", zh: "关岛", en: "guam" },
    { name: "波多黎各", flag: "🇵🇷", zh: "波多黎各", en: "puerto rico|puertorico" },
    { name: "百慕大", flag: "🇧🇲", zh: "百慕大", en: "bermuda" },
    { name: "格陵兰", flag: "🇬🇱", zh: "格陵兰", en: "greenland" },
    { name: "库拉索", flag: "🇨🇼", zh: "库拉索", en: "curacao" },
    { name: "阿鲁巴", flag: "🇦🇼", zh: "阿鲁巴", en: "aruba" },
    { name: "开曼群岛", flag: "🇰🇾", zh: "开曼群岛", en: "cayman islands|caymanislands" },
    { name: "英属维尔京群岛", flag: "🇻🇬", zh: "英属维尔京群岛", en: "british virgin islands|britishvirginislands" },
    { name: "美属维尔京群岛", flag: "🇻🇮", zh: "美属维尔京群岛", en: "us virgin islands|u s virgin islands|usvirginislands" },
    { name: "新喀里多尼亚", flag: "🇳🇨", zh: "新喀里多尼亚", en: "new caledonia|newcaledonia" },
    { name: "法属波利尼西亚", flag: "🇵🇫", zh: "法属波利尼西亚", en: "french polynesia|frenchpolynesia" },
    { name: "库克群岛", flag: "🇨🇰", zh: "库克群岛", en: "cook islands|cookislands" },
    { name: "纽埃", flag: "🇳🇺", zh: "纽埃", en: "niue" }
];

// 不算地区的词：先从名字里去掉再识别（内蒙古不是蒙古；各大洲不是美国）。
var REGION_BLOCKLIST = ["内蒙古", "內蒙古"];
var regionBlockEn = / (?:south|latin|central|north) ?america(?= )/g;

// 识别结果。名字只归一个地区：国旗 → 中文名 → 英文名 → 大写代码（白名单），同一层取最左，同一位置取最长。
var CN_DNS_TAG = "dns-cn";
var regionIndex = null;

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\\/]/g, "\\$&");
}
function foldAccents(s) {
  return s
    .replace(/[áàâäãå]/gi, "a")
    .replace(/[éèêë]/gi, "e")
    .replace(/[íìîï]/gi, "i")
    .replace(/[óòôöõ]/gi, "o")
    .replace(/[úùûü]/gi, "u")
    .replace(/ç/gi, "c")
    .replace(/ñ/gi, "n");
}
// HongKong01 / HK_01 / US-LA → "Hong Kong 01" / "HK 01" / "US LA"，两头各留一个空格。
function normalizeName(s) {
  s = foldAccents("" + s)
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2")
    .replace(/([A-Za-z])([0-9])/g, "$1 $2")
    .replace(/([0-9])([A-Za-z])/g, "$1 $2")
    .replace(/[^A-Za-z0-9]+/g, " ");
  return " " + s.replace(/^ +| +$/g, "") + " ";
}
function splitAliases(v) {
  if (!v) return [];
  return ("" + v).split("|");
}
function buildTier(pick, fold) {
  var map = {};
  var alts = [];
  for (var i = 0; i < REGIONS.length; i++) {
    var list = pick(REGIONS[i]);
    for (var j = 0; j < list.length; j++) {
      var key = fold ? fold(list[j]) : list[j];
      if (!key || Object.prototype.hasOwnProperty.call(map, key)) continue;
      map[key] = REGIONS[i];
      alts.push(key);
    }
  }
  alts.sort(function (a, b) { return b.length - a.length; });
  var parts = [];
  for (var k = 0; k < alts.length; k++) parts.push(escapeRe(alts[k]));
  return { map: map, body: parts.join("|") };
}
function getRegionIndex() {
  if (regionIndex) return regionIndex;
  var flag = buildTier(function (r) { return r.flag ? [r.flag] : []; });
  var zh = buildTier(function (r) { return splitAliases(r.zh); });
  var en = buildTier(function (r) { return splitAliases(r.en); }, function (a) {
    return normalizeName(a).toLowerCase().replace(/^ +| +$/g, "");
  });
  var codes = buildTier(function (r) { return splitAliases(r.codes); });
  regionIndex = {
    flag: { map: flag.map, re: new RegExp("(" + flag.body + ")") },
    zh: { map: zh.map, re: new RegExp("(" + zh.body + ")") },
    en: { map: en.map, re: new RegExp(" (" + en.body + ")(?= )") },
    codes: { map: codes.map, re: new RegExp(" (" + codes.body + ")(?= )") }
  };
  return regionIndex;
}
function matchRegion(name) {
  var idx = getRegionIndex();
  var raw = "" + name;
  for (var b = 0; b < REGION_BLOCKLIST.length; b++) {
    if (raw.indexOf(REGION_BLOCKLIST[b]) >= 0) raw = raw.split(REGION_BLOCKLIST[b]).join(" ");
  }
  var m = idx.flag.re.exec(raw);
  if (m) return idx.flag.map[m[1]];
  m = idx.zh.re.exec(raw);
  if (m) return idx.zh.map[m[1]];
  var norm = normalizeName(raw);
  var lower = norm.toLowerCase().replace(regionBlockEn, " ");
  m = idx.en.re.exec(lower);
  if (m) return idx.en.map[m[1]];
  m = idx.codes.re.exec(norm);
  if (m) return idx.codes.map[m[1]];
  return null;
}
function regionLabel(region) {
  return (region.flag ? region.flag + " " : "") + region.name;
}

function isAnnouncement(name, hasRegion) {
  if (infoHardFilter.test(name)) return true;
  if (hasRegion || !on("过滤非地区节点")) return false;
  return infoSoftFilter.test(name);
}
function rateOf(name) {
  var m = rateSuffixRe.exec(name) || ratePrefixRe.exec(name);
  if (!m) return -1;
  var v = parseFloat(m[1]);
  return isNaN(v) ? -1 : v;
}
// "low" / "high" / ""，互斥。
function rateClass(name) {
  if (lowRateWords.test(name)) return "low";
  if (highRateWords.test(name)) return "high";
  var r = rateOf(name);
  if (r < 0) return "";
  if (r < 1) return "low";
  if (r >= 2) return "high";
  return "";
}

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
function copyTags(list) {
  var out = [];
  for (var i = 0; i < list.length; i++) out.push(list[i]);
  return out;
}
// 去重合并，O(n)。
function concatTags(a, b) {
  var out = [];
  var seen = {};
  var lists = [a, b];
  for (var l = 0; l < lists.length; l++) {
    for (var i = 0; i < lists[l].length; i++) {
      var t = lists[l][i];
      if (seen["$" + t]) continue;
      seen["$" + t] = 1;
      out.push(t);
    }
  }
  return out;
}
// 名字被占用时改成 "名字 (2)"、"名字 (3)"……
function uniqueTag(tag, taken) {
  if (!taken["$" + tag]) {
    taken["$" + tag] = 1;
    return tag;
  }
  var n = 2;
  while (taken["$" + tag + " (" + n + ")"]) n++;
  var next = tag + " (" + n + ")";
  taken["$" + next] = 1;
  return next;
}

var GROUP = {
  selector: 1, urltest: 1, direct: 1, block: 1, dns: 1, relay: 1, chain: 1
};
var SERVICE_NAMES = [
  "AdBlock", "FCM", "YouTube", "Google", "ClaudeAI", "AI", "Microsoft", "Apple", "Telegram", "Steam",
  "TikTok", "Twitter", "Meta", "Line", "Netflix", "Emby", "PikPak", "Spotify", "Crypto", "EHentai", "远控工具"
];
var DIRECT_VARIANTS = [
  ["🇨🇳 直连 | 双栈", ""],
  ["🇨🇳 直连 | IPv4优先", "prefer_ipv4"],
  ["🇨🇳 直连 | IPv6优先", "prefer_ipv6"],
  ["🇨🇳 直连 | 仅IPv4", "ipv4_only"],
  ["🇨🇳 直连 | 仅IPv6", "ipv6_only"]
];
// 脚本自己要生成的出站名。节点撞上这些名字时改名，避免重复标签。
function reservedTags() {
  var taken = {};
  var fixed = [
    "默认代理", "手动选择", "自动选择", "直连", "漏网之鱼", "REJECT", "angela-direct",
    "其他节点", "其他节点-自动选择", "低倍率节点", "高倍率节点"
  ];
  for (var i = 0; i < fixed.length; i++) taken["$" + fixed[i]] = 1;
  for (var s = 0; s < SERVICE_NAMES.length; s++) taken["$" + SERVICE_NAMES[s]] = 1;
  for (var d = 0; d < DIRECT_VARIANTS.length; d++) taken["$" + DIRECT_VARIANTS[d][0]] = 1;
  for (var r = 0; r < REGIONS.length; r++) {
    var label = regionLabel(REGIONS[r]);
    taken["$" + label] = 1;
    taken["$" + label + "-自动选择"] = 1;
  }
  return taken;
}

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
// sing-box 1.12 起出站的 domain_strategy 已弃用（1.14 移除），改写成 domain_resolver。
// 解析服务器固定用本脚本写入的国内 DNS（CN_DNS_TAG）：脚本会整体替换 dns.servers，订阅里原来的服务器标签不再存在；
// 走 dns-remote 又会绕回代理自己。
function applyResolver(item, strategy) {
  var st = strategy || ("" + (item.domain_strategy || ""));
  var cur = item.domain_resolver;
  if (cur && typeof cur === "object") {
    cur.server = CN_DNS_TAG;
    if (strategy || (!cur.strategy && st)) cur.strategy = st;
  } else if (cur || st) {
    item.domain_resolver = st ? { server: CN_DNS_TAG, strategy: st } : CN_DNS_TAG;
  }
  if (typeof item.domain_strategy !== "undefined") delete item.domain_strategy;
}
function directOut(tag, strategy) {
  var o = { type: "direct", tag: tag, connect_timeout: "8s" };
  if (strategy) o.domain_resolver = { server: CN_DNS_TAG, strategy: strategy };
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
// 收尾：删掉组里指向不存在出站的成员，删掉空组（可能连锁），修正 default；再删掉指向不存在出站的路由规则。
function pruneGroups(list, extraTags) {
  var changed = true;
  while (changed) {
    changed = false;
    var have = {};
    for (var e = 0; e < extraTags.length; e++) have["$" + extraTags[e]] = 1;
    for (var h = 0; h < list.length; h++) have["$" + tagOf(list[h])] = 1;
    var kept = [];
    for (var i = 0; i < list.length; i++) {
      var ob = list[i];
      var t = typeOf(ob);
      if (t === "selector" || t === "urltest") {
        var members = isArray(ob.outbounds) ? ob.outbounds : [];
        var live = [];
        for (var m = 0; m < members.length; m++) {
          if (have["$" + members[m]] && members[m] !== tagOf(ob)) live.push(members[m]);
        }
        if (live.length === 0) {
          changed = true;
          continue;
        }
        ob.outbounds = live;
        if (ob["default"] && live.indexOf(ob["default"]) < 0) delete ob["default"];
      }
      kept.push(ob);
    }
    list = kept;
  }
  return list;
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
  var endpoints = isArray(config.endpoints) ? config.endpoints : [];

  var strategy = "";
  if (on("代理IPV4优先") && !on("代理IPV6优先")) strategy = "prefer_ipv4";
  if (on("代理IPV6优先") && !on("代理IPV4优先")) strategy = "prefer_ipv6";

  // 标签占用表：脚本生成的组名、已有 direct、不参与分组的端点先占位，节点重名时改成 "名字 (2)"。
  var taken = reservedTags();
  var existingDirect = null;
  var directNameTaken = false;
  for (var d = 0; d < outbounds.length; d++) {
    if (tagOf(outbounds[d]) !== "direct") continue;
    if (typeOf(outbounds[d]) === "direct" && !existingDirect) existingDirect = outbounds[d];
    else directNameTaken = true;
  }
  if (existingDirect) taken["$direct"] = 1;
  var keptEndpoints = [];
  var leafEndpoints = [];
  for (var ep = 0; ep < endpoints.length; ep++) {
    var endpoint = endpoints[ep];
    if (!endpoint || typeof endpoint !== "object" || !tagOf(endpoint)) continue;
    keptEndpoints.push(endpoint);
    if (typeOf(endpoint) === "wireguard") leafEndpoints.push(endpoint);
    else endpoint.tag = uniqueTag(tagOf(endpoint), taken);
  }

  var leaves = [];
  var leafTags = [];
  var leafRegion = [];
  var leafRate = [];
  var endpointTags = [];
  function adopt(item, isEndpoint) {
    var name = tagOf(item);
    if (!name) return;
    var region = matchRegion(name);
    var rate = rateClass(name);
    var drop = isAnnouncement(name, !!region);
    if (!drop && on("过滤低倍率节点") && rate === "low") drop = true;
    if (!drop && on("过滤高倍率节点") && rate === "high") drop = true;
    if (isEndpoint) {
      // 端点留在 endpoints 里；被过滤的只是不进分组。
      item.tag = uniqueTag(name, taken);
      endpointTags.push(item.tag);
      if (drop) return;
    } else {
      if (drop) return;
      item.tag = uniqueTag(name, taken);
      if (item.name && item.name !== item.tag) delete item.name;
    }
    if (item.detour) delete item.detour;
    if (item["dialer-proxy"]) delete item["dialer-proxy"];
    applyResolver(item, strategy);
    if (!isEndpoint) {
      item.tcp_keep_alive = "60s";
      if (!item.tcp_keep_alive_interval) item.tcp_keep_alive_interval = "60s";
    }
    leaves.push(isEndpoint ? null : item);
    leafTags.push(item.tag);
    leafRegion.push(region);
    leafRate.push(rate);
  }
  for (var i = 0; i < outbounds.length; i++) {
    if (isLeaf(outbounds[i])) adopt(outbounds[i], false);
  }
  for (var we = 0; we < leafEndpoints.length; we++) adopt(leafEndpoints[we], true);
  var customSeen = {};
  for (var lt = 0; lt < leafTags.length; lt++) customSeen["$" + leafTags[lt]] = 1;
  for (var c = 0; c < customizeProxies.length; c++) {
    var custom = customizeProxies[c];
    if (!custom || typeof custom !== "object") continue;
    if (!custom.tag && custom.name) custom.tag = custom.name;
    var ct = tagOf(custom);
    if (!ct || customSeen["$" + ct]) continue;
    customSeen["$" + ct] = 1;
    adopt(custom, false);
  }
  if (leafTags.length === 0) {
    applyTransport(config);
    return config;
  }

  var buckets = {};
  var other = [];
  var low = [];
  var high = [];
  for (var b = 0; b < REGIONS.length; b++) buckets["$" + REGIONS[b].name] = [];
  for (var n = 0; n < leafTags.length; n++) {
    if (leafRegion[n]) buckets["$" + leafRegion[n].name].push(leafTags[n]);
    else other.push(leafTags[n]);
    if (leafRate[n] === "low") low.push(leafTags[n]);
    else if (leafRate[n] === "high") high.push(leafTags[n]);
  }

  var regionSelect = [];
  var regionGroups = [];
  function addRegion(label, members) {
    var manualMembers = copyTags(members);
    var autoName = "";
    if (on("生成地区自动选择组")) {
      autoName = label + "-自动选择";
      regionGroups.push(urltest(autoName, members));
      manualMembers.push(autoName);
    }
    if (!on("隐藏地区手动选择组")) {
      // 地区选择组默认落在它的自动选择成员上，而不是第一个节点。
      regionGroups.push(selector(label, manualMembers, autoName));
      regionSelect.push(label);
    } else if (autoName) {
      regionSelect.push(autoName);
    }
  }
  for (var g = 0; g < REGIONS.length; g++) {
    var members = buckets["$" + REGIONS[g].name];
    if (!members || members.length === 0) continue;
    addRegion(regionLabel(REGIONS[g]), members);
  }
  if (other.length) addRegion("其他节点", other);
  if (on("生成倍率组")) {
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
  if (!existingDirect && directNameTaken) directTag = "angela-direct";
  var directVariants = [];
  for (var dvi = 0; dvi < DIRECT_VARIANTS.length; dvi++) {
    directVariants.push(directOut(DIRECT_VARIANTS[dvi][0], DIRECT_VARIANTS[dvi][1]));
  }
  if (!existingDirect) directVariants.unshift(directOut(directTag, ""));
  if (existingDirect && !existingDirect.connect_timeout) existingDirect.connect_timeout = "8s";
  if (existingDirect) applyResolver(existingDirect, "");
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
    useSet(sets, rules, "geosite-steam@cn", "geosite-steam@cn.srs", false, "直连");
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
      domains: [{
        domain_suffix: [
          "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com",
          "youtubekids.com", "youtubei.googleapis.com", "youtube.googleapis.com",
          "wide-youtube.l.google.com"
        ],
        outbound: "YouTube"
      }],
      extra: [{
        package_name: [
          "com.google.android.youtube",
          "com.google.android.apps.youtube.music",
          "app.revanced.android.youtube",
          "com.vanced.android.youtube"
        ],
        outbound: "YouTube"
      }]
    });
    addService({ name: "Google", sets: [{ tag: "geosite-google", file: "geosite-google.srs", out: "Google" }] });
    addService({
      name: "ClaudeAI",
      def: "🇺🇸 美国",
      sets: [{ tag: "geosite-anthropic", file: "geosite-anthropic.srs", out: "ClaudeAI" }],
      domains: [{ domain_suffix: ["claude.ai", "anthropic.com", "claudeusercontent.com"], outbound: "ClaudeAI" }],
      extra: [{ package_name: ["com.anthropic.claude"], outbound: "ClaudeAI" }]
    });
    addService({
      name: "AI",
      def: "🇺🇸 美国",
      sets: [{ tag: "geosite-category-ai-!cn", file: "geosite-category-ai-!cn.srs", out: "AI" }],
      domains: [{ domain_suffix: ["openai.com", "chatgpt.com", "gemini.google.com", "generativelanguage.googleapis.com", "aistudio.google.com"], outbound: "AI" }],
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
      sets: [
        { tag: "geosite-paypal", file: "geosite-paypal.srs", out: "Crypto" },
        { tag: "geosite-binance", file: "geosite-binance.srs", out: "Crypto" },
        { tag: "geosite-okx", file: "geosite-okx.srs", out: "Crypto" },
        { tag: "geosite-bybit", file: "geosite-bybit.srs", out: "Crypto" },
        { tag: "geosite-huobi", file: "geosite-huobi.srs", out: "Crypto" },
        { tag: "geosite-category-cryptocurrency", file: "geosite-category-cryptocurrency.srs", out: "Crypto" }
      ],
      domains: [{ domain_suffix: ["htx.com"], outbound: "Crypto" }]
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
  for (var k = 0; k < leaves.length; k++) {
    if (leaves[k]) next.push(leaves[k]);
  }
  if (existingDirect) next.push(existingDirect);
  for (var dv2 = 0; dv2 < directVariants.length; dv2++) next.push(directVariants[dv2]);
  next.push(rejectSink());
  next.push(defaultGroup);
  for (var bg = 0; bg < baseGroups.length; bg++) next.push(baseGroups[bg]);
  next.push(directGroup);
  for (var rg = 0; rg < regionGroups.length; rg++) next.push(regionGroups[rg]);
  if (!minimal) next.push(fish);
  var cleaned = [];
  var emitted = {};
  for (var ci = 0; ci < next.length; ci++) {
    var ob = next[ci];
    if (!ob || !tagOf(ob)) continue;
    var ot = typeOf(ob);
    if (ot === "block" || ot === "dns") continue;
    if (emitted["$" + tagOf(ob)]) continue;
    emitted["$" + tagOf(ob)] = 1;
    cleaned.push(ob);
  }
  var endpointTagList = [];
  for (var et = 0; et < keptEndpoints.length; et++) endpointTagList.push(tagOf(keptEndpoints[et]));
  cleaned = pruneGroups(cleaned, endpointTagList);
  config.outbounds = cleaned;
  if (isArray(config.endpoints)) config.endpoints = keptEndpoints;

  var live = {};
  for (var lv = 0; lv < cleaned.length; lv++) live["$" + tagOf(cleaned[lv])] = 1;
  for (var le = 0; le < endpointTagList.length; le++) live["$" + endpointTagList[le]] = 1;
  var liveRules = [];
  for (var lr = 0; lr < rules.length; lr++) {
    if (typeof rules[lr].outbound === "string" && !live["$" + rules[lr].outbound]) continue;
    liveRules.push(rules[lr]);
  }
  rules = liveRules;
  if (!live["$" + finalTag]) finalTag = "默认代理";

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
  dnsRules.push({ query_type: ["HTTPS", "SVCB"], action: "reject" });
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
