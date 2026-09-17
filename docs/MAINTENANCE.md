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
| [dukangalex/sing-box](https://github.com/dukangalex/sing-box) | `chain-dev` | Chain 内核（低耦合 outbound） |
| [dukangalex/AngelaBox](https://github.com/dukangalex/AngelaBox) | `dev` | AngelaBox Android 客户端 |

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
- **Git 历史：** 当前 `dev` 可达历史和 GitHub 代码搜索都没有 `.jks` / `.keystore`。原泄露提交 `7736e1e` 不在本仓库可达对象里。CI overlay-guards 用 `fetch-depth: 0` 扫工作树和 `git log --all`，防止再提交。GitHub 仍可能保留旧 fork/PR 的不可达 blob：用账号 Settings → GitHub Support 申请 purge（附仓库名、泄露路径、commit SHA `7736e1e`）。**不要 force-push 重写现有 1.0.x 历史**——没有可删对象，只会打断覆盖安装说明和 CI SHA。
- 订阅、脚本、更新、WebDAV、规则集下载地址全部 HTTPS。应用 `usesCleartextTraffic=false`，仅 loopback 允许明文。下载使用系统 `HttpsURLConnection`，每次 3xx 跳转都再走 `RemoteUrlGuard`，DNS 解析失败则拒绝。HTTP 订阅（含局域网明文）一律拒绝。
- 不走 F-Droid / 官方 SagerNet 更新源。
- 不得用官方名称上架应用商店。

不做事：整包重命名 `io.nekohasekai.sfa`。那会改数千个文件、容易跟丢上游同步能力，对用户无益。

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
| 官方上游 | SagerNet/sing-box **v1.15.0-alpha.5** |
| 本仓库 | dukangalex/sing-box 分支 `chain-dev` |
| 已对齐基线 | 官方 1.15.0-alpha.5（Go 1.25.5） |
| 内核 tag | v1.15.0-chain.1 |
| 发版钉死的 commit | `5e1593f0f5bf1110a94aab71e55244ceb4ccceba`（`version.properties` 的 `KERNEL_COMMIT`） |

官方 1.14.1（2026-09-15）changelog 为 “Fixes and improvements”，无新 inbound/outbound 类型，且把 **Go 升到 1.26.8**。chain-dev 的 `oomprofile` 用 go:linkname 钉在 Go 1.25.5 的 `runtime/pprof` 内部符号上，**不跟进 v1.14.1**。

当前测试内核为官方 **1.15.0-alpha.5**（2026-09-16）：新 TUN TCP/IP 栈（去掉 `stack` 使用 sing-tun 自有栈，该字段 1.17 删除）、Tailcat、Android auto_redirect、on_demand、cache 写缓冲。Go 仍为 1.25.5。`chain-dev` 已快进到 `5e1593f0`，并已打 annotated tag `v1.15.0-chain.1`。稳定安装包 1.0.57 仍钉 1.14.0（`03ad0a1`）；1.0.58-beta 已编进本 commit，但发版浅克隆未打 tag，设置 → 核心显示 unknown。1.0.59-beta 在 gomobile 前打上同一 tag，并用 `BuildConfig.KERNEL_TAG` 兜底。App 侧静默去掉 TUN `stack`，不刷「已修正」横幅。1.15 刚需开关：自动重定向（已有，文案补热点/中继）、按需连接（新增，默认开）。不做成开关：Tailcat、`multi_queue`、cache `buffer_size`。

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

1. 改 `version.properties`（`VERSION_NAME` 与 tag 一致，`VERSION_CODE` 必须递增）。
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
