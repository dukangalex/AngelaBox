# 可选覆写脚本

应用不内置机场覆写。以前导入并留在本机的「机场覆写」，打开脚本列表时会删掉。全地区分组已经是默认脚本，不要再导入一份绑到同一配置上。链式开启时，配置上的脚本仍然会跑（先脚本，再组链）。

内置默认脚本是 `app/src/main/assets/scripts/airport-region.js`（`overlay-revision: 23`）。它按全地区识别分组。地区自动选择是地区选择组里的一个成员，分组列表不单独再列一张卡片。没有负载均衡 / 故障转移。延迟挑选用 urltest，手选用 selector。设置里的配置覆盖开关作用在这份脚本上。不要再把同一份逻辑当成第二个脚本绑到同一配置。

## service-groups.js

功能组含「💬 Claude.ai」。测速与规则集只用 HTTPS；没有 `geoip-private` / `geoip-telegram`（官方规则集不存在，私有网用 `ip_is_private`，Telegram 用 `geosite-telegram`）。
