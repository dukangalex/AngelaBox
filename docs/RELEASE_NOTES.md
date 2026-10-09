• **1.0.107：**
1. **版本号递增：** 功能内容与 1.0.106 完全相同（诊断脱敏加固、崩溃日志脱敏、备份凭据防丢、云备份隧道感知）；`versionCode` 提升至 10701，已安装 1.0.106 的设备可直接覆盖升级安装。

• **1.0.106：**
1. **诊断导出脱敏补漏（中危修复）：** `DebugInfoExporter` 的密钥脱敏正则追加裸 `auth` 分支——此前 hysteria 出站的裸 `"auth"` 键（base64 密钥材料）会原文落入诊断导出包；现已覆盖，并新增单测。`author=` 等非密钥词不受影响。
2. **崩溃/OOM 报告纵深防御：** `CrashReportManager` / `OOMReportManager` 在打包分享前对 go/jvm 日志文本复用 `redactSecrets` 脱敏；二进制堆转储保持原样（文本脱敏不适用）。
3. **崩溃上报可观测性：** 写 pending JVM 崩溃报告失败时不再静默吞异常，改为 `Log.e` 留痕，便于排查磁盘满/权限问题。
4. **备份凭据防丢：** 备份时不再先清空 live Settings 再 `sleep(250)` 等落盘（进程若在该窗口被杀会导致用户 WebDAV 密码 / GitHub token 丢失）；改为与 profiles 库相同的“redacted 临时库”模式——拷贝 settings 库到临时文件后在副本里删除凭据行，live Settings 全程不被触碰。
5. **云备份隧道感知：** 隧道开着时 WebDAV 建连改走隧道内按域名直拨（此前守卫要求直连 DNS 出答案，隧道 fake-ip 下直连 DNS 常失败，导致“无法解析主机，已拒绝”）；隧道关闭时保持原来的 IP  pinning 直连。TLS 仍校验主机名，SSRF 防护不变。
