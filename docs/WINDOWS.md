# AngelaBox Windows

身份与文件名以 [IDENTITY.md](IDENTITY.md) 为准。云备份与 Android 共用同一 WebDAV 帐号，见 [BACKUP.md](BACKUP.md)。

AngelaBox 的 Windows 包与 Android 安装包使用同一份 `chain-dev` 内核（官方 sing-box 加上 Chain outbound）。**不是**官方 sing-box for Desktop（SFW），不得用官方名称、图标或 `io.nekohasekai.sfw` 冒充。

构建过程完全开源：内核 `dukangalex/sing-box`（`chain-dev`）、图形 overlay `dukangalex/sing-box-for-desktop`（`angelabox`）、仪表 `dukangalex/sing-box-dashboard`（`angelabox`）、Android 与发版脚本 `dukangalex/AngelaBox`（`dev`）。GitHub Actions 公开跑 **AngelaBox Windows Desktop** 与 **AngelaBox Release**。

因未购买商业代码签名证书，当前 Windows 包用 CI **自签**（`CN=AngelaBox Test`）。Chrome 可能把 `.exe` 标成「未经验证的文件」。GitHub 域名信誉高，**便携 zip** 一般能过下载拦截；安装器 `.exe` 仍可能被拦。

## 图形客户端（要的是这个）

从 GitHub Release 下载 **`AngelaBox-vX.X.X-windows-amd64.zip`**，解压后运行其中的 `AngelaBox.exe`。这是带窗口的便携版客户端，压缩包包含运行所需的守护进程与资源。

1. 解压 `AngelaBox-v1.0.65-windows-amd64.zip`，运行其中的 `AngelaBox.exe`。如需安装版，可另行下载同页的 `AngelaBox-windows-*.exe`。
2. SmartScreen 未知发布者：更多信息 → 仍要运行。
3. 若弹出「数据迁移已完成，但无法删除旧数据（代码 40）」，点确定即可。

所有构建过程、打包脚本和源代码都在本项目公开。由于尚未购买商业 Authenticode 代码签名证书，当前 Windows 包使用 CI 自签证书；Chrome 可能将其中的 `.exe` 标记为「未经验证的文件」。如遇下载阻断，按 **Ctrl + J** 打开 Chrome 下载页，选择 **保留危险文件 → 仍然保留**。发布资产使用 GitHub 域名并以 `.zip` 分发，通常可避免浏览器在 HTTP 下载阶段显示「不安全下载」红字；这不改变 SmartScreen 对自签 `.exe` 的信誉判断。

图形安装包 / 便携包必须同时满足：

1. 主程序文件名是 `AngelaBox.exe`
2. 守护进程按官方布局放在 `resources\daemon\sing-box-daemon.exe`（便携包在解压目录的对应 resources）
3. 内核 `applicationExecutableName` 也是 `AngelaBox.exe`
4. Windows 服务名是 `angelabox-daemon`（不得与官方 `sing-box-daemon` 冲突）
5. 主程序和守护进程用**同一把**证书（现阶段为 CI 自签；买到 OV 后再换）

桌面快捷方式图标是透明底的立方体（无白底方块）。若仍看到白底或官方立方体，删掉旧快捷方式后重新安装，或重启一次资源管理器刷新图标缓存。

未配置仓库证书时，CI 用一次性自签证书（`CN=AngelaBox Test`）。`.zip` 仅改善下载通道的兼容性，不能让自签 `.exe` 获得 SmartScreen 信誉；正式对外发版仍须长期证书。

Chrome 安全浏览看的是**文件种类 + 签名 + 下载源**，不是「开源就不拦」。

- GitHub Release 上的 **zip**（尤其是普通压缩包）通常直接放行，因为 github.com 信誉高。
- Actions 产物 `windows-desktop-x64.zip` 里面是 **未用商业证书签的 NSIS 安装器**，容易被标「包含危险内容」，只有删除按钮。
- 直接下 `.exe` 安装器：即使在 GitHub Release 上，也可能显示「未经验证」。`Ctrl + J` → 保留即可。
- 以前能下到的 `AngelaBox-windows-amd64.zip` 是**命令行内核**，不是图形客户端，所以没被当成安装器拦截。

买到 OV/EV Authenticode 之前，请用 **`AngelaBox-v*-windows-amd64.zip`**。怎样换成商业证书见下文。

## 智能防回环

TUN 若把本机连节点的流量再抓进隧道，会自连自、CPU 打满或完全断网。Windows 图形端启动时强制：

1. `route.auto_detect_interface = true`：出站走真实网卡，不走 TUN。
2. TUN `auto_route` + `strict_route`（配置覆盖「严格路由」开着时）。
3. 进程直连：`AngelaBox.exe`、`sing-box-daemon.exe` 不进隧道。
4. 中国直连 / 局域网规则仍在隧道里走 DIRECT，不绕回代理。

不要在订阅里给叶节点再写 `detour` 指回本机 mixed/TUN 端口。

## 命令行客户端

同次 Release 也会附命令行内核压缩包：

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

Windows 图形端使用 `angelabox-daemon` 服务、独立数据目录和受保护的 `angelabox` 命名管道。启动、重载和守护进程发现均只接受这一专有服务/管道前缀，避免与官方 SFW 或旧 `sing-box-daemon` 互相接管并形成代理回环。两层警告不是同一件事：

| 你看到的 | 谁在拦 | 证书能做什么 |
|----------|--------|----------------|
| 未知发布者 / Windows 已保护你的电脑 | SmartScreen | OV/EV 签名后发布者变成你的公司名；声誉要靠下载量积累 |
| Chrome「此文件包含危险内容」 | Google 安全浏览 | 签名后通常不再硬拦；仍建议用 Edge 下第一版 |

**2024 年起 EV 不再保证立刻过 SmartScreen。** 买 OV 就够（更便宜、审核更快）。EV 只在做内核驱动或甲方采购强制时才需要。

### 1. 买哪一种

1. 向 DigiCert、Sectigo 或 SSL.com 购买 **OV** 代码签名（不要买 SSL，不要买「仅文档签名」）。2024 年起 EV 不再保证立刻过 SmartScreen，买 OV 即可。
2. 新证私钥必须放 USB 令牌或云 HSM，不能导出 P12。GitHub 托管跑腿插不了 U 盘，推荐 SSL.com eSigner / DigiCert KeyLocker。
3. 主程序和守护进程同一把证，加 RFC 3161 时间戳。
4. 证书上的发布者是公司法定名。Android CN=ChainBox 和 Windows Authenticode 不是同一把，也不会为了 Windows 去轮换 Android 证书。

源码：

- 桌面：[dukangalex/sing-box-for-desktop](https://github.com/dukangalex/sing-box-for-desktop) 分支 `angelabox`
- 仪表：[dukangalex/sing-box-dashboard](https://github.com/dukangalex/sing-box-dashboard) 分支 `angelabox`
- 内核：[dukangalex/sing-box](https://github.com/dukangalex/sing-box) 分支 `chain-dev`（身份常量已合入，tag `v1.15.0-chain.3`）

不要从第三方站点下载名为 SFW 或 sing-box 的包装包。Android 用户仍只安装 `AngelaBox-android.apk`。

## 云备份

Windows 与 Android 使用同一 WebDAV 地址、用户名、密码和远程文件名（默认 `backup.zip`）。备份格式是 `angelabox-cloud/1`，不是 Android 的 SQLite。请用当前 Android 重新备份一次，电脑端才能读。图形端路径：设置 → 云备份。

