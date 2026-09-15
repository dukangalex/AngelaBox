更新说明
• 安全加固 1.0.53：链式国内直连不再把「中国 + Google」这类混合规则当成直连；境外流量异常时仍 fail-closed，不会掉到 DIRECT。
• 应用内更新只认 `AngelaBox-android.apk` 和 SHA-256，并校验发行证书；不再回退到旧仓库 ChainBox。
• 订阅 / 脚本下载拦截回环、链路本地和云元数据；脚本只允许 HTTPS。
• 关闭系统云备份（节点密码不会进 Google 备份）；Clash API 只绑 127.0.0.1。
• 发版只出 `AngelaBox-android.apk`。内核仍钉官方 1.14.0 / commit `03ad0a1d`。
• 注重隐私、安全防护、高能低耗、开箱即用。
