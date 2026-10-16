# AngelaBox Windows 代码签名政策

SignPath 基金会的免费签名申请被拒绝。Windows 包改由维护者自己的代码签名证书签名。私钥不进仓库，只放在 GitHub Actions secrets。

## 1. 范围

- 主程序 `AngelaBox.exe`
- 守护进程 `resources\daemon\sing-box-daemon.exe`
- 安装器 `AngelaBox-windows-*.exe`
- 便携包 `AngelaBox-v*-windows-amd64.zip` 里的可执行文件

主程序和守护进程用同一张证书。

## 2. 密钥

- `WINDOWS_PFX_BASE64`：PFX 的 base64。
- `WINDOWS_PFX_PASSWORD`：PFX 密码。
- 仓库里不放 `.pfx`、`.p12` 或私钥。CI 里给 electron-builder 用的 `New-SelfSignedCertificate` 只是打包占位，发布前会用上面的 PFX 重签。没配 secrets 时不往 GitHub Release 挂 Windows 包。

## 3. 流程

工作流是 `.github/workflows/release-windows-desktop.yml`。只在手动触发且 secrets 已配置时签名并上传。未签名产物不挂到 Release。
