# AngelaBox 安全与联动验收覆盖台账

日期：2026-11-17 UTC。代码基线：`c0534722aa7e89ac39d6dc8ae5fde43abe4b7ece`。

## 结论与边界

当前是“已有定向源码审计和自动化回归，整产品验收未通过”，不是“全面审计完成”。本台账把已有证据与剩余验收分开；测试类存在不等于其所有调用方或用户流程都已验证。未发现新问题不等于不存在问题。

- 复用该提交已完成的 JVM 测试，不重复执行：重新解析最终 JUnit XML，72 类、797 项，失败/错误/跳过均为 0。原始结果见 `worker-cancel-final.log` 与对应 XML。
- 本轮补充执行 `ci.yml` 中的十个 Python 宿主脚本，全部顺序完成、整体退出 0。覆盖源码守卫、覆盖层沙箱、签名解析器、72 类测试白名单、版本 pin 与本地模拟远程 tag 门禁；不是远端 CI、真实发行签名或设备证据。
- 本轮执行 `:app:mergeProjectDexDebug :app:mergeDebugNativeLibs`，退出 0，`BUILD SUCCESSFUL in 1m 51s`，31 项任务中 5 项执行。通过项目 DEX 生成/合并、重复类检查与原生库合并；不是完整 APK 打包、依赖 DEX 全面构建、安装或启动。
- 读取合并后的 Debug manifest：包名为 `io.chainbox.app`，`allowBackup=false`、`usesCleartextTraffic=false`；登记 11 个导出入口（含 Debug/依赖入口）。未在运行时验证这些入口的身份检查或意图处理。
- 当前 AAR 中仅有 ARM64 `libbox.so`，ELF 为 AArch64；嵌入 Go 元数据为 `go1.25.5`、`GOOS=android`、`GOARCH=arm64`、`vcs.revision=b4a0320eabbb9b2835055b6dfcc094f25706421f`、`vcs.modified=false`，与版本 pin 一致。这是二进制自报元数据一致性，不是独立可复现构建证明；其版本链接值为 `unknown`，不能用它证明发行版本身份。
- 原生库合并中间目录有 25 个 SO，含四种 ABI 的依赖库；不能把中间目录或源码 ABI filter 当最终 APK 仅含 ARM64 的证据。最终包须另检。

## 按关键路径分层

| 路径 | 已有证据及其范围 | 尚缺的决定性验收 |
|---|---|---|
| 原生订阅首次导入、离线保留、补排 | `PendingProfileImportCommitTest`、`InitialImportSchedulingIntegrationTest`；生产 Room/WorkManager 测试及持久化意图、去重/网络约束回归 | 完整应用在未启 VPN 时成功 HTTPS 导入；进程死亡后自动补排、恢复网络后一次性物化；UI 可理解状态 |
| 后台周期/手动更新、取消 | `ProfileUpdateAttempt`、`UpdateProfileWork`、`ManualProfileCommitTest`；c053472 取消回归，正常失败与取消区分 | 真实 Worker 正在下载时 Stop/取消；不得发起后续订阅。同步 HTTP/DNS 在途请求不保证即时撤回；取消不能撤回已提交数据 |
| HTTPS、重定向、公网地址限制、VPN 路由 | `HTTPClient*Test`、`RemoteUrlGuardTest`、`SubscriptionRouteLeaseTest`；8e1328b/1dda0e7/9ef9249 定向修复；先前独立 API30 x86_64 VPN harness | 完整 ARM64 应用真实 HTTPS、VPN 启停/撤销/替换/重载时的流量归属；系统 DNS 已发查询不可由代次检查撤回。既有 harness 不运行完整应用或真实 libbox 重载 |
| 配置/链路绑定与文件一致性 | `ChainBindingRoomTransactionTest`、`ProfileBindingJournalTest`、`ProfileBindingIntegrationTest`；a42d82a 主机七点 SIGKILL 与 Android 精简 harness 七点进程重开证据 | 当前完整应用真实 Room/文件路径、进程中断及设备存储；真正断电/文件系统持久性。精简 harness/保留内核的进程杀死不等于断电 |
| 备份恢复、脚本/绑定 opt-in、自动联网策略 | `BackupManagerTest`、`PortableCloudBackupTest` 与 59ad74e/aa0c18f SQLite 红绿回归 | ZIP UI 完整恢复、跨版本迁移；分别勾选/不勾选时观察脚本绑定与网络请求；恶意/损坏/超限备份交互及中断恢复 |
| 二维码/外部导入提示 | b491a6a 和 `LocalProviderDisclosureTest`、导入路径守卫 | 摄像头/二维码/深链接/文件提供者真实 UI 是否提示嵌入远程访问及拒绝后的零后续动作 |
| CommandClient / UI 状态联动 | `CommandEpochGateTest`、`LatestRemoteSelectionTest`、连接列表筛选等测试；4209b5f 等时序修复 | 实际 JNI 回调、前后台切换、日志/选中项/连接列表与服务真实状态一致且及时 |
| AI 设置与巡检 | `InspectionProvidersTest`、`InspectionAiTransportTest`、`InspectionAiLocalEndpointTest`、策略/展示/样本门禁测试 | 真实 UI 保存服务商/模型/API Key，联网请求的最小披露/错误提示、巡检建议可理解性；不以模型文本代替网络策略授权 |
| 内核与依赖供应链 | pin/tag/签名解析器/源码守卫；本轮 AAR 哈希与嵌入构建元数据一致性 | 当前候选完整内核测试/可复现 AAR 来源链、已知 Go 公告逐项可达性、当前依赖完整审计；历史 37 条公告记录不代表当前问题数量或已清零 |
| Android 权限与导出组件 | manifest 清单和既有源码权限边界；`WorkingDirectoryProvider` 的 MANAGE_DOCUMENTS 等静态声明 | 实际调用者身份/授权拒绝、XposedProvider 边界、分享/磁贴/启动广播等完整攻击面动态检查。无运行时证据时不认定漏洞或安全通过 |
| 更新/发布 | APK 签名解析器合成测试及工作流源守卫；精确 dev tip/CI 前置门禁代码 | 当前具体 APK 的所有签名、哈希、来源、覆盖安装、更新拒绝路径；完整 lint/安全审计与实际发布门禁。未执行发布 |
| 其他既有功能 | 已保留源码和测试；本轮不增加 Clash/Mihomo 兼容或其他功能 | USBIP/OpenConnect/Taildrop、分应用代理、通知/诊断、耗电与长稳等完整用户流程；本台账没有证明这些功能全部可用 |

## 原始证据与身份

- 当前 JVM 证据：`worker-cancel-c053472-evidence.zip`，SHA-256 `4b3ea6bd24d691fdd303b0a952ae2bf2b3c47cd2f28956a0d7f29931f731ef56`（本轮重新计算，与前轮记录一致）。
- 本轮输出：`audit-coverage-c053472/` 下的 `dex-native.log`、`junit-inventory.json`、`host-ci-summary.txt`、manifest/导出入口清单、原生库合并清单与嵌入 Go 构建信息。host-ci-summary 为完成结果摘要，不伪装为逐测试原始日志。
- AAR SHA-256（本轮计算）：`2f19fe11407b7f91da602d038b5bb55bdff9222bd63d763a0aef6019c1193936`。
- ARM64 libbox.so SHA-256（本轮计算）：`f36c6e3d0de92bd16040b75a6c3a5b9a7352d4a0a1909c5f8ca8f6ab3c5af102`；合并产物中 libbox.so 哈希相同。
- 先前分层报告：`verified-a42d82a.md`、`android-simular-a42d82a.md`、`subscription-route-8e1328b.md`、`subscription-reload-1dda0e7.md`、`subscription-preflight-9ef9249.md`、`restore-sensitive-report.md`、`worker-cancel-report.md`。历史环境观察不当作本轮设备重新验证。

## 试用与发布门槛

1. 阶段性非发布试用不等于完整验收；旧 b491a6a 试用包不包含本台账基线后续修复。此次不另发 APK，以免把静态/宿主检查当作安全放行。
2. 首先需要稳定、获授权的 ARM64 设备/兼容运行环境完成上述冷启动、网络恢复、VPN 替换/重载和 ZIP 恢复路径；现有无 KVM、无附加设备及故障模拟器没有新的可用性证据，不重复启动。
3. 原始 DNS 故障仍缺主机与完整异常链；通用公开 HTTPS 测试成功也不能证明 Alex 的原故障根因已经解决。
4. 当前完整 lint 及依赖可达性审计仍未签核；历史被终止的 lint 不算通过。
5. 通过适当审计/验收后，生成明确代码/内核/包名/签名身份、可覆盖当前试用版的非发布试用包，再做安装与功能核验。正式发布只在 Alex 对该具体版本试用认可后进行；不因测试数量或文档完成降低门槛。

本轮没有新增功能或生产代码修改，没有推送、合并、发布、轮换凭据或操作个人账号。
