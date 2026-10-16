# AngelaBox Windows

Windows 版在编。SignPath 基金会的免费签名申请被拒绝，改用维护者自己的代码签名证书。私钥只放在 `WINDOWS_PFX_BASE64` / `WINDOWS_PFX_PASSWORD`，不进仓库。没配这两项时，工作流可以构建，但不往 Release 挂包。

## 证书

- 发布签名在 `.github/workflows/release-windows-desktop.yml` 里用 `signtool` 完成。
- 打包步骤里的自签证书只满足 electron-builder 对 `signing.local.json` 的要求，不是对外发布的签名。
- 主程序和守护进程用同一张证书。

## 身份与文件约束

图形安装包 / 便携包必须同时满足：

1. 主程序文件名是 `AngelaBox.exe`
2. 守护进程按官方布局放在 `resources\daemon\sing-box-daemon.exe`
3. 内核 `applicationExecutableName` 也是 `AngelaBox.exe`
4. Windows 服务名是 `angelabox-daemon`（不得与官方 `sing-box-daemon` 冲突）
5. 主程序和守护进程用同一张证书

AngelaBox 的 Windows 包与 Android 使用同一份 `chain-dev` 内核（官方 sing-box 加上 Chain outbound）。**不是**官方 sing-box for Desktop（SFW），不得用官方名称、图标或 `io.nekohasekai.sfw` 冒充。

## 曾暂停期间的包

已发布的 `AngelaBox-v*-windows-amd64.zip`、`AngelaBox-windows-*.exe`、无版本号的 `AngelaBox-windows-*.zip` 仍视为残缺包，**不要安装**。第一份用 `WINDOWS_PFX_*` 签过的包发出后再删这段。

## 源码分支

- 桌面：[dukangalex/sing-box-for-desktop](https://github.com/dukangalex/sing-box-for-desktop) 分支 `angelabox`
- 仪表：[dukangalex/sing-box-dashboard](https://github.com/dukangalex/sing-box-dashboard) 分支 `angelabox`
- 内核：[dukangalex/sing-box](https://github.com/dukangalex/sing-box) 分支 `chain-dev`

不要从第三方站点下载名为 SFW、sing-box 或 AngelaBox 的 Windows 包装包。

## 构建与发版

- 工作流：`AngelaBox Windows Desktop`（`.github/workflows/release-windows-desktop.yml`）。配了 `WINDOWS_PFX_BASE64` 和 `WINDOWS_PFX_PASSWORD` 才签名并挂到 Release；没配就只构建。
- 云备份格式 `angelabox-cloud/1` 在 Windows 可用构建验证通过前，仍只服务 Android。
