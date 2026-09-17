# AngelaBox

<p align="center">
  <img src="docs/brand/AngelaBox-icon-512.png" width="160" height="160" alt="AngelaBox">
</p>

<p align="center"><strong>AngelaBox</strong></p>

**基准（始终做到，不只是口号）**

| 基准 | 做法 |
|------|------|
| 注重隐私 | 不上传配置与日志，崩溃报告不含配置明文，日志不落盘 |
| 安全防护 | 校验 APK、防篡改覆盖安装；启动失败明示并回滚，不静默直连 |
| 高能低耗 | 关进程扫描、限制日志缓冲、测速只在需要时跑、分应用扫描不解析应用组件 |
| 开箱即用 | 配置规范化只在确有错误时修正（没有错误不提示）/ 中国直连 / DNS 防泄漏 / 广告拦截 / 严格路由 / 禁用 IPv6 / 禁用 QUIC（放行国内）默认开；脚本开着时由同一套开关控制，不叠两套规则；节点能用就能代理 |

AngelaBox 是基于 [sing-box](https://github.com/SagerNet/sing-box) 内核的 Android 代理客户端。项目保持官方内核完整，并在其上提供模块化的链式出站与面向普通用户的操作界面。

本项目与 SagerNet 及官方 sing-box 无从属或授权关系，不得使用官方名称及标志进行商业发布或应用商店上架。

- 发行版：[Releases](https://github.com/dukangalex/AngelaBox/releases)
- 构建：[Actions](https://github.com/dukangalex/AngelaBox/actions)
- 频道：[Telegram](https://t.me/AngelaBox)
- 使用说明：[docs/USER_GUIDE.md](docs/USER_GUIDE.md)
- 安全说明：[docs/SECURITY.md](docs/SECURITY.md)
- 维护说明：[docs/MAINTENANCE.md](docs/MAINTENANCE.md)

## 项目标识

| 项目 | 值 |
|------|-----|
| 应用名称 | AngelaBox |
| 应用包名 | `io.chainbox.app`（保持不变，便于覆盖安装） |
| 客户端仓库 | [dukangalex/AngelaBox](https://github.com/dukangalex/AngelaBox)（分支 `dev`） |
| 内核仓库 | [dukangalex/sing-box](https://github.com/dukangalex/sing-box)（分支 `chain-dev`） |
| 更新检查 | 仅本仓库 GitHub Releases |
| 应用图标 | 白色底、居中立方体（橙黄顶 / 天蓝正面 / 玫红侧面），见 [docs/brand](docs/brand) |
| 安装包 | `AngelaBox-android.apk` |

曾用名 ChainBox。产品名称与代码仓库均已更名为 AngelaBox；应用包名仍为 `io.chainbox.app`，以免打断已安装用户的覆盖更新。

## 上游内核

当前发版对齐的内核型号写在 `version.properties`（`KERNEL_*`），并在 GitHub Release 中记录内核 commit SHA。

| 项目 | 值 |
|------|-----|
| 官方上游 | [SagerNet/sing-box](https://github.com/SagerNet/sing-box) **v1.15.0-alpha.5** |
| 本项目内核 | [dukangalex/sing-box](https://github.com/dukangalex/sing-box) 分支 **`chain-dev`** |
| 已同步基线 | 官方 **sing-box 1.15.0-alpha.5**（Go 1.25.5；Tailcat、新 TUN 栈、Android auto_redirect） |
| 内核型号 / tag | `v1.15.0-chain.1`（`chain-dev` 当前 git tag；App 内 `Libbox.version()` 为官方 `1.15.0-alpha.5`） |
| 客户端版本 | 见 `version.properties` 的 `VERSION_NAME`（当前为 **1.0.58-beta** 测试版） |

### 同步更新策略

1. **跟随官方，不替换内核。** AngelaBox 在官方 sing-box 之上提供模块化链式出站与普通用户界面，不另做协议栈，不为跟版而改内核架构。
2. **内核：** `git fetch` 官方 `SagerNet/sing-box`，merge 进 `chain-dev`，只解决与 Chain outbound 相关的冲突。
3. **App：** `git fetch` 官方 `SagerNet/sing-box-for-android`，merge 进本仓库 `dev`。冲突以 AngelaBox 为准（包名、组链、覆盖层、备份、更新检查、发版工作流）。
4. **Fail Closed：** 链路失败必须报错并停止启动，不得静默落到 DIRECT。
5. **先验证再合入。** 官方新版本发布后，先把 App 兼容层（DNS / inbound / rule-set）和链式出站做稳，**验证 Chain outbound 之后**再合入更新的官方提交，避免未验证的整包快进。官方 1.14.1（2026-09-15）仅为修补、无新协议类型，且把 Go 升到 1.26.8；`chain-dev` 的 oomprofile 依赖 Go 1.25.5 内部符号，**不跟进 v1.14.1**。当前测试内核对齐官方 **v1.15.0-alpha.5**（Go 仍 1.25.5）。稳定安装包仍是 1.0.57（1.14.0）；1.0.58-beta 才使用 1.15 测试内核。
6. **发版核对官方功能。** 每次发布会拉取 `version.properties` 中的官方 tag，确认官方 inbound/outbound 类型常量仍存在于 `chain-dev`；缺失则拒绝发版。Release 说明记录内核 commit SHA。
7. **功能范围。** 本项目增加的能力只为降低日常操作成本，不改变官方配置模型。

细节与命令见 [docs/MAINTENANCE.md](docs/MAINTENANCE.md)。

## 架构

官方 sing-box 内核保持完整。链式出站、中国直连、广告拦截、WebRTC、DNS/入站兼容等都是 **模块化运行时覆盖层**：只在导入/启动时改运行时 JSON，不改订阅文件，不替换内核。产品面向社区通用场景，不为单一订阅商或个人配置定制。

## 功能范围

- 多级出站：在当前配置中选择入口分组/节点，再选择落地（可来自当前或其他配置）；外部访问的源地址应为落地节点地址。**每份配置独立保存链路**。入口只作为链式第一跳，不会成为出口。
- 实时拓扑：仪表首页以下行速率、当前节点/延迟和最多四列的放射状路径为主（来源 → 规则 → 入口/落地）。首页图标为启动/停止开关。链式时中国直连是底层路由，不作为中间跳或当前节点显示。未链式时直连流量为灰色线束。
- 链路保持：出口选择保存于本地；远程订阅更新后仍按已保存的出口复用，不必重配。
- 运行时覆盖：中国直连、广告拦截、严格路由、DNS、IPv6、QUIC、WebRTC 防护。开启后**强制覆盖**对应字段，不修改订阅原文。脚本开着时由脚本按这些开关写出一套规则，应用不再重复写入。
- 备份与恢复：本地文件及 WebDAV（覆盖=完全替换，兼容=与现有共存）。备份不含账号密码。
- 更新校验：Releases 附带 APK SHA-256；应用内下载在存在校验和时会验证。
- 日志：内核日志等级默认 info。

具体操作见 [docs/USER_GUIDE.md](docs/USER_GUIDE.md)。

## 多级出站

```
设备 → 入口节点 → 落地节点 → 目的站
```

仪表页以放射状实时路径显示流量：来源 → 规则 → 当前入口/落地。链式时中国直连覆盖层不进入路径（避免与落地跳来跳去）。未链式时直连流量为灰色线束。Direct 模式显示设备 → DIRECT。首页图标用于启动或停止服务。

1. 导入并启用配置，确认基础连通。
2. 在「工具 → 链式代理」中为**当前配置**选择入口与落地并保存。其他配置可各自绑定不同落地。
3. 重载服务后验证出站公网地址。

取消链式后，出站恢复为当前配置的默认出口。

同配置链式不会改写 DNS `detour`（解析保持一跳），并对 selector/urltest 就地过滤 DIRECT，避免克隆后双重测速。

已知上游限制：两种均启用 TLS 的协议互相 `detour`（例如 VLESS 经 Trojan）可能失败，见 [SagerNet/sing-box#3205](https://github.com/SagerNet/sing-box/issues/3205)。入口或落地一侧使用 SOCKS / HTTP / SSH 更稳妥。AngelaBox 不能在应用层绕过该限制。

## 下载

请从 [Releases](https://github.com/dukangalex/AngelaBox/releases) 下载 `AngelaBox-android.apk`，并用同目录 `AngelaBox-android.apk.sha256` 校验。

```
sha256sum -c AngelaBox-android.apk.sha256
```

覆盖安装要求使用相同签名证书，且新版本的 `versionCode` 须大于已安装版本。自行构建时须在仓库 Secrets 中配置：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD` 或 `KEYSTORE_PASS`
- `KEY_ALIAS` 或 `ALIAS_NAME`
- `KEY_PASSWORD` 或 `ALIAS_PASS`

## 构建

使用工作流 `.github/workflows/release-chainbox.yml`（Actions 里显示为 **AngelaBox Release**）：

1. 从钉死的 `KERNEL_COMMIT` 编译 `libbox.aar`
2. 与官方 sing-box 核对 inbound/outbound 类型常量
3. 校验发行证书 SHA-256 后组装 Android APK，生成 `AngelaBox-android.apk.sha256`
4. 指定 `version_tag` 后发布至 GitHub Releases（只出 `AngelaBox-android.apk`）

客户端版本号以 `version.properties` 为准。

## 致谢

AngelaBox 建立在上游开源工作之上，谢谢：

- [sing-box](https://github.com/SagerNet/sing-box)，由 [nekohasekai](https://github.com/nekohasekai) 与 [SagerNet](https://github.com/SagerNet) 维护的通用代理平台
- [sing-box for Android](https://github.com/SagerNet/sing-box-for-android)，本客户端的上游界面与服务框架

上述致谢不构成从属、授权或官方认可。

## 许可

本仓库继承上游 [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)。
上游代码版权归属原作者。AngelaBox 为独立衍生工作，不代表上游项目。
