# AngelaBox Windows

AngelaBox 的 Windows 包与 Android 安装包使用同一份 `chain-dev` 内核（官方 sing-box 加上 Chain outbound）。**不是**官方 sing-box for Desktop（SFW），不得用官方名称、图标或 `io.nekohasekai.sfw` 冒充。

## 命令行客户端

Release 中的压缩包：

| 文件 | 架构 |
|------|------|
| `AngelaBox-windows-amd64.zip` | 64 位 Intel/AMD（大多数电脑） |
| `AngelaBox-windows-arm64.zip` | Windows on ARM |
| `AngelaBox-windows-386.zip` | 32 位 |

解压后得到 `sing-box.exe`。在管理员命令提示符中：

```
sing-box.exe check -c config.json
sing-box.exe run -c config.json
```

`config.json` 须为当前稳定官方语法。订阅、规则集、远程脚本只允许公网 HTTPS；不要写 HTTP 或 RFC1918 地址。启用 TUN 需要管理员权限。

内核型号与 Android 测试版相同，见该次 Release 说明中的 `KERNEL_TAG` / `KERNEL_COMMIT`。用 `sing-box.exe version` 核对。

## 图形客户端

官方图形客户端是独立的 Electron 工程（[sing-box-for-desktop](https://github.com/SagerNet/sing-box-for-desktop)），安装包文件名为 `SFW-*.exe`，由 SagerNet 用他们自己的 Authenticode 证书签发。

本仓库的 **AngelaBox Windows Desktop** 工作流用同一份 `chain-dev` 内核编译图形端，产品名与 appId 改为 AngelaBox / `io.chainbox.desktop`，安装包文件名为 `AngelaBox-windows-*.exe`。未配置 `WINDOWS_CERTIFICATES_P12` 时，安装包 **没有** Authenticode 签名，Windows SmartScreen 会提示未知发布者；这是预期行为，不是官方 SFW。

不要从第三方站点下载名为 SFW 或 sing-box 的包装包。Android 用户仍只安装 `AngelaBox-android.apk`。
