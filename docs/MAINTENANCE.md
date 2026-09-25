# AngelaBox 维护说明

AngelaBox 是独立客户端，不是官方 sing-box / SFA 的产品名。曾用名 ChainBox。
维护目标：内核长期跟随官方 sing-box；App 只维护组链体验、运行时覆盖与发布。

## 产品边界（必须遵守）

AngelaBox = 官方 sing-box 内核 + **模块化链式出站覆盖层** + 面向普通用户的操作界面。

- 不重新设计 sing-box，不替换内核，不另做代理协议栈。
- 组链、中国直连、广告拦截、WebRTC、DNS 兼容、备份等都是 **运行时/导入覆盖层**：只改内存中的 JSON，不改订阅原文，不改 libbox 架构。
- 冲突即停：与官方配置模型无法兼容时停止发版，而不是在内核里开特例。
- 增加的功能只为降低日常操作成本，不为单一订阅商或个人配置定制。

仓库首页若已 Detach fork，不影响发版。同步上游继续用 git remote：

```bash
git remote add upstream https://github.com/SagerNet/sing-box-for-android.git   # 若尚未添加
git fetch upstream
git checkout dev
git merge upstream/dev
```

后期同步与发版由维护者发起，不要依赖仓库首页 Sync fork。

## 仓库分工

| 仓库 | 分支 | 职责 |
|------|------|------|
| [dukangalex/sing-box](https://github.com/dukangalex/sing-box) | `chain-dev` | Chain 内核 + Windows 身份常量 |
| [dukangalex/AngelaBox](https://github.com/dukangalex/AngelaBox) | `dev` | AngelaBox Android 客户端 + 发版规格 |
| [dukangalex/sing-box-for-desktop](https://github.com/dukangalex/sing-box-for-desktop) | `angelabox` | Windows 图形端（`main` 保持官方镜像） |
| [dukangalex/sing-box-dashboard](https://github.com/dukangalex/sing-box-dashboard) | `angelabox` | 仪表盘（`main` 保持官方镜像） |

Windows 身份与云备份格式见 [IDENTITY.md](IDENTITY.md)、[BACKUP.md](BACKUP.md)。不要再对官方 SFW 做构建时改名。

Telegram 频道：[https://t.me/AngelaBox](https://t.me/AngelaBox)

| 项目 | 值 |
|------|-----|
| 应用名 | AngelaBox |
| 包名 | `io.chainbox.app` |
| 更新源 | 仅本仓库 Releases |
| 内部代码包 | `io.nekohasekai.sfa`（上游遗留，不对外） |

## 对外身份（已落地）

- 对外产品名、README、About、Release、APK 文件名、仓库路径都是 AngelaBox。包名仍为 `io.chainbox.app`。
- 只发布 `AngelaBox-android.apk`。曾用名 ChainBox 不再出安装包。
- 发版工作流是 **AngelaBox Release**（文件 `release-chainbox.yml`）。发布成功后 `telegram.yml` 向 [t.me/AngelaBox](https://t.me/AngelaBox) 发说明并上传 APK。需仓库 Secrets：`TG_BOT_TOKEN`、`TG_CHANNEL_ID`。`build-chainbox.yml` 已删除，不要恢复成第二个发版入口。
- App 更新只查 `https://api.github.com/repos/dukangalex/AngelaBox/releases`，只下载 `AngelaBox-android.apk`，必须带 SHA-256，并校验 CN=ChainBox 发行证书。
- **不要轮换当前发行私钥。** 1.0.x 全部由 CN=ChainBox（SHA-256 `e7041217…4151`）签署，这把钥匙从未进过 git。轮换会让所有 1.0.x 用户无法覆盖安装。2020 年泄露的 SagerNet JKS（CN 猫羽 世界）从未签过 1.0.x。
- **Git 历史：** 当前树没有 `.jks` / `.keystore`。全量历史上 `7736e1e` **是** `dev` 与现存 tag 的祖先（浅克隆会误判）。已于 2026-09-18 公开说明：不改写历史、不轮换 CN=ChainBox、直链 200 是接受的残留。CI overlay-guards 用 `fetch-depth: 0` 扫工作树，防止再提交。`goodmen001/AngelaBox` 已独立（`fork: false`）。**不要为清历史反复改仓库可见性。**
- 订阅、脚本、更新、WebDAV、规则集下载地址全部 HTTPS。应用 `usesCleartextTraffic=false`，仅 loopback 允许明文。下载使用系统 `HttpsURLConnection`，每次 3xx 跳转都再走 `RemoteUrlGuard`，DNS 解析失败则拒绝。HTTP 订阅（含局域网明文）一律拒绝。
- 不走 F-Droid / 官方 SagerNet 更新源。
- 不得用官方名称上架应用商店。

不做事：整包重命名 `io.nekohasekai.sfa`。那会改数千个文件、容易跟丢上游同步能力，对用户无益。

## 发版说明

`docs/RELEASE_NOTES.md` 只保留**当前版**。每次发布前整篇替换：先写这次新加入的行为，再写修正。不要把上一版的条目留在同一篇里。Telegram 发的就是这一篇。

## Windows

暂停。条件未成熟（签名、安装、服务都还不能在干净机器上单独验通）。已有 Windows 包功能残缺，无法使用，不跟进、不修、不挂到 Release。恢复之前不要改 Windows 身份常量来「顺便发一版」。详见 [WINDOWS.md](WINDOWS.md)。

## 后续，按这个顺序做

这三件事是方向，不是这一版的范围。Android 客户端继续用运行时覆盖层修启动和更新；内核不要为了一处报错去改 sing-box 源码。

### 和上游同步

- 不直接改 sing-box 核心源码。Chain 和身份常量保持离散补丁（或 submodule 加薄封装）。上游一重构 adapter / option，rebase 补丁，而不是在 fork 里散改。
- 工具链写死在 `version.properties` 和发版工作流里：Go `1.25.5`、对应的 NDK、`KERNEL_TAG` / `KERNEL_COMMIT`。上游发版不能让 CI 自己跳到新的 Go。升到官方 CI 的 1.26.8 之前，先单独验证 gomobile 和 `go:linkname`。

### 稳

- libbox 进 JNI 的入口要有 `recover()`，把 panic 收成错误交给 Kotlin，而不是让进程死。这一条在内核仓库做，不在这个 Android 树里改 Go。
- 停服务、热重载、Wi-Fi 和蜂窝切换时，关掉这次开过的 goroutine、TUN 和 UDP socket。Android 休眠很勤，漏一个就会在后台卡住。

### 以后怎么拆

- 改过的内核（增强版 libbox）单独当 SDK 维护。AngelaBox 只负责配置、界面和 VpnService。Windows 恢复时再复用这层，而不是现在继续分叉桌面仓库。
- 代理链路的单测留在 GitHub Actions 里，每次提交跑。这个 Android 仓库已经对配置自愈和更新文案有 JVM 测试；协议握手本身的测试放在内核仓库。


## Telegram 发版通知

频道：[https://t.me/AngelaBox](https://t.me/AngelaBox)

GitHub Release 发布成功后，`telegram.yml` 会按 `docs/RELEASE_NOTES.md` 往频道发更新说明：先发文字（关闭网页预览），再把 `AngelaBox-android.apk` 作为可直接安装的文件上传。`telegram.yml`（Actions 里显示为 **Telegram Release**）也可手动补发。需仓库管理员一次性配置：

1. Telegram 打开 [@BotFather](https://t.me/BotFather)，`/newbot` 拿到 token。
2. 把该 bot 加进频道 **AngelaBox**，授予「发布消息」权限。
3. 仓库 **Settings → Secrets and variables → Actions** 增加：
   - `TG_BOT_TOKEN`：BotFather 给出的 token
   - `TG_CHANNEL_ID`：`@AngelaBox`（或频道的 `-100…` 数字 ID）

未配置时发版仍成功，只是跳过频道通知。配好后也可在 Actions 里手动跑 **Telegram Release**（文件 `telegram.yml`）。

## 内核同步

当前已同步（与 README / `version.properties` 一致）：

| 项目 | 值 |
|------|-----|
| 官方上游 | SagerNet/sing-box **v1.15.0-alpha.6** |
| 本仓库 | dukangalex/sing-box 分支 `chain-dev` |
| 已对齐基线 | 官方 1.15.0-alpha.6（`go.mod` 1.25.5；官方 CI 1.26.8；本仓库发版 Go 1.25.5） |
| 内核 tag | v1.15.0-chain.3 |
| 发版钉死的 commit | `d7639f61339bd87f737aff937f7010010bb1e443`（`version.properties` 的 `KERNEL_COMMIT`） |

官方 1.14.1（2026-09-15）changelog 为 “Fixes and improvements”，无新 inbound/outbound 类型。**不跟进 v1.14.1**：稳定安装包钉的是 1.14.0；测试线已在 1.15，没有产品理由再回头吃一版修补。官方 1.15.0-alpha.6（2026-09-17）同样是修补、无新类型，测试线跟进。

Go 版本（2026-09-17 核对）：

| 项目 | 值 |
|------|-----|
| `go.mod` 语言版本 | 官方 v1.14.1、v1.15.0-alpha.5、v1.15.0-alpha.6、本 fork `chain-dev` 均为 **`go 1.25.5`** |
| 官方 CI 编译器 | v1.14.1、v1.15.0-alpha.5、v1.15.0-alpha.6 的 `.github/workflows/build.yml` 均为 **`go-version: 1.26.8`** |
| AngelaBox 发版编译器 | **Go 1.25.5**（`release-chainbox.yml` / `ci.yml`）。Windows CLI 工作流已停，不要再为它升编译器 |

AngelaBox 钉 1.25.5 是因为 `experimental/libbox/internal/oomprofile` 与 `runtimeinfo` 使用 `go:linkname` / `badlinkname` 绑 `runtime/pprof` 未导出符号和 `runtime.g` 布局，随 Go 次版本会变。官方 1.15 线已经在 1.26.8 上编过；本仓库 **尚未** 用 1.26.8 验证 gomobile / Android。升工具链应对齐 1.15 线并先验证，不是为了 1.14.1。

当前测试内核为官方 **1.15.0-alpha.6**（2026-09-17）：在 alpha.5（新 TUN 栈、Tailcat、Android auto_redirect、on_demand）之上合入修补（Windows 进程归属、go 栈内存、自动重定向 DNS 劫持、WireGuard 域名握手、libbox 命令客户端取消）。`go.mod` 为 1.25.5；官方该 tag 的 CI 用 Go 1.26.8，AngelaBox 发版仍用 Go 1.25.5。`chain-dev` 已应用到 `d7639f61`（含当时写下的 Windows 身份常量），并已打 annotated tag `v1.15.0-chain.3`。官方 tag 不是 `chain-dev` 的 git 祖先（alpha.5 当时是单亲提交接入），因此 alpha.6 按官方 tag 之间的 17 个文件接入，Chain outbound 保持不变。稳定安装包 1.0.57 仍钉 1.14.0（`03ad0a1`）。默认脚本仍为 `overlay-revision: 21`。**Windows 暂停：** 不编命令行，不编图形端。`release-windows-desktop.yml` 的构建作业是 `if: false`。见 [WINDOWS.md](WINDOWS.md)。

官方上游：`https://github.com/SagerNet/sing-box`

```bash
cd sing-box
git fetch upstream
git checkout chain-dev
git merge upstream/dev
# 只解决与 chain 相关的冲突

go test ./...
git push origin chain-dev
```

原则：

1. **Fail Closed**：链路失败不得静默落到 DIRECT。
2. **低耦合**：Chain 尽量只挂在 outbound 注册与 dial 链路上。
3. **冲突即停**：与官方架构无法兼容时停止发版。
4. **先验证再跟进**：官方 sing-box 1.14 已发布，`chain-dev` 已带 1.14 依赖。App 侧先把兼容层（fakeip / rcode / inbound sniff / rule-set URL）与链式出站做稳，再合入更新的官方提交。不要在未验证 Chain outbound 的情况下整包快进。
5. **发版核对官方功能**：CI 拉取 `SagerNet/sing-box` 的 `v$KERNEL_UPSTREAM`，用 `scripts/check_upstream_features.py` 确认官方 inbound/outbound 类型常量仍存在于 `chain-dev`。缺失即失败，不得发版。
6. **记录内核 commit**：Release 说明写入本次构建的 `chain-dev` SHA，便于复现与审计。构建仍从 `chain-dev` 分支拉取，不以可变分支代替记录。

## App 同步

官方上游：`https://github.com/SagerNet/sing-box-for-android`

```bash
cd ChainBox
git fetch upstream
git checkout dev
git merge upstream/dev
```

冲突时以 AngelaBox 为准：包名、签名、组链、配置覆盖、备份、更新检查、`release-chainbox.yml`、`version.properties`。

## 发版

1. 改 `version.properties`（`VERSION_NAME` 与 tag 去掉前导 `v` 后一致，`VERSION_CODE` 必须递增）。发布工作流会校验输入的 `version_tag` 精确等于 `v$VERSION_NAME`，不匹配会在构建前拒绝发布。
2. Actions → **AngelaBox Release** → `version_tag=vX.Y.Z`。
3. 用户安装 `AngelaBox-android.apk`，并用 `AngelaBox-android.apk.sha256` 校验。
4. 发版前对照上游 `scripts/upstream_strings/`：简体用词与官方一致，仅保留 AngelaBox 新增条目。
5. 发版说明必须包含：内核 commit SHA、官方基线 tag、官方类型常量检查结果。

Secrets：`KEYSTORE_BASE64`，以及 `KEYSTORE_PASSWORD`/`KEYSTORE_PASS`、`KEY_ALIAS`/`ALIAS_NAME`、`KEY_PASSWORD`/`ALIAS_PASS`。

## 能力边界

- 支持：分组→节点、分组→分组、多跳 chain，订阅更新后保持链路。
- 同配置链式：DNS detour 不改写到 chain（保持一跳）；selector/urltest 就地剔除 DIRECT，避免克隆后双重测速。
- 已知上游限制：跨 TLS 协议的 `detour`（SagerNet/sing-box#3205，官方不计划修复）。文档说明即可，不要在内核里做特例。
- 不支持冒充官方；不向官方仓库提交 Chain 补丁。
