# AngelaBox Windows 代码签名政策

本政策定义 AngelaBox Windows 构建的代码签名规则，使用 [SignPath Foundation](https://signpath.org) 为开源项目提供的免费代码签名服务。

## 1. 范围

适用于 AngelaBox Windows 发布的所有可执行产物，包括但不限于：

- 主程序（`AngelaBox.exe`）
- 守护进程（`resources\daemon\sing-box-daemon.exe`，Windows 服务 `angelabox-daemon`）
- 安装器（`AngelaBox-windows-*.exe`，electron-builder / NSIS 生成）
- 便携包内的全部可执行文件（`AngelaBox-v*-windows-amd64.zip`）

主程序与守护进程必须由同一 signing policy 签名。

## 2. 职责

- **项目维护者**：仅授权维护者可推送默认分支（`dev`）、创建发版 tag、管理仓库设置。维护者均启用 2FA。
- **SignPath 服务**：提供代码签名证书并在云端 HSM 中完成签名；私钥永不离开 SignPath。

## 3. 分支保护与代码审查

只有经过审查的可信代码才会被签名：

- `dev` 分支受保护，变更经审查合并。
- 所有签名操作经 GitHub Actions 全自动完成，禁止维护者手动签名后发布。

## 4. 构建与签名流程

- **CI/CD 自动化**：签名仅在官方发版流程中触发（`.github/workflows/release-windows-desktop.yml`）。
- **触发条件**：手动触发发版工作流且 `SIGNPATH_*` secrets 已配置；未获批/未配置时工作流不产出签名包。
- **临时环境**：构建在一次性的 GitHub 托管 runner 上完成。
- **SignPath 集成**：未签名产物编译完成后，GitHub Action 经 SignPath API 提交；SignPath 验证构建出处（OIDC / API Token）后签名。
- **分发**：已签名产物取回 runner，校验后上传至 GitHub Releases。

## 5. 事件响应与吊销

如怀疑仓库、CI token 泄露或恶意代码被签名：

1. 立即吊销相关 token / secrets。
2. 如恶意代码已被签名，立即联系 SignPath Foundation 申请吊销证书。
3. 在仓库发布安全公告通知用户。
