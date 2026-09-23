# AngelaBox Windows — 暂停

**现在不发 Windows 版，也不维护已有 Windows 包。**

条件未成熟：没有可用的商业代码签名，图形端和命令行都还跑不完整，装上不能当日常客户端用。未来一段时间本项目不会推出 Windows 版。已发布的 `AngelaBox-v*-windows-amd64.zip`、`AngelaBox-windows-*.exe`、无版本号的 `AngelaBox-windows-*.zip` 都视为残缺包，**不要安装，不要当故障来修**。等签名、安装和服务能在干净的 Windows 上单独验通，再恢复开发和发版。

在那之前：

- 只安装 Android 的 `AngelaBox-android.apk`。
- 不要跑 **AngelaBox Windows Desktop** 工作流，也不要用 `scripts/build_windows_cli.sh` 出包。工作流里的构建作业已 `if: false`。
- 云备份格式 `angelabox-cloud/1` 先只服务 Android。格式定义留在 [BACKUP.md](BACKUP.md) 和 [IDENTITY.md](IDENTITY.md)，不是邀请去装 Windows。

下面的安装步骤、服务名和证书说明是归档，供以后恢复时对照。按它们操作不会得到一个能用的客户端。

---

## 归档（勿安装）

身份与文件名以 [IDENTITY.md](IDENTITY.md) 为准。

AngelaBox 的 Windows 包曾经计划与 Android 使用同一份 `chain-dev` 内核（官方 sing-box 加上 Chain outbound）。**不是**官方 sing-box for Desktop（SFW），不得用官方名称、图标或 `io.nekohasekai.sfw` 冒充。

因未购买商业代码签名证书，试发过的包用 CI **自签**（`CN=AngelaBox Test`）。这是暂停的原因之一，不是安装说明。

### 曾计划的文件

| 文件 | 是什么 |
|------|--------|
| `AngelaBox-v*-windows-amd64.zip` | 未完成的图形便携包 |
| `AngelaBox-windows-*-x64.exe` | 未完成的 NSIS 安装器 |
| `AngelaBox-windows-amd64.zip`（无版本号） | 未完成的命令行，不是图形界面 |

这些文件功能残缺。不要解压，不要点安装。

### 恢复时才需要的约束

图形安装包 / 便携包必须同时满足：

1. 主程序文件名是 `AngelaBox.exe`
2. 守护进程按官方布局放在 `resources\daemon\sing-box-daemon.exe`
3. 内核 `applicationExecutableName` 也是 `AngelaBox.exe`
4. Windows 服务名是 `angelabox-daemon`（不得与官方 `sing-box-daemon` 冲突）
5. 主程序和守护进程用**同一把**长期证书

Windows 弹「未知发布者」、Chrome 拦下载，是因为安装器用的是 CI 自签。要恢复发版，必须先有公开 CA 签发的 OV Authenticode（DigiCert、Sectigo 或 SSL.com；不要买 SSL，不要买仅文档签名）。2024 年起 EV 不再保证立刻过 SmartScreen。新证私钥必须放 USB 令牌或云 HSM。Android CN=ChainBox 和 Windows Authenticode 不是同一把，也不会为了 Windows 去轮换 Android 证书。

源码仍在，但分支不再跟发版：

- 桌面：[dukangalex/sing-box-for-desktop](https://github.com/dukangalex/sing-box-for-desktop) 分支 `angelabox`
- 仪表：[dukangalex/sing-box-dashboard](https://github.com/dukangalex/sing-box-dashboard) 分支 `angelabox`
- 内核：[dukangalex/sing-box](https://github.com/dukangalex/sing-box) 分支 `chain-dev`

不要从第三方站点下载名为 SFW、sing-box 或 AngelaBox 的 Windows 包装包。
