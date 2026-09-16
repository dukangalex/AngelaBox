更新说明
• 安全加固 1.0.53：链式国内直连不再把「中国 + Google」这类混合规则当成直连；境外流量异常时仍 fail-closed，不会掉到 DIRECT。
• 远程订阅、脚本、更新全部只允许 HTTPS。已保存的 HTTP 订阅请改成 HTTPS。
• 应用内更新只认 AngelaBox-android.apk，必须校验 SHA-256 和发行证书，不再回退到旧 ChainBox 仓库。
• Clash API 只绑 127.0.0.1；禁止系统云备份配置；Xposed 接口只接受系统 / LSPosed。
• 发版只出 AngelaBox-android.apk。官方 1.14.1 仅为修补且把 Go 升到 1.26.8，本版内核仍钉 1.14.0。
• 注重隐私、安全防护、高能低耗、开箱即用。
