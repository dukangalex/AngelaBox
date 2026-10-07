# AngelaBox 云备份

Windows 客户端已暂停，见 [WINDOWS.md](WINDOWS.md)。下面的「两端共用」是格式约定，留给以后恢复 Windows 时用。**现在只在 Android 上备份和恢复。** 不要为了同步去装残缺的 Windows 包。

手机以后如果和电脑共用，用的是 **同一套 WebDAV 帐号、同一个远程文件**。这不是官方 SFW 的 `.bpf`，也不是把 Android 的 SQLite 直接扔给电脑。交换的是下面这份可移植 ZIP。

## 帐号怎么共用

| 项 | 规则 |
|---|---|
| 服务器 | 同一条公网 `https://…` WebDAV 地址 |
| 用户名 / 密码 | 同一套。密码只存在本机，**不进备份文件** |
| 远程文件名 | 默认 `backup.zip`。两端必须填同一个名字 |
| 传输 | 仅 HTTPS；禁止 HTTP、回环、RFC1918、云元数据 |
| User-Agent | `AngelaBox-WebDAV` |
| 证书 | 系统校验证书链，不关闭校验 |

Android 设置 → 备份与恢复里填好的 URL / 用户名 / 文件名，在 Windows 填同样三样即可。覆盖后本机 WebDAV 密码保留，不会被备份清空。

## ZIP 布局（`angelabox-cloud/1`）

```
manifest.json
profiles.json
settings.json
configs/<uuid>.json
```

Android 为了本机覆盖恢复，仍可能附带根目录的 `settings.db` / `profiles.db`（v2）。Windows **必须忽略** `.db`，只读 JSON。没有 JSON 的旧备份，Windows 不能直接用；请在当前 Android 上重新备份一次。

### `manifest.json`

```json
{
  "version": 3,
  "format": "angelabox-cloud/1",
  "app": "angelabox",
  "written_by": "android",
  "time": 1710000000000,
  "secrets": "omitted"
}
```

`written_by` 为 `android` 或 `windows`。`app` 必须是 `angelabox`（历史 v2 曾写 `chainbox`，仅 Android 认）。

### `profiles.json`

配置用 UUID，不用 Android 的数字 id，也不用 Windows SQLite 的内部 id。链式绑定按 UUID 对齐。

```json
{
  "selected": "550e8400-e29b-41d4-a716-446655440000",
  "profiles": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "机场",
      "type": "remote",
      "remote_url": "https://example.com/sub",
      "auto_update": true,
      "auto_update_interval_minutes": 60,
      "last_updated": 1710000000000,
      "icon": null,
      "config": "configs/550e8400-e29b-41d4-a716-446655440000.json",
      "order": 0
    }
  ]
}
```

`type`：`local` 或 `remote`。`remote_url` 必须是公网 HTTPS，恢复时再走同一套地址守卫。

### `settings.json`（可移植字段）

两端都恢复：

- `china_direct` `ads_block` `strict_route` `dns_protect`
- `disable_ipv6` `disable_quic` `exclude_cn_quic` `webrtc_protect`
- `on_demand` `config_normalize` `auto_redirect`
- `chain_bindings`：`[{ "profile_id", "entry_tag", "landing_profile_id", "landing_tag" }]`，id 都是 UUID
- `overlay_scripts`：脚本目录（含代码）
- `overlay_script_bindings`：`{ "<profile-uuid>": ["script-id", …] }`
- `webdav.url` `webdav.user` `webdav.remote_file`（**没有密码**）

本机专用、不进可移植文件：

- Android：分应用名单、Xposed、特权设置、包查询方式
- Windows：系统代理、开机启动、窗口位置
- 两端：WebDAV 密码、GitHub token、远控 secret

## 恢复策略

与 Android 现有语义相同：

| 策略 | 行为 |
|---|---|
| 覆盖 | 用备份替换配置列表与可移植设置；先停服务 |
| 兼容 | 按「同名或同一订阅 URL」跳过已有项，把其余配置追加进来，不改本机设置 |

Windows 读到 v3 JSON 就按上表做。读到只有 `.db` 的 v2 备份时，提示用当前 Android 重新备份。

Windows 图形端设置 → 云备份 填写与手机相同的 WebDAV 帐号。分流覆写开关（中国直连 / 广告拦截等）会随 ZIP 往返；启动时叠加到当前配置，不改写订阅文件。Windows 会保存 overlay 脚本但暂不执行脚本引擎。

## 安全

- 备份含节点凭据，按机密保存
- 写备份时去掉 WebDAV 密码、GitHub token、远控 secret
- 恢复远程 URL / 脚本 URL 必须再次通过 HTTPS 地址守卫
- ZIP 有条目数、单文件、总体积上限；下载后校验 ZIP 魔数
