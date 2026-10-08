import { AuditFinding, ScriptAuditReport } from '../types/proxy';

// Predefined default scripts with built-in benchmarks
export const HARDENED_SCRIPTS = {
  systemd: `[Unit]
Description=AngelaBox sing-box Next-Gen Proxy Core
Documentation=https://sing-box.sagernet.org
After=network.target nss-lookup.target network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
# Privilege drop & Linux capability isolation
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_BIND_SERVICE CAP_NET_RAW
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_BIND_SERVICE CAP_NET_RAW

# File System & Security Hardening
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_NETLINK AF_UNIX

# Directory permissions
ReadWritePaths=/var/lib/angelabox /var/log/angelabox /etc/angelabox/cache.db
ConfigurationDirectory=angelabox
StateDirectory=angelabox
LogsDirectory=angelabox

# Execution & Auto-restart
ExecStartPre=/usr/local/bin/sing-box check -c /etc/angelabox/config.json
ExecStart=/usr/local/bin/sing-box run -c /etc/angelabox/config.json
ExecReload=/bin/kill -HUP $MAINPID
Restart=on-failure
RestartSec=5s
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target`,

  linuxBash: `#!/usr/bin/env bash
# ========================================================
# AngelaBox Hardened Linux Runner & Health Monitor
# ========================================================
set -euo pipefail
IFS=$'\\n\\t'

CONFIG_DIR="/etc/angelabox"
CONFIG_FILE="\${CONFIG_DIR}/config.json"
BIN_PATH="/usr/local/bin/sing-box"
LOG_FILE="/var/log/angelabox/core.log"

# 1. Root & Capability Check
if [[ \$EUID -ne 0 ]]; then
  echo "[-] 错误: AngelaBox TUN 虚拟网卡模式需要 root 权限执行。" >&2
  exit 1
fi

# 2. Dependency Sanity Check
for cmd in sing-box ip curl; do
  if ! command -v "\$cmd" &>/dev/null; then
    echo "[-] 缺少关键依赖: \$cmd，请先安装。" >&2
    exit 1
  fi
done

# 3. Create secure directories if not present
mkdir -p "\${CONFIG_DIR}" "/var/log/angelabox" "/var/lib/angelabox"
chmod 750 "\${CONFIG_DIR}"
chmod 600 "\${CONFIG_FILE}" 2>/dev/null || true

# 4. Atomic Configuration Pre-flight Validation
echo "[+] 正在执行 sing-box 配置有效性校验..."
if ! "\$BIN_PATH" check -c "\$CONFIG_FILE"; then
  echo "[!] 错误: 配置文件语法检查未通过，中止启动以防网络中断！" >&2
  exit 1
fi

# 5. Enable IP Forwarding for TUN auto-route
echo "[+] 校验内核 ipv4/ipv6 转发开关..."
sysctl -w net.ipv4.ip_forward=1 >/dev/null
sysctl -w net.ipv6.conf.all.forwarding=1 >/dev/null 2>&1 || true

# 6. Start AngelaBox with Process Confinement
echo "[+] AngelaBox 启动中... 日志写入: \$LOG_FILE"
exec "\$BIN_PATH" run -c "\$CONFIG_FILE" >> "\$LOG_FILE" 2>&1
`,

  windowsPowerShell: `# ========================================================
# AngelaBox Hardened Windows PowerShell Runner
# ========================================================
#Requires -RunAsAdministrator

$ErrorActionPreference = "Stop"
$WorkingDir = "$PSScriptRoot"
$BinPath = Join-Path $WorkingDir "sing-box.exe"
$ConfigPath = Join-Path $WorkingDir "config.json"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   AngelaBox Windows Client Controller   " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 1. Check Binary
if (-not (Test-Path $BinPath)) {
    Write-Error "[-] 找不到 sing-box.exe，请将内核置于本脚本同级目录。"
    Exit 1
}

# 2. Check Config
if (-not (Test-Path $ConfigPath)) {
    Write-Error "[-] 找不到 config.json 配置文件，请从 AngelaBox Web 导出保存。"
    Exit 1
}

# 3. Config Validation
Write-Host "[+] 正在审计校验 config.json 语法..." -ForegroundColor Yellow
$checkProcess = Start-Process -FilePath $BinPath -ArgumentList ('check -c "' + $ConfigPath + '"') -NoNewWindow -Wait -PassThru
if ($checkProcess.ExitCode -ne 0) {
    Write-Error "[-] 配置文件语法不合规，退出执行。"
    Exit 1
}

# 4. Check & Install Wintun driver if necessary
$wintunDll = Join-Path $WorkingDir "wintun.dll"
if (-not (Test-Path $wintunDll)) {
    Write-Warning "[!] 未检测到 wintun.dll，TUN 模式若需 Wintun 加速请放入同级目录。"
}

# 5. Add Windows Firewall Rule
Write-Host "[+] 检查 Windows 防火墙规则..." -ForegroundColor Green
$RuleName = "AngelaBox-SingBox-Outbound"
$ruleExists = Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue
if (-not $ruleExists) {
    New-NetFirewallRule -DisplayName $RuleName -Direction Outbound -Program $BinPath -Action Allow -Profile Any | Out-Null
}

# 6. Run Core
Write-Host "[+] AngelaBox 启动成功，按 Ctrl+C 安全退出并还原路由..." -ForegroundColor Green
& $BinPath run -c "$ConfigPath"
`,

  autoUpdater: `#!/usr/bin/env bash
# ========================================================
# AngelaBox Safe Atomic Subscription Updater & Switcher
# ========================================================
set -euo pipefail

SUB_URL="\${1:-}"
CONFIG_TARGET="/etc/angelabox/config.json"
TEMP_DOWNLOAD="/tmp/angelabox_new_config.json"
BACKUP_CONFIG="/etc/angelabox/config.backup.json"

if [[ -z "\$SUB_URL" ]]; then
  echo "用法: \$0 <订阅链接或API端点>" >&2
  exit 1
fi

echo "[+] 正在拉取远端订阅配置..."
# User-Agent header with strict timeout and SSL verify
if ! curl -fsSL \\
  -H "User-Agent: sing-box/1.11.0 (AngelaBox Core)" \\
  --connect-timeout 10 \\
  --max-time 30 \\
  "\$SUB_URL" -o "\$TEMP_DOWNLOAD"; then
  echo "[-] 拉取失败，网络不可达或超时" >&2
  exit 1
fi

# Pre-flight check before touching live config
echo "[+] 正在进行原子配置语法检查..."
if /usr/local/bin/sing-box check -c "\$TEMP_DOWNLOAD"; then
  echo "[+] 校验成功，正在备份旧配置并热更新..."
  [[ -f "\$CONFIG_TARGET" ]] && cp -f "\$CONFIG_TARGET" "\$BACKUP_CONFIG"
  mv -f "\$TEMP_DOWNLOAD" "\$CONFIG_TARGET"
  chmod 600 "\$CONFIG_TARGET"
  
  if systemctl is-active --quiet sing-box; then
    echo "[+] 重载运行中 systemd 服务..."
    systemctl reload sing-box || systemctl restart sing-box
  fi
  echo "[✔] AngelaBox 配置热更新完成！"
else
  echo "[!] 新拉取的配置校验失败，已放弃替换，保障当前节点连接正常。" >&2
  rm -f "\$TEMP_DOWNLOAD"
  exit 1
fi
`,
};

// Comprehensive Script & Configuration Audit Engine
export function auditScript(content: string, type: 'bash' | 'powershell' | 'systemd' | 'config' = 'bash'): ScriptAuditReport {
  const findings: AuditFinding[] = [];
  const lines = content.split('\n');

  let score = 100;

  if (type === 'bash') {
    // 1. Strict mode check
    if (!content.includes('set -e') && !content.includes('set -euo pipefail')) {
      findings.push({
        id: 'bash_no_strict',
        level: 'warning',
        title: '缺少 Bash 严格错误处理 (set -euo pipefail)',
        description: '脚本未启用严格模式。遇到管道错误或未声明变量时可能会继续盲目执行，引发异常。',
        recommendation: '在脚本开头第2行添加 `set -euo pipefail`。',
        category: 'security',
        line: 1,
      });
      score -= 10;
    }

    // 2. Privilege root check
    if (!content.includes('EUID') && !content.includes('whoami')) {
      findings.push({
        id: 'bash_no_root_check',
        level: 'info',
        title: '缺少 Root 权限自检逻辑',
        description: 'sing-box TUN 虚拟网卡模式需要 CAP_NET_ADMIN 权限，非 root 运行可能静默失败。',
        recommendation: '增加 `if [[ $EUID -ne 0 ]]; then exit 1; fi` 明确提示用户。',
        category: 'privilege',
      });
      score -= 5;
    }

    // 3. Insecure curl
    lines.forEach((l, i) => {
      if (/curl\s+.*(-k|--insecure)/i.test(l)) {
        findings.push({
          id: `insecure_curl_${i}`,
          level: 'critical',
          title: '禁用 TLS 证书有效性校验 (--insecure)',
          description: `第 ${i + 1} 行检测到 curl -k 或 --insecure，容易遭遇中间人拦截篡改节点订阅。`,
          recommendation: '删除 -k 参数，确保依赖系统根证书有效性。',
          category: 'security',
          line: i + 1,
        });
        score -= 25;
      }

      if (/chmod\s+(?:-R\s+)?777/i.test(l)) {
        findings.push({
          id: `chmod_777_${i}`,
          level: 'critical',
          title: '配置文件过度放宽权限 (chmod 777)',
          description: `第 ${i + 1} 行将权限设为 777，系统上任何普通用户进程都可读取节点密码与密钥。`,
          recommendation: '配置文件建议严格保持为 chmod 600。',
          category: 'security',
          line: i + 1,
        });
        score -= 20;
      }
    });

    // 4. Pre-flight check
    if (!content.includes('sing-box check')) {
      findings.push({
        id: 'bash_no_preflight',
        level: 'warning',
        title: '缺少 sing-box check 配置预检',
        description: '在启动或更新配置前未运行 preflight check，配置格式错误将导致服务宕机。',
        recommendation: '在执行 sing-box run 前先调用 sing-box check -c ... 进行语法预检。',
        category: 'singbox_syntax',
      });
      score -= 15;
    }
  } else if (type === 'systemd') {
    if (!content.includes('CapabilityBoundingSet') && !content.includes('AmbientCapabilities')) {
      findings.push({
        id: 'systemd_caps_missing',
        level: 'warning',
        title: '未限制 Linux Capabilities 权限集',
        description: '以全权限 root 身份运行可能超出所需最小特权。',
        recommendation: '添加 `AmbientCapabilities=CAP_NET_ADMIN CAP_NET_BIND_SERVICE` 精细化收敛权限。',
        category: 'privilege',
      });
      score -= 15;
    }

    if (!content.includes('ProtectSystem=strict')) {
      findings.push({
        id: 'systemd_protect_system',
        level: 'info',
        title: '缺少 ProtectSystem=strict 文件系统只读沙箱',
        description: '建议配置 Systemd 沙箱隔绝，防止二进制被意外篡改。',
        recommendation: '在 [Service] 段添加 `ProtectSystem=strict` 与 `ProtectHome=true`。',
        category: 'security',
      });
      score -= 10;
    }

    if (!content.includes('LimitNOFILE')) {
      findings.push({
        id: 'systemd_nofile_missing',
        level: 'info',
        title: '未提升最大文件句柄限制 (LimitNOFILE)',
        description: '高并发连接代理在流量剧增时可能遭遇 1024 默认文件描述符瓶颈导致连接丢包。',
        recommendation: '添加 `LimitNOFILE=1048576`。',
        category: 'performance',
      });
      score -= 5;
    }
  } else if (type === 'config') {
    // Config JSON audit
    try {
      const cfg = JSON.parse(content);
      // Check DNS leak
      if (cfg.dns && (!cfg.dns.rules || cfg.dns.rules.length === 0)) {
        findings.push({
          id: 'config_dns_no_rules',
          level: 'critical',
          title: 'DNS 分流规则缺失，存在重大 DNS 泄露风险',
          description: '没有为国内直连与国外代理分别配置独立的 DNS 服务器与路由规则。',
          recommendation: '使用 AngelaBox 推荐的双轨 DNS 架构（国内 Alidns / 国外 1.1.1.1 + FakeIP）。',
          category: 'dns_leak',
        });
        score -= 30;
      }

      // Check strict_route
      const tunInbound = cfg.inbounds?.find((i: any) => i.type === 'tun');
      if (tunInbound && tunInbound.strict_route !== true) {
        findings.push({
          id: 'config_tun_strict_route',
          level: 'warning',
          title: 'TUN 模式未开启 strict_route 严格路由',
          description: '未启用 strict_route 可能导致某些局域网流量或非默认出口绕过代理产生泄露。',
          recommendation: '在 TUN inbound 中设置 `strict_route: true`。',
          category: 'security',
        });
        score -= 15;
      }

      // Check listen 0.0.0.0
      const mixedInbound = cfg.inbounds?.find((i: any) => i.type === 'mixed' || i.type === 'socks');
      if (mixedInbound && mixedInbound.listen === '0.0.0.0') {
        findings.push({
          id: 'config_listen_public',
          level: 'warning',
          title: '本地代理端口对公网 0.0.0.0 全网开放',
          description: 'mixed / socks 监听在 0.0.0.0，若无认证容易被局域网或公网其他人未授权扫描借道。',
          recommendation: '建议将 listen 改为 127.0.0.1，如需局域网共享请配合强密码认证。',
          category: 'security',
        });
        score -= 15;
      }
    } catch {
      findings.push({
        id: 'config_json_invalid',
        level: 'critical',
        title: 'JSON 格式解析失败',
        description: '内容不是有效的 JSON 数据。',
        recommendation: '检查逗号、括号配对是否合法。',
        category: 'singbox_syntax',
      });
      score -= 50;
    }
  }

  // All pass bonus
  if (findings.length === 0) {
    findings.push({
      id: 'all_pass',
      level: 'pass',
      title: '全面审计合规',
      description: '未检测到明显安全隐患、权限越权或性能瓶颈，配置符合生产级安全最佳实践。',
      recommendation: '保持定期更新 sing-box 内核及规则集即可。',
      category: 'security',
    });
  }

  return {
    score: Math.max(10, score),
    findings,
    scriptType: type,
    auditedLines: lines.length,
    timestamp: new Date().toLocaleTimeString(),
  };
}
