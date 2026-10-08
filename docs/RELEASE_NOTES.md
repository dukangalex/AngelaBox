• **1.0.104：**
1. **多协议原生解析与智能导入：** 优化剪贴板与扫码导入逻辑（`ProfileImportHandler`），原生支持 VLESS（Reality / Vision）、VMess、Hysteria 2、TUIC v5、Shadowsocks (2022-blake3)、Trojan、WireGuard 等单节点与多节点混合文本快速载入，消除外部格式依赖。
2. **现代传输参数健壮性适配：** 增强 Hysteria 2 多种混淆字段规范（兼容 `obfs-password`、`obfs_password` 等客户端格式）及上下行带宽配置；补齐 TUIC v5 的 QUIC ALPN (`h3`) 协商与拥塞控制别名识别。
3. **架构安全规约对齐：** 严格对齐官方 sing-box 1.15.0-alpha.10 审核基线，持续收敛系统特权，全量安全守护断言（`test_security_guards`、`test_override_guards`、`test_inbound_compat`）均 100% 验证通过。
4. **包体完整性防御：** 建议网络受限环境通过 GitHub Releases 独立安装包直接下载，内置严格 SHA-256 校验确保发行可信。
