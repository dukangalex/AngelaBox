import express from 'express';
import { createServer as createViteServer } from 'vite';
import path from 'path';
import { fileURLToPath } from 'url';
import dotenv from 'dotenv';
import { lookup } from 'node:dns/promises';
import { request as httpRequest, type IncomingMessage } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { isIP, type LookupFunction } from 'node:net';

dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = parseInt(process.env.PORT || '3000', 10);
const HOST = process.env.HOST || '127.0.0.1';

/** Hard cap on a fetched subscription body; real configs are far smaller. */
const MAX_SUBSCRIPTION_BYTES = 10 * 1024 * 1024;
/** Per-hop budget for response headers to arrive. */
const FETCH_HEADER_TIMEOUT_MS = 15000;
/** Overall budget for a fetch, body streaming included; never reset early. */
const FETCH_TOTAL_TIMEOUT_MS = 30000;

function blockedIp(ip: string): boolean {
  const raw = ip.toLowerCase().replace(/^\[|\]$/g, '');
  if (raw.startsWith('::ffff:')) return blockedIp(raw.slice(7));
  if (raw.includes(':')) {
    return raw === '::1' || raw === '::' || raw.startsWith('fe80:') || raw.startsWith('fc') || raw.startsWith('fd');
  }
  const parts = raw.split('.').map((n) => Number(n));
  if (parts.length !== 4 || parts.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return true;
  const [a, b] = parts;
  return a === 0 || a === 10 || a === 127 || a === 255 ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 100 && b >= 64 && b <= 127);
}

interface PinnedTarget {
  url: URL;
  addresses: Array<{ address: string; family: number }>;
}

/** Public http(s) only. Rejects loopback, LAN, link-local, and cloud metadata. */
async function assertPublicHttpUrl(raw: string): Promise<PinnedTarget> {
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    throw new Error('URL is not valid');
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    throw new Error('only http and https URLs are allowed');
  }
  if (parsed.username || parsed.password) throw new Error('URL credentials are not allowed');
  const host = parsed.hostname;
  if (!host || host === 'localhost' || host.endsWith('.local') || host.endsWith('.internal')) {
    throw new Error('that host is not allowed');
  }
  const bareHost = host.replace(/^\[|\]$/g, '');
  const addresses = isIP(bareHost)
    ? [{ address: bareHost, family: isIP(bareHost) }]
    : await lookup(host, { all: true });
  if (addresses.length === 0 || addresses.some((item) => blockedIp(item.address))) {
    throw new Error('that address is not allowed');
  }
  return { url: parsed, addresses };
}

/**
 * DNS lookup that only ever yields the addresses assertPublicHttpUrl just
 * validated, so the connection cannot be rebound between check and connect:
 * the validated resolution IS the connection's resolution, and no second
 * lookup ever happens. TLS still authenticates the original hostname.
 */
function pinnedLookup(addresses: PinnedTarget['addresses']): LookupFunction {
  return (_hostname, options, callback) => {
    if (options.all) {
      callback(null, addresses.map((item) => ({ ...item })));
      return;
    }
    const chosen = addresses.find((item) => !options.family || item.family === options.family) ?? addresses[0];
    callback(null, chosen.address, chosen.family);
  };
}

/**
 * GET pinned to the target's validated addresses. The header timeout stops
 * applying once headers arrive; totalSignal bounds the whole exchange,
 * body streaming included, until the response closes.
 */
function pinnedGet(target: PinnedTarget, userAgent: string, totalSignal: AbortSignal): Promise<IncomingMessage> {
  const { url, addresses } = target;
  const isHttps = url.protocol === 'https:';
  return new Promise((resolve, reject) => {
    const hop = new AbortController();
    const onTotalAbort = () => hop.abort(totalSignal.reason);
    if (totalSignal.aborted) {
      hop.abort(totalSignal.reason);
    } else {
      totalSignal.addEventListener('abort', onTotalAbort, { once: true });
    }
    const headerTimer = setTimeout(
      () => hop.abort(new Error('subscription fetch timed out')),
      FETCH_HEADER_TIMEOUT_MS,
    );
    const cleanup = () => {
      clearTimeout(headerTimer);
      totalSignal.removeEventListener('abort', onTotalAbort);
    };
    const req = (isHttps ? httpsRequest : httpRequest)(
      {
        hostname: url.hostname,
        port: url.port ? Number(url.port) : isHttps ? 443 : 80,
        path: `${url.pathname}${url.search}`,
        method: 'GET',
        headers: {
          'User-Agent': userAgent,
          'Accept': '*/*',
          'Accept-Encoding': 'identity',
        },
        lookup: pinnedLookup(addresses),
        servername: isHttps ? url.hostname.replace(/^\[|\]$/g, '') : undefined,
        signal: hop.signal,
      },
      (response) => {
        clearTimeout(headerTimer);
        response.once('close', cleanup);
        resolve(response);
      },
    );
    req.on('error', (err) => {
      cleanup();
      reject(err);
    });
    req.end();
  });
}

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
    const total = new AbortController();
    const totalTimer = setTimeout(
      () => total.abort(new Error('subscription fetch timed out')),
      FETCH_TOTAL_TIMEOUT_MS,
    );
    try {
      let current = await assertPublicHttpUrl(url);
      let response: IncomingMessage | null = null;
      for (let hop = 0; hop < 3; hop++) {
        response = await pinnedGet(current, selectedUa, total.signal);
        if (response.statusCode === undefined || response.statusCode < 300 || response.statusCode >= 400) break;
        const location = response.headers.location;
        if (!location) break;
        response.destroy();
        current = await assertPublicHttpUrl(new URL(location, current.url).toString());
        response = null;
      }
      if (!response) {
        return res.status(502).json({ error: 'too many redirects' });
      }

      if (response.statusCode === undefined || response.statusCode < 200 || response.statusCode >= 300) {
        const status = response.statusCode ?? 502;
        const statusText = response.statusMessage;
        response.destroy();
        return res.status(status).json({
          error: `Remote subscription responded with HTTP status ${status} ${statusText}`,
        });
      }

      const chunks: Buffer[] = [];
      let received = 0;
      for await (const chunk of response) {
        const buffer = chunk as Buffer;
        received += buffer.length;
        if (received > MAX_SUBSCRIPTION_BYTES) {
          response.destroy();
          throw new Error(`subscription response exceeds ${MAX_SUBSCRIPTION_BYTES} bytes`);
        }
        chunks.push(buffer);
      }
      const text = Buffer.concat(chunks).toString('utf8');
      const userInfoRaw = response.headers['subscription-userinfo'];
      const userInfoHeader = Array.isArray(userInfoRaw) ? userInfoRaw[0] : userInfoRaw || '';
    
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

      const contentType = response.headers['content-type'] || '';
      const contentDisposition = response.headers['content-disposition'] || '';

      return res.json({
        success: true,
        data: text,
        userInfo,
        contentType,
        contentDisposition,
        size: text.length,
      });
    } finally {
      clearTimeout(totalTimer);
    }
  } catch (err: unknown) {
    const errorMsg = err instanceof Error ? err.message : String(err);
    const denied = /not allowed|not valid|only http|credentials/.test(errorMsg);
    return res.status(denied ? 400 : 500).json({
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

  app.listen(PORT, HOST, () => {
    console.log(`[AngelaBox] Server active at http://${HOST}:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error('[AngelaBox] Failed to start server:', err);
  process.exit(1);
});
