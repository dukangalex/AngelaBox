import express from 'express';
import { createServer as createViteServer } from 'vite';
import path from 'path';
import { fileURLToPath } from 'url';
import dotenv from 'dotenv';

dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = parseInt(process.env.PORT || '3000', 10);

app.use(express.json({ limit: '20mb' }));
app.use(express.urlencoded({ extended: true, limit: '20mb' }));

// API: Fetch remote subscription with simulated modern proxy user-agents & CORS bypass
app.post('/api/fetch-subscription', async (req, res) => {
  const { url, userAgentType = 'singbox' } = req.body;
  if (!url || typeof url !== 'string') {
    return res.status(400).json({ error: 'URL is required' });
  }

  const userAgents: Record<string, string> = {
    singbox: 'sing-box/1.11.0 (Linux; x86_64)',
    clash: 'ClashMeta/v1.18.8 (Mihomo)',
    v2ray: 'v2rayN/6.39',
    curl: 'curl/8.4.0',
  };

  const selectedUa = userAgents[userAgentType] || userAgents.singbox;

  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);

    const response = await fetch(url, {
      signal: controller.signal,
      headers: {
        'User-Agent': selectedUa,
        'Accept': '*/*',
        'Accept-Encoding': 'identity',
      },
      redirect: 'follow',
    });

    clearTimeout(timeout);

    if (!response.ok) {
      return res.status(response.status).json({
        error: `Remote subscription responded with HTTP status ${response.status} ${response.statusText}`,
      });
    }

    const text = await response.text();
    const userInfoHeader = response.headers.get('subscription-userinfo') || '';
    
    // Parse user info if available: upload=1024; download=2048; total=10737418240; expire=1735689600
    let userInfo: Record<string, number> | null = null;
    if (userInfoHeader) {
      userInfo = {};
      const parts = userInfoHeader.split(';');
      for (const part of parts) {
        const [k, v] = part.trim().split('=');
        if (k && v) {
          const num = parseInt(v, 10);
          if (!isNaN(num)) userInfo[k] = num;
        }
      }
    }

    const contentType = response.headers.get('content-type') || '';
    const contentDisposition = response.headers.get('content-disposition') || '';

    return res.json({
      success: true,
      data: text,
      userInfo,
      contentType,
      contentDisposition,
      size: text.length,
    });
  } catch (err: unknown) {
    const errorMsg = err instanceof Error ? err.message : String(err);
    return res.status(500).json({
      error: `Failed to fetch subscription: ${errorMsg}`,
    });
  }
});

// API: Script Audit Engine
app.post('/api/audit-script', (req, res) => {
  const { scriptContent, scriptType = 'shell' } = req.body;
  if (!scriptContent) {
    return res.status(400).json({ error: 'scriptContent is required' });
  }

  const findings: Array<{
    level: 'critical' | 'warning' | 'info';
    title: string;
    description: string;
    recommendation: string;
    line?: number;
  }> = [];

  const lines = scriptContent.split('\n');

  lines.forEach((line: string, idx: number) => {
    const lineNum = idx + 1;
    const trimmed = line.trim();

    // Check for hardcoded secrets or tokens
    if (/(?:api_key|secret|password|uuid|token)\s*=\s*['"][a-zA-Z0-9_-]{8,}['"]/i.test(trimmed)) {
      findings.push({
        level: 'warning',
        title: '硬编码敏感凭据',
        description: `在第 ${lineNum} 行检测到疑似硬编码的密码或密钥。`,
        recommendation: '建议改用环境变量或受限权限的专有凭据文件载入，避免直接裸写在脚本中。',
        line: lineNum,
      });
    }

    // Check for insecure curl without cert verification
    if (/curl.*(-k|--insecure)/i.test(trimmed)) {
      findings.push({
        level: 'critical',
        title: '禁用 TLS/SSL 证书校验',
        description: `在第 ${lineNum} 行发现 curl 使用了 -k/--insecure 参数，存在中间人攻击 (MITM) 劫持风险。`,
        recommendation: '移除 --insecure 参数，确保使用系统有效 CA 证书链校验。',
        line: lineNum,
      });
    }

    // Check for sudo chmod 777
    if (/chmod\s+(?:-R\s+)?777/i.test(trimmed)) {
      findings.push({
        level: 'critical',
        title: '过度宽松的文件权限 (chmod 777)',
        description: `第 ${lineNum} 行赋予了全系统全局可读写执行权限 777。`,
        recommendation: '配置目录使用 755，证书与密钥私有配置文件严格使用 600 或 640 属主权限。',
        line: lineNum,
      });
    }

    // Check for rm -rf without sanity check
    if (/rm\s+-rf\s+\/|rm\s+-rf\s+\$[a-zA-Z0-9_]+/i.test(trimmed)) {
      findings.push({
        level: 'warning',
        title: '潜在危险的递归删除指令',
        description: `第 ${lineNum} 行包含未设防的 rm -rf 变量引用，若变量未定义可能清空根目录或工作区。`,
        recommendation: '增加 `rm -rf "${TARGET_DIR:?error}"/*` 安全防护断言。',
        line: lineNum,
      });
    }

    // Check for iptables without restore backup
    if (/iptables\s+-F/i.test(trimmed)) {
      findings.push({
        level: 'warning',
        title: '裸清空 iptables 防火墙规则',
        description: `第 ${lineNum} 行发现 \`iptables -F\`，可能导致远程 SSH 瞬间断连失联。`,
        recommendation: '配置前备份现有规则并在前置开放 SSH 22 端口白名单。',
        line: lineNum,
      });
    }
  });

  // Calculate security score
  let score = 100;
  findings.forEach((f) => {
    if (f.level === 'critical') score -= 25;
    else if (f.level === 'warning') score -= 10;
    else score -= 5;
  });
  score = Math.max(10, score);

  return res.json({
    success: true,
    score,
    findings,
    auditedLines: lines.length,
    timestamp: new Date().toISOString(),
  });
});

// Vite middleware or static serving
async function startServer() {
  if (process.env.NODE_ENV === 'production') {
    app.use(express.static(path.resolve(__dirname, 'dist')));
    app.get('*', (_req, res) => {
      res.sendFile(path.resolve(__dirname, 'dist/index.html'));
    });
  } else {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`[AngelaBox] Server active at http://0.0.0.0:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error('[AngelaBox] Failed to start server:', err);
  process.exit(1);
});
