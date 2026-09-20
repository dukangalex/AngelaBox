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

未配置仓库证书时，CI 用一次性自签证书（`CN=AngelaBox Test`），Chrome 和 SmartScreen 都会拦截，这是预期。测试包已挂在预发布页，方便下载；正式对外发版仍须长期证书。

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

Windows 弹「未知发布者」、Chrome 拦下载，都是因为现在的安装器用的是 **CI 自签证书**（`CN=AngelaBox Test`）。Windows 不信任这把钥匙。改不了设置、改不了产品名，只能换一把**公开 CA 签发的 Authenticode 代码签名证书**。

两层警告不是同一件事：

| 你看到的 | 谁在拦 | 证书能做什么 |
|----------|--------|----------------|
| 未知发布者 / Windows 已保护你的电脑 | SmartScreen | OV/EV 签名后发布者变成你的公司名；声誉要靠下载量积累 |
| Chrome「此文件包含危险内容」 | Google 安全浏览 | 签名后通常不再硬拦；仍建议用 Edge 下第一版 |

**2024 年起 EV 不再保证立刻过 SmartScreen。** 买 OV 就够（更便宜、审核更快）。EV 只在做内核驱动或甲方采购强制时才需要。

### 1. 买哪一种

向 **Sectigo、SSL.com 或 DigiCert** 买 **OV Code Signing**（组织验证代码签名）。不要买 SSL 证书，不要买「仅文档签名」。

大约：

- Sectigo OV：约 220–320 美元 / 年
- DigiCert OV：约 400–440 美元 / 年
- 审核 1–3 个工作日；证书最长约 460 天

发布者显示的是证书上的**法定名称**（公司名），不是「AngelaBox」。想显示 AngelaBox，营业执照或 DBA 得叫这个名字。

个人开发者：多数 CA 要注册公司（美国 LLC 或国内营业执照）。没有公司，OV 很难过审。

### 2. 私钥不能导出成 P12（2023 年起的规定）

新证的私钥必须放在 **FIPS USB 令牌或云 HSM**，不能拷进 git、不能贴聊天。GitHub 托管的 Windows 跑腿**插不了 U 盘**，所以不要走「导出 P12 塞进 Secrets」这条老路（仓库里 `WINDOWS_CERTIFICATES_P12` 是给旧证留的）。

适合本仓库 CI 的三种办法，选一个：

1. **云签名（推荐）**：SSL.com eSigner 或 DigiCert KeyLocker。CI 用账号 + 一次性密码调他们的签名 API，私钥不出云。
2. **自建 Windows 跑腿**：U 盘令牌插在你自己的电脑上，把 AngelaBox Windows Desktop 工作流改到 self-hosted runner。
3. **本机签**：CI 仍出未签名包，你在插着令牌的电脑上用 `signtool` 签完再上传 Release。

主程序 `AngelaBox.exe` 和守护进程 `sing-box-daemon.exe` **必须同一把证**。签名时加 RFC 3161 时间戳，证书过期后旧安装包仍显示有效。

### 3. 签完之后

1. 工作流日志应出现 `Using repository Authenticode certificate.`，而不是 `issuing a CI self-signed`。
2. 安装器属性 → 数字签名，发布者是你的公司名。
3. 第一批下载仍可能看到 SmartScreen 黄条，点「仍要运行」即可。下载量上来后黄条会少。
4. Chrome 硬拦通常随签名消失；没消就继续用 Edge。

Android 发行证书（CN=ChainBox）和 Windows Authenticode **不是同一把**，也不会为了 Windows 去轮换 Android 证书。

源码：

- 桌面：[dukangalex/sing-box-for-desktop](https://github.com/dukangalex/sing-box-for-desktop) 分支 `angelabox`
- 仪表：[dukangalex/sing-box-dashboard](https://github.com/dukangalex/sing-box-dashboard) 分支 `angelabox`
- 内核：[dukangalex/sing-box](https://github.com/dukangalex/sing-box) 分支 `chain-dev`（身份常量已合入，tag `v1.15.0-chain.3`）

不要从第三方站点下载名为 SFW 或 sing-box 的包装包。Android 用户仍只安装 `AngelaBox-android.apk`。

## 云备份

Windows 与 Android 使用同一 WebDAV 地址、用户名、密码和远程文件名（默认 `backup.zip`）。备份格式是 `angelabox-cloud/1`，不是 Android 的 SQLite。请用当前 Android 重新备份一次，电脑端才能读。图形端路径：设置 → 云备份。
