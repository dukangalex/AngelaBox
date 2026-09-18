# AngelaBox 安全说明

本文只陈述已核实的事实。没有公开数据支撑的指纹、密钥或结论，不会写进产品。

## 发行签名

| 项目 | 值 |
|------|-----|
| 当前发行证书 | CN=ChainBox，创建于 2026-09-05 |
| SHA-256 | `e7041217f276a7cd860b2e210f6f7d91590f263730e09fbf73bae929d6994151` |
| 是否进过 git | 否。仅存在于 GitHub Actions secrets，用于 `release-chainbox.yml` |

覆盖安装要求签名一致。因此 **不会轮换当前发行证书**。轮换会迫使 1.0.x 用户全部卸载重装。

## 历史密钥事件

仓库早期历史曾包含 2020 年 SagerNet/nekohasekai 调试钥匙（CN 猫羽 世界，SHA-256 `32250a4b5f3a6733df57a3b9ec16c38d2c7fc5f2f693a9636f8f7b3be3549641`）。

已核实：

- 该钥匙 **不是** 当前 AngelaBox 发行证书（CN=ChainBox，`e7041217…`）。1.0.x 安装包不是这把钥匙签的。
- 工作区与当前树没有 `.jks` / `.keystore`。`app/release.keystore` 在 `7736e1e` 加入，在 `3b020925` 从树中删除。
- **全量历史（非浅克隆）** 中，`7736e1e` **是** `dev` 与全部现存 tag（`v1.0.13`–`v1.0.61-beta`）的祖先。此前「不是祖先」的判断来自浅克隆把 `e7019d4` 误当成根，**已更正**。GitHub Support Ticket 4763595 用服务端工具得到同一结论，是对的。
- 仍带该文件的旧 tag（v0.1.x、v1.0.2–v1.0.9）已删除。分支 `chainbox-audit-fix` 在 GitHub 上已不存在（`git ls-remote --heads` 仅剩 `dev`）。
- 2026-09-17：本仓库曾短暂改为私有再恢复公开。`goodmen001/AngelaBox` API 为 `fork: false`；本仓库 `forks_count` 为 0。脱离 fork **不会** 删除对象。
- 直链 `https://github.com/dukangalex/AngelaBox/commit/7736e1e` 与独立仓库 `goodmen001/AngelaBox` 上同一 SHA 目前仍能打开。GitHub 规则：只要提交还被任何分支/tag 引用，就不能 GC。要让本仓库这两条 URL 变 404，必须改写全部现存历史并重建全部 tag，然后再请 Support GC。改写 **不能** 清掉 `goodmen001` 或上游 SagerNet 上的副本。
- 因此默认 **不** 改写当前 `dev` / v1.0.13+，**不** 轮换 CN=ChainBox。直链保持 200 是接受的残留：泄露物不是发行证书。
- 将仓库改私有会清掉 star / watcher。不要为同一目的反复改可见性。

请只从本仓库 Releases 安装 `AngelaBox-android.apk`，并用同目录 `.sha256` 以及应用内更新的证书校验。不要安装来路不明的包装包。不要从 `goodmen001/AngelaBox` 安装。

## 上游 Firebase 客户端钥匙（非发行证书）

GitHub Secret scanning 于 2026-09-16 报出历史提交 `446ffa4` 中的 `app/google-services.json`（Google API Key，标签 Public leak）。

已核实：

- 作者是 SagerNet/nekohasekai（2023-10-31），一周后提交 `56a27728` 已从树中删除该文件。全量历史里该提交仍是 `dev` 的祖先。
- 绑定包名是官方 `io.nekohasekai.sfa`，Firebase 项目 `sing-b0x`。**不是** AngelaBox 的 `io.chainbox.app`。
- 这是打进 APK 的 Firebase 客户端标识，不是签名私钥，也不是本项目发行证书。
- 工作区当前树无此文件。同一文件仍出现在多个 SagerNet 客户端的公开仓库中。本项目无法轮换不属于自己的 Google Cloud 钥匙。
- 与 `7736e1e` 相同：不改写当前 `dev` / v1.0.13+ 则本仓库直链会保持 200。改写也无法收回上游与独立仓库里的副本。

## 远程地址

订阅、脚本、更新、WebDAV 只允许公网 HTTPS。HTTP、回环、RFC1918、ULA、CGNAT、链路本地与云元数据一律拒绝。下载连接钉住所校验的 IP，TLS SNI 与证书校验仍用原主机名。

应用内更新只认 GitHub 上的 `AngelaBox-android.apk`，必须同时通过 SHA-256 与发行证书校验。

## Xposed 接口

`XposedProvider` 只接受：

- SYSTEM / root / shell / 本应用 UID；或
- 精确包名 `org.lsposed.manager`、`org.lsposed.daemon`，且签名主体不是 Android Debug。

官方 LSPosed **没有在公开源码中给出可钉扎的发行证书**：`SignInfo.CERTIFICATE` 在他们自己的构建里从私钥生成，不在仓库里；Magisk zip 内的 manager/daemon APK 是未签名载荷。因此本项目 **不会猜测指纹**。未安装 LSPosed 时，同名第三方包仍可能冒充，这是框架侧未公开证书带来的上限。

## 分应用代理

自动扫描只勾选能判定网络用途的应用：

- 中国应用：面向中国网络、不依赖代理即可使用。
- 需代理应用：在中国网络下需要海外路径。
- 系统组件、厂商框架、其他 VPN/代理客户端：不自动勾选。预装的微信、Play 商店等已知消费级应用仍按用途判定。误把系统应用推进名单会导致无法联网。不确定的应用不猜成海外。

`QUERY_ALL_PACKAGES` 是列出已装应用所必需；Play 渠道构建会去掉该权限。

## 位置权限

仅当内核配置使用 Wi-Fi SSID 规则（`needWIFIState`）时才会请求。不是日常定位。

## 备份

备份会去掉 WebDAV 密码、GitHub token 和远程控制 `secret`。配置文件本身含节点凭据，这是恢复所必需，请把备份文件当作机密保存。

## 工作目录 DocumentsProvider

`WorkingDirectoryProvider` 按 Android 文档提供者约定导出，并要求系统权限 `MANAGE_DOCUMENTS`。不是对任意应用开放的文件接口。
