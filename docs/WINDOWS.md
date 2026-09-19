# AngelaBox Windows

身份与文件名以 [IDENTITY.md](IDENTITY.md) 为准。云备份与 Android 共用同一 WebDAV 帐号，见 [BACKUP.md](BACKUP.md)。

AngelaBox 的 Windows 包与 Android 安装包使用同一份 `chain-dev` 内核（官方 sing-box 加上 Chain outbound）。**不是**官方 sing-box for Desktop（SFW），不得用官方名称、图标或 `io.nekohasekai.sfw` 冒充。

## 命令行客户端

Release 中的压缩包：

| 文件 | 架构 |
|------|------|
| `AngelaBox-windows-amd64.zip` | 64 位 Intel/AMD（大多数电脑） |
| `AngelaBox-windows-arm64.zip` | Windows on ARM |
| `AngelaBox-windows-386.zip` | 32 位 |

解压后得到 `sing-box.exe`（命令行内核，不是图形主程序）。在管理员命令提示符中：

```
sing-box.exe check -c config.json
sing-box.exe run -c config.json
```

`config.json` 须为当前稳定官方语法。订阅、规则集、远程脚本只允许公网 HTTPS；不要写 HTTP 或 RFC1918 地址。启用 TUN 需要管理员权限。

内核型号与 Android 测试版相同，见该次 Release 说明中的 `KERNEL_TAG` / `KERNEL_COMMIT`。用 `sing-box.exe version` 核对。

## 图形客户端（方案 B）

图形安装包必须同时满足：

1. 主程序文件名是 `AngelaBox.exe`（`C:\Program Files\AngelaBox\`）
2. 守护进程按官方布局放在 `resources\daemon\sing-box-daemon.exe`
3. 内核 `applicationExecutableName` 也是 `AngelaBox.exe`
4. Windows 服务名是 `angelabox-daemon`（不得与官方 `sing-box-daemon` 冲突）
5. 主程序和守护进程用**同一把** Authenticode 证书

1.0.62-beta 的 `AngelaBox-windows-*.exe` **不能安装**：当时只改了外壳名字，守护进程仍去打开 `sing-box.exe`，安全安装失败后会回滚。不要重试那一版。命令行 zip 仍可用。

在干净机器上把 `AngelaBox.exe` 图形安装包走通之前，**不会再往 Release 挂图形安装包**。身份内核已在 `chain-dev`（`v1.15.0-chain.3` / `d7639f61`），桌面源码在 `dukangalex/sing-box-for-desktop` 的 `angelabox` 分支。

源码：

- 桌面：[dukangalex/sing-box-for-desktop](https://github.com/dukangalex/sing-box-for-desktop) 分支 `angelabox`
- 仪表：[dukangalex/sing-box-dashboard](https://github.com/dukangalex/sing-box-dashboard) 分支 `angelabox`
- 内核：[dukangalex/sing-box](https://github.com/dukangalex/sing-box) 分支 `chain-dev`（身份常量已合入，tag `v1.15.0-chain.3`）

未配置 `WINDOWS_CERTIFICATES_P12` 时，测试包会有 SmartScreen「未知发布者」提示，这是预期。正式包必须用仓库里那把长期证书。

不要从第三方站点下载名为 SFW 或 sing-box 的包装包。Android 用户仍只安装 `AngelaBox-android.apk`。

## 云备份

Windows 与 Android 使用同一 WebDAV 地址、用户名、密码和远程文件名（默认 `backup.zip`）。备份格式是 `angelabox-cloud/1`，不是 Android 的 SQLite。请用当前 Android 重新备份一次，电脑端才能读。图形端路径：设置 → 云备份。
