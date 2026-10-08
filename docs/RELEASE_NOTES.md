• **1.0.105：**
1. **诊断导出密钥脱敏加固：** 崩溃/诊断信息导出（`DebugInfoExporter`）的密钥脱敏规则补齐 sing-box 与 VPN 密钥材料——WireGuard `private_key` / `pre_shared_key` / `psk`、Hysteria2 `auth_str`，此前 JSON 形态的这些字段会原文落入导出包；同时支持 JSON 引号键名（`"private_key": "…"`）与 `Authorization: Bearer <token>` 头的完整脱敏，不再只吞掉 `Bearer` 而留下 token。
2. **构建加速：** `gradle.properties` 启用 Gradle 构建缓存（`org.gradle.caching=true`），增量构建复用任务产物输出。
3. **代码清理：** 删除两处从未被引用的死常量——`BoxService.PROFILE_UPDATE_INTERVAL` 与 `HTTPClient.UPDATE_ATTEMPTS`（全仓库 grep 确认零引用），避免与现行订阅更新（WorkManager）/ 下载重试阶梯混淆。
