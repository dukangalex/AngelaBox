# AngelaBox 身份表（冻结）

方案 **B**：Windows 主程序就是 `AngelaBox.exe`。三端必须逐字相同，禁止再靠 CI 字符串替换官方 SFW。

**发版状态：** Android 在发。Windows 暂停。下表里的 Windows 名字留着，避免以后恢复时和官方 SFW 撞车；现在不要按这张表去打 Windows 包。已有 Windows 包功能残缺，无法使用，不维护。见 [WINDOWS.md](WINDOWS.md)。

本文件是发版对照表。改其中任何一行，必须同时改内核、桌面安装脚本和 Android 文档，并重新验证。Windows 恢复之前，不要求重新验证 Windows 安装。

## 产品

| 项 | 值 |
|---|---|
| 产品名 | AngelaBox |
| 曾用名 | ChainBox（不再出现在安装包文件名） |
| 许可 | GPL-3.0-or-later（继承上游） |
| 与官方关系 | 独立衍生，不冒充 SagerNet / SFW / `io.nekohasekai.sfw` |

## Android（已冻结，不得为 Windows 而改）

| 项 | 值 |
|---|---|
| 包名 | `io.chainbox.app` |
| 发行证书 | CN=ChainBox，SHA-256 `e7041217f276a7cd860b2e210f6f7d91590f263730e09fbf73bae929d6994151` |
| 证书存放 | 仅 GitHub Actions secrets，**永不进 git** |
| 安装包 | `AngelaBox-android.apk` |
| 仓库 | [dukangalex/AngelaBox](https://github.com/dukangalex/AngelaBox) 分支 `dev` |
| 上游 | `SagerNet/sing-box-for-android`，单向 merge |

## Windows 图形端（方案 B）

| 项 | 值 | 不得使用 |
|---|---|---|
| 显示名 / productName | AngelaBox | sing-box |
| 主程序文件名 | `AngelaBox.exe` | `sing-box.exe` |
| appId | `io.chainbox.desktop` | `io.nekohasekai.sfw` |
| 安装包文件名 | `AngelaBox-windows-${arch}.exe`（NSIS）+ `AngelaBox-v${version}-windows-amd64.zip`（便携） | `SFW-*.exe` |
| 安装目录 | `C:\Program Files\AngelaBox\` | `C:\Program Files\sing-box\` |
| 守护进程文件名 | `sing-box-daemon.exe`（布局保持官方：`resources\daemon\`） | 不要改路径层级 |
| Windows 服务名 | `angelabox-daemon` | `sing-box-daemon` |
| 服务显示名 | AngelaBox Service | sing-box Service |
| 服务数据目录 | `C:\ProgramData\angelabox-daemon` | `C:\ProgramData\sing-box-daemon` |
| 安装器注册表 | `HKLM\Software\AngelaBox\desktop` | `Software\SagerNet\sing-box` |
| 命名管道前缀 | `\\.\pipe\angelabox-worker.` | `\\.\pipe\sing-box-worker.` |
| 守护进程管道 | `\\.\pipe\ProtectedPrefix\Administrators\angelabox` | `\\.\pipe\ProtectedPrefix\Administrators\sing-box` |
| 图形端用户数据 | `%APPDATA%\AngelaBox` | `%APPDATA%\sing-box` |
| Authenticode | 同一把长期证书签主程序 **和** 守护进程 | 每次构建新的自签证书发「正式」包 |
| 证书存放 | secrets `WINDOWS_CERTIFICATES_P12` + `WINDOWS_P12_PASSWORD` | 仓库、日志、README |
| 源码 | [dukangalex/sing-box-for-desktop](https://github.com/dukangalex/sing-box-for-desktop) 分支 `angelabox` | 内核里的官方 `clients/desktop` 子模块现场改名 |
| 仪表盘 | [dukangalex/sing-box-dashboard](https://github.com/dukangalex/sing-box-dashboard) 分支 `angelabox` | 构建时仍指向 `SagerNet/sing-box-dashboard` |

图形便携包文件名仍定为 `AngelaBox-v${version}-windows-amd64.zip`（解压应运行 `AngelaBox.exe`）。CLI 压缩包仍是无版本号的 `AngelaBox-windows-amd64.zip`。**这两类都暂停，不要发，不要装。** 名字留在这里，只为恢复时不要和官方包撞名。

## 内核

| 项 | 值 |
|---|---|
| 仓库 | [dukangalex/sing-box](https://github.com/dukangalex/sing-box) 分支 `chain-dev` |
| 身份补丁 | 已合入 `chain-dev`（`5ef2960b` / `v1.15.0-chain.4`，在 `d7639f61` 之上修 urltest 失败换节点）。`angelabox-identity` 与 `chain-dev` 同提交。图形安装包仍须干净机器装通后再发 |
| 上游 | `SagerNet/sing-box`，只解决 Chain 与身份常量冲突 |
| `applicationExecutableName` | `AngelaBox.exe` |
| `daemonExecutableName` | `sing-box-daemon.exe` |
| `serviceName` | `angelabox-daemon` |
| `workerPipePrefix` | `\\.\pipe\angelabox-worker.` |
| 守护进程管道 | `\\.\pipe\ProtectedPrefix\Administrators\angelabox` |

官方 SFW 若已安装，服务名必须错开，否则互相卸载。

## 云备份（两端同一帐号）

见 [BACKUP.md](BACKUP.md)。摘要：

| 项 | 值 |
|---|---|
| 传输 | 公网 HTTPS WebDAV，同一 URL / 用户名 / 密码 / 远程文件名 |
| 默认远程文件 | `backup.zip`（两端相同，已有 Android 用户不用改名） |
| User-Agent | `AngelaBox-WebDAV` |
| 格式 | `angelabox-cloud/1`（ZIP 内 JSON，不是 Android SQLite） |
| 不含 | WebDAV 密码、GitHub token、远控 secret |

## 仓库拓扑

不要把 Electron 塞进 Android 仓库根目录。

| 仓库 | 分支 | 职责 |
|---|---|---|
| dukangalex/sing-box | `chain-dev` | 内核 + Chain + Windows 身份常量 |
| dukangalex/AngelaBox | `dev` | Android 客户端 + 发版 + 身份/备份规格 |
| dukangalex/sing-box-for-desktop | `angelabox` | Windows 图形端；`main` 保持官方镜像 |
| dukangalex/sing-box-dashboard | `angelabox` | 仪表盘灵魂；`main` 保持官方镜像 |

`main` 只进官方，不改。AngelaBox 差异只活在 overlay 分支。

## 防泄露

- `.gitignore` 必须包含 `*.jks` `*.keystore` `*.p12` `*.pfx` `*.pem` `signing.local.json` `key.properties`
- Secret scanning + push protection 四个仓库全开
- CI overlay-guard：工作树出现钥匙文件或 `SFW-*.exe` 即失败
- 不改写 AngelaBox git 历史；新桌面线从官方 fork 前进
- 不轮换 CN=ChainBox
