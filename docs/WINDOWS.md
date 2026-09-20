# AngelaBox Windows

身份与文件名以 [IDENTITY.md](IDENTITY.md) 为准。云备份与 Android 共用同一 WebDAV 帐号，见 [BACKUP.md](BACKUP.md)。

AngelaBox 的 Windows 包与 Android 安装包使用同一份 `chain-dev` 内核（官方 sing-box 加上 Chain outbound）。**不是**官方 sing-box for Desktop（SFW），不得用官方名称、图标或 `io.nekohasekai.sfw` 冒充。

## 图形客户端（要的是这个）

从 GitHub Release 下载 **`AngelaBox-windows-*.exe`**，双击安装。这是带窗口的客户端，安装后开始菜单和桌面会有 AngelaBox。

当前测试包：[`AngelaBox-windows-1.0.63-beta-x64.exe`](https://github.com/dukangalex/AngelaBox/releases/download/v1.0.64-beta/AngelaBox-windows-1.0.63-beta-x64.exe)（挂在 `v1.0.64-beta` 这一页；安装器底部应是 **1.0.63-beta**）。

1. 先卸载 1.0.62-beta。
2. Chrome 若提示「此文件包含危险内容」，换 **Microsoft Edge** 或 Firefox 打开同一链接。
3. SmartScreen 未知发布者：更多信息 → 仍要运行。
4. 若弹出「数据迁移已完成，但无法删除旧数据（代码 40）」，点确定即可。
5. 不要下任何 `.zip`。zip 里只有命令行 `sing-box.exe`，不是图形界面。

图形安装包必须同时满足：

1. 主程序文件名是 `AngelaBox.exe`（`C:\Program Files\AngelaBox\`）
2. 守护进程按官方布局放在 `resources\daemon\sing-box-daemon.exe`
3. 内核 `applicationExecutableName` 也是 `AngelaBox.exe`
4. Windows 服务名是 `angelabox-daemon`（不得与官方 `sing-box-daemon` 冲突）
5. 主程序和守护进程用**同一把** Authenticode 证书

1.0.62-beta 的 `AngelaBox-windows-*.exe` **不能用**：当时只改了外壳名字，守护进程仍去打开 `sing-box.exe`，安全安装失败后会回滚。那一版还会因 `registerCore is not defined` 立刻退出。不要再用。

未配置 `WINDOWS_CERTIFICATES_P12` 时，CI 用一次性自签证书，Chrome 和 SmartScreen 都会拦截，这是预期。测试包已挂在预发布页，方便下载；正式对外发版仍须长期证书。

桌面快捷方式图标是透明底的立方体（无白底方块）。若仍看到白底或官方立方体，删掉旧快捷方式后重新安装，或重启一次资源管理器刷新图标缓存。

## 命令行客户端

部分 Release 会附压缩包（本测试页已去掉，避免和下图形安装器搞混）：

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

## 怎样才不是未知发布者

自签证书过不了 Chrome / SmartScreen。必须用**同一把长期 Authenticode 证书**签 `AngelaBox.exe` 和 `sing-box-daemon.exe`：

1. 向 DigiCert、Sectigo 或 SSL.com 购买 **OV** 或 **EV** 代码签名证书（不要买「仅文档签名」）。
   - **EV**：SmartScreen 通常立刻信任。需要注册公司，个人开发者一般买不到。
   - **OV**：个人或公司都可以。初期仍可能提示未知发布者，要靠一段时间的下载量积累声誉。
2. 在本机导出 PKCS#12（`.p12` / `.pfx`）。**不要提交到 git，不要贴到聊天或 Issue。**
3. 仓库 Settings → Secrets and variables → Actions 添加：
   - `WINDOWS_CERTIFICATES_P12`：p12 文件的 Base64
   - `WINDOWS_P12_PASSWORD`：导出时的密码
4. 再跑一次 **AngelaBox Windows Desktop**。日志应出现 `Using repository Authenticode certificate.`，而不是 `issuing a CI self-signed`。

Android 发行证书（CN=ChainBox）和 Windows Authenticode **不是同一把**，也不会为了 Windows 去轮换 Android 证书。

源码：

- 桌面：[dukangalex/sing-box-for-desktop](https://github.com/dukangalex/sing-box-for-desktop) 分支 `angelabox`
- 仪表：[dukangalex/sing-box-dashboard](https://github.com/dukangalex/sing-box-dashboard) 分支 `angelabox`
- 内核：[dukangalex/sing-box](https://github.com/dukangalex/sing-box) 分支 `chain-dev`（身份常量已合入，tag `v1.15.0-chain.3`）

不要从第三方站点下载名为 SFW 或 sing-box 的包装包。Android 用户仍只安装 `AngelaBox-android.apk`。

## 云备份

Windows 与 Android 使用同一 WebDAV 地址、用户名、密码和远程文件名（默认 `backup.zip`）。备份格式是 `angelabox-cloud/1`，不是 Android 的 SQLite。请用当前 Android 重新备份一次，电脑端才能读。图形端路径：设置 → 云备份。
