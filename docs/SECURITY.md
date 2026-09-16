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

- 该钥匙 **不是** 当前 AngelaBox 发行证书。
- 当前 `dev` 与 tag `v1.0.13` 及之后 **不以** 含该钥匙的提交为祖先。
- 仍带该文件的旧 tag（v0.1.x、v1.0.2–v1.0.9）及分支 `chainbox-audit-fix` 已删除。
- GitHub 对象回收需官方 Support 清缓存；fork `goodmen001/AngelaBox` 若仍引用旧对象，需由 fork 所有者删除或重新 fork。

请只从本仓库 Releases 安装 `AngelaBox-android.apk`，并用同目录 `.sha256` 以及应用内更新的证书校验。不要安装来路不明的包装包。

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
