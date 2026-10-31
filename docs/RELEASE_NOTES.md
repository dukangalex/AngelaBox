• **1.0.138：**
1. **更新测试线内核（Updated beta kernel）：** 内核升级为 `v1.15.0-chain.11`，官方基线仍为 `v1.15.0-alpha.13`；同步上游网络与端点流量处理更新，并修正 Hysteria v1 双向大包 UDP 互操作。
   The beta kernel is now `v1.15.0-chain.11`, still based on official `v1.15.0-alpha.13`, with upstream network/endpoint flow updates and a Hysteria v1 bidirectional large-datagram interoperability fix.
2. **链式 UDP 拒绝不再换出口（A chain UDP refusal can no longer fall back to another exit）：** 链式出站仍只承载 TCP；UDP 被拒绝时，URLTest 现在保留这次策略拒绝，不再把它当成连接故障改走其它出口。
   Chains still carry TCP only. When a chain intentionally refuses UDP, URLTest now propagates that refusal instead of treating it as an availability failure and choosing another exit.
3. **启动重试保留分应用选择（Per-app selection survives startup retries）：** 每次内核启动尝试都使用新的应用包名迭代器，避免恢复重试复用已经被消耗的列表。
   Each kernel-start attempt now receives a fresh package-name iterator, so a recovery retry cannot reuse an exhausted per-app list.
4. **发布前检查更严格（Stricter release checks）：** 发布必须来自 `dev` 的精确最新提交，且对应 CI 全部通过；分应用重试回归已纳入 CI 与发布测试清单。可以直接覆盖安装，正式版轨道不变。
   Releases must use the exact current `dev` commit with a successful CI run. Per-app retry regressions are included in both CI and release tests. Installs over the previous beta; the stable track is unchanged.
