# AngelaBox Windows — 恢复开发中

Windows 版已重启开发。证书方案改用 **SignPath 基金会免费开源代码签名**（参考 [Bettbox](https://github.com/appshubcc/Bettbox) 的方案），不再需要购买商业 OV 证书。旧的暂停原因（无商业签名、CI 自签）不再成立。

> 申请状态：待提交。SignPath 基金会申请需项目维护者填写表格并邮件至 oss-support@signpath.org（见 https://signpath.org/apply），获批前工作流中的签名步骤保持占位、不实际发版。

## 证书方案：SignPath Foundation（免费）

- [SignPath Foundation](https://signpath.org) 为符合条件的开源项目提供免费 Authenticode 代码签名；私钥由其 HSM 保管，我方永不接触。
- CI 流程：编出未签名包 → GitHub Actions 提交 SignPath API → 验证构建出处 → 签名后取回 → 发版。全自动，不允许人工手动签名。
- 代价：证书颁发给 SignPath Foundation，Windows 上显示的发布者为 "SignPath Foundation" 而非 AngelaBox；"未知发布者"警告消除，SmartScreen 信任度随已签名安装量逐步积累（OV 证书均如此）。
- 申请条件对照（https://signpath.org/terms.html）：
  - OSI 认可的开源许可证：GPL-3.0 ✓（见 LICENSE）
  - 无专有/双许可商业组件 ✓
  - 持续维护、有已发布版本：Android 线活跃 ✓；Windows 线以本次重启后的首个可用构建为准
  - 公示代码签名政策 ✓（见 [SIGNING-POLICY.md](SIGNING-POLICY.md)）
  - 维护者启用 2FA（仓库已要求）

## 身份与文件约束（恢复后仍有效）

图形安装包 / 便携包必须同时满足：

1. 主程序文件名是 `AngelaBox.exe`
2. 守护进程按官方布局放在 `resources\daemon\sing-box-daemon.exe`
3. 内核 `applicationExecutableName` 也是 `AngelaBox.exe`
4. Windows 服务名是 `angelabox-daemon`（不得与官方 `sing-box-daemon` 冲突）
5. 主程序和守护进程用**同一把**签名证书（SignPath 同一 signing policy 下自然满足）

AngelaBox 的 Windows 包与 Android 使用同一份 `chain-dev` 内核（官方 sing-box 加上 Chain outbound）。**不是**官方 sing-box for Desktop（SFW），不得用官方名称、图标或 `io.nekohasekai.sfw` 冒充。

## 曾暂停期间的包

已发布的 `AngelaBox-v*-windows-amd64.zip`、`AngelaBox-windows-*.exe`、无版本号的 `AngelaBox-windows-*.zip` 仍视为残缺包，**不要安装**。首个 SignPath 签名版本发布后，本节删除。

## 源码分支

- 桌面：[dukangalex/sing-box-for-desktop](https://github.com/dukangalex/sing-box-for-desktop) 分支 `angelabox`
- 仪表：[dukangalex/sing-box-dashboard](https://github.com/dukangalex/sing-box-dashboard) 分支 `angelabox`
- 内核：[dukangalex/sing-box](https://github.com/dukangalex/sing-box) 分支 `chain-dev`

不要从第三方站点下载名为 SFW、sing-box 或 AngelaBox 的 Windows 包装包。

## 构建与发版

- 工作流：`AngelaBox Windows Desktop`（`.github/workflows/release-windows-desktop.yml`），恢复中：`if: false` 移除、签名步骤改为 SignPath（以 `SIGNPATH_*` secrets 是否存在为门控，未获批前不实际签名发版）。
- 云备份格式 `angelabox-cloud/1` 在 Windows 可用构建验证通过前，仍只服务 Android。
