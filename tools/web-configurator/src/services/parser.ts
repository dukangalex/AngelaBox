import { load } from 'js-yaml';
import { ProxyNode, ProxyType } from '../types/proxy';

// Detect country flag from tag name
export function detectFlag(tag: string): string {
  const lower = tag.toLowerCase();
  if (lower.includes('香港') || lower.includes('hk') || lower.includes('hong kong') || lower.includes('hongkong')) return '🇭🇰';
  if (lower.includes('日本') || lower.includes('jp') || lower.includes('japan') || lower.includes('tokyo') || lower.includes('osaka')) return '🇯🇵';
  if (lower.includes('新加坡') || lower.includes('sg') || lower.includes('singapore') || lower.includes('狮城')) return '🇸🇬';
  if (lower.includes('美国') || lower.includes('us') || lower.includes('united states') || lower.includes('america')) return '🇺🇸';
  if (lower.includes('台湾') || lower.includes('tw') || lower.includes('taiwan') || lower.includes('台北')) return '🇹🇼';
  if (lower.includes('韩国') || lower.includes('kr') || lower.includes('korea') || lower.includes('首尔')) return '🇰🇷';
  if (lower.includes('英国') || lower.includes('uk') || lower.includes('gb') || lower.includes('london')) return '🇬🇧';
  if (lower.includes('德国') || lower.includes('de') || lower.includes('germany') || lower.includes('frankfurt')) return '🇩🇪';
  if (lower.includes('法国') || lower.includes('fr') || lower.includes('france') || lower.includes('paris')) return '🇫🇷';
  if (lower.includes('加拿大') || lower.includes('ca') || lower.includes('canada')) return '🇨🇦';
  if (lower.includes('澳大利亚') || lower.includes('au') || lower.includes('australia') || lower.includes('sydney')) return '🇦🇺';
  if (lower.includes('荷兰') || lower.includes('nl') || lower.includes('netherlands')) return '🇳🇱';
  if (lower.includes('俄罗斯') || lower.includes('ru') || lower.includes('russia')) return '🇷🇺';
  if (lower.includes('土耳其') || lower.includes('tr') || lower.includes('turkey')) return '🇹🇷';
  if (lower.includes('阿根廷') || lower.includes('ar') || lower.includes('argentina')) return '🇦🇷';
  if (lower.includes('印度') || lower.includes('in') || lower.includes('india')) return '🇮🇳';
  if (lower.includes('马来西亚') || lower.includes('my') || lower.includes('malaysia')) return '🇲🇾';
  return '🌐';
}

// Safe Base64 decode supporting utf-8 and url-safe characters
export function safeBase64Decode(str: string): string {
  try {
    let clean = str.trim().replace(/-/g, '+').replace(/_/g, '/');
    while (clean.length % 4 !== 0) {
      clean += '=';
    }
    const binary = atob(clean);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return new TextDecoder('utf-8').decode(bytes);
  } catch {
    try {
      return atob(str.trim());
    } catch {
      return '';
    }
  }
}

// Generate unique ID
function genId(): string {
  return 'node_' + Math.random().toString(36).substring(2, 11) + '_' + Date.now().toString(36);
}

// Parse single VLESS URL
export function parseVlessUri(uri: string, source = 'Direct'): ProxyNode | null {
  try {
    // Format: vless://uuid@host:port?params#tag
    const url = new URL(uri);
    const uuid = url.username;
    const server = url.hostname;
    const server_port = parseInt(url.port, 10) || 443;
    const tag = decodeURIComponent(url.hash ? url.hash.replace(/^#/, '') : `${server}:${server_port}`);
    const params = url.searchParams;

    const security = params.get('security') || 'none';
    const type = params.get('type') || 'tcp';
    const flow = params.get('flow') || undefined;

    const node: ProxyNode = {
      id: genId(),
      tag,
      type: 'vless',
      server,
      server_port,
      uuid,
      flow,
      source,
      sourceType: 'snippet',
      flag: detectFlag(tag),
    };

    // TLS / Reality
    if (security === 'tls' || security === 'reality') {
      node.tls = {
        enabled: true,
        server_name: params.get('sni') || undefined,
        insecure: params.get('allowInsecure') === '1' || params.get('insecure') === '1',
        alpn: params.get('alpn') ? params.get('alpn')!.split(',') : undefined,
      };
      if (security === 'reality') {
        node.tls.reality = {
          enabled: true,
          public_key: params.get('pbk') || '',
          short_id: params.get('sid') || undefined,
        };
      }
    }

    // Transport
    if (type === 'ws') {
      node.transport = {
        type: 'ws',
        path: params.get('path') || '/',
        headers: params.get('host') ? { Host: params.get('host')! } : undefined,
      };
    } else if (type === 'grpc') {
      node.transport = {
        type: 'grpc',
        service_name: params.get('serviceName') || params.get('service_name') || '',
      };
    } else if (type === 'http' || type === 'h2') {
      node.transport = {
        type: 'http',
        path: params.get('path') || '/',
        headers: params.get('host') ? { Host: params.get('host')! } : undefined,
      };
    } else if (type === 'httpupgrade') {
      node.transport = {
        type: 'httpupgrade',
        path: params.get('path') || '/',
        headers: params.get('host') ? { Host: params.get('host')! } : undefined,
      };
    }

    return node;
  } catch (err) {
    return null;
  }
}

// Parse single VMess URL
export function parseVmessUri(uri: string, source = 'Direct'): ProxyNode | null {
  try {
    const raw = uri.replace(/^vmess:\/\//i, '');
    const decoded = safeBase64Decode(raw);
    if (!decoded) return null;
    const v = JSON.parse(decoded);

    const tag = v.ps || `${v.add}:${v.port}`;
    const node: ProxyNode = {
      id: genId(),
      tag,
      type: 'vmess',
      server: v.add,
      server_port: parseInt(v.port, 10),
      uuid: v.id,
      alter_id: parseInt(v.aid, 10) || 0,
      security: v.scy || 'auto',
      source,
      sourceType: 'snippet',
      flag: detectFlag(tag),
    };

    if (v.tls === 'tls' || v.tls === 1 || v.tls === '1') {
      node.tls = {
        enabled: true,
        server_name: v.sni || v.host || undefined,
        alpn: v.alpn ? v.alpn.split(',') : undefined,
      };
    }

    const net = (v.net || 'tcp').toLowerCase();
    if (net === 'ws') {
      node.transport = {
        type: 'ws',
        path: v.path || '/',
        headers: v.host ? { Host: v.host } : undefined,
      };
    } else if (net === 'grpc') {
      node.transport = {
        type: 'grpc',
        service_name: v.path || '',
      };
    } else if (net === 'h2' || net === 'http') {
      node.transport = {
        type: 'http',
        path: v.path || '/',
        headers: v.host ? { Host: v.host } : undefined,
      };
    }

    return node;
  } catch {
    return null;
  }
}

// Parse Shadowsocks (ss://)
export function parseShadowsocksUri(uri: string, source = 'Direct'): ProxyNode | null {
  try {
    const raw = uri.replace(/^ss:\/\//i, '');
    let tag = '';
    let main = raw;
    const hashIndex = raw.indexOf('#');
    if (hashIndex !== -1) {
      tag = decodeURIComponent(raw.substring(hashIndex + 1));
      main = raw.substring(0, hashIndex);
    }

    let method = '';
    let password = '';
    let server = '';
    let server_port = 8388;
    let plugin = '';
    let plugin_opts = '';

    // Check SIP002 format: ss://base64(method:password@host:port)?plugin=...
    const atIndex = main.indexOf('@');
    if (atIndex !== -1) {
      // Format: ss://method:password@host:port or ss://base64(method:pass)@host:port
      const userInfoPart = main.substring(0, atIndex);
      const hostPart = main.substring(atIndex + 1);

      // Decoded userInfo or plain
      const decodedUser = safeBase64Decode(userInfoPart);
      const userStr = decodedUser.includes(':') ? decodedUser : userInfoPart;
      const colon = userStr.indexOf(':');
      if (colon !== -1) {
        method = userStr.substring(0, colon);
        password = userStr.substring(colon + 1);
      }

      // Check plugin params in hostPart
      const qIdx = hostPart.indexOf('?');
      let hostPortStr = hostPart;
      if (qIdx !== -1) {
        hostPortStr = hostPart.substring(0, qIdx);
        const queryParams = new URLSearchParams(hostPart.substring(qIdx + 1));
        const pluginStr = queryParams.get('plugin');
        if (pluginStr) {
          const semi = pluginStr.indexOf(';');
          if (semi !== -1) {
            plugin = pluginStr.substring(0, semi);
            plugin_opts = pluginStr.substring(semi + 1);
          } else {
            plugin = pluginStr;
          }
        }
      }

      const hpColon = hostPortStr.lastIndexOf(':');
      server = hostPortStr.substring(0, hpColon);
      server_port = parseInt(hostPortStr.substring(hpColon + 1), 10);
    } else {
      // Whole main string might be base64
      const decoded = safeBase64Decode(main);
      if (decoded && decoded.includes('@')) {
        return parseShadowsocksUri(`ss://${decoded}${tag ? '#' + encodeURIComponent(tag) : ''}`, source);
      }
    }

    if (!tag) tag = `${server}:${server_port}`;

    const node: ProxyNode = {
      id: genId(),
      tag,
      type: 'shadowsocks',
      server,
      server_port,
      method: method || 'aes-256-gcm',
      password,
      plugin: plugin || undefined,
      plugin_opts: plugin_opts || undefined,
      source,
      sourceType: 'snippet',
      flag: detectFlag(tag),
    };

    return node;
  } catch {
    return null;
  }
}

// Parse Trojan (trojan://)
export function parseTrojanUri(uri: string, source = 'Direct'): ProxyNode | null {
  try {
    const url = new URL(uri);
    const password = url.username;
    const server = url.hostname;
    const server_port = parseInt(url.port, 10) || 443;
    const tag = decodeURIComponent(url.hash ? url.hash.replace(/^#/, '') : `${server}:${server_port}`);
    const params = url.searchParams;

    const node: ProxyNode = {
      id: genId(),
      tag,
      type: 'trojan',
      server,
      server_port,
      password,
      source,
      sourceType: 'snippet',
      flag: detectFlag(tag),
      tls: {
        enabled: true,
        server_name: params.get('sni') || server,
        insecure: params.get('allowInsecure') === '1' || params.get('insecure') === '1',
        alpn: params.get('alpn') ? params.get('alpn')!.split(',') : undefined,
      },
    };

    const type = params.get('type');
    if (type === 'ws') {
      node.transport = {
        type: 'ws',
        path: params.get('path') || '/',
        headers: params.get('host') ? { Host: params.get('host')! } : undefined,
      };
    } else if (type === 'grpc') {
      node.transport = {
        type: 'grpc',
        service_name: params.get('serviceName') || '',
      };
    }

    return node;
  } catch {
    return null;
  }
}

// Parse Hysteria 2 (hy2:// or hysteria2://)
export function parseHysteria2Uri(uri: string, source = 'Direct'): ProxyNode | null {
  try {
    const normalized = uri.replace(/^hy2:\/\//i, 'hysteria2://');
    const url = new URL(normalized);
    const password = url.username;
    const server = url.hostname;
    const server_port = parseInt(url.port, 10) || 443;
    const tag = decodeURIComponent(url.hash ? url.hash.replace(/^#/, '') : `${server}:${server_port}`);
    const params = url.searchParams;

    const node: ProxyNode = {
      id: genId(),
      tag,
      type: 'hysteria2',
      server,
      server_port,
      password,
      source,
      sourceType: 'snippet',
      flag: detectFlag(tag),
      tls: {
        enabled: true,
        server_name: params.get('sni') || server,
        insecure: params.get('insecure') === '1',
        alpn: params.get('alpn') ? params.get('alpn')!.split(',') : undefined,
      },
    };

    const obfs = params.get('obfs');
    const obfsPassword = params.get('obfs-password');
    if (obfs || obfsPassword) {
      node.obfs = {
        type: obfs || 'salamander',
        password: obfsPassword || undefined,
      };
    }

    if (params.get('upmbps')) node.up_mbps = parseInt(params.get('upmbps')!, 10);
    if (params.get('downmbps')) node.down_mbps = parseInt(params.get('downmbps')!, 10);

    return node;
  } catch {
    return null;
  }
}

// Parse TUIC (tuic://)
export function parseTuicUri(uri: string, source = 'Direct'): ProxyNode | null {
  try {
    const url = new URL(uri);
    const uuid = url.username;
    const password = url.password;
    const server = url.hostname;
    const server_port = parseInt(url.port, 10) || 443;
    const tag = decodeURIComponent(url.hash ? url.hash.replace(/^#/, '') : `${server}:${server_port}`);
    const params = url.searchParams;

    const node: ProxyNode = {
      id: genId(),
      tag,
      type: 'tuic',
      server,
      server_port,
      uuid,
      password,
      congestion_control: (params.get('congestion_control') as any) || 'bbr',
      source,
      sourceType: 'snippet',
      flag: detectFlag(tag),
      tls: {
        enabled: true,
        server_name: params.get('sni') || server,
        insecure: params.get('allow_insecure') === '1' || params.get('insecure') === '1',
        alpn: params.get('alpn') ? params.get('alpn')!.split(',') : ['h3'],
      },
    };

    return node;
  } catch {
    return null;
  }
}

// Parse SOCKS or HTTP
export function parseSocksOrHttpUri(uri: string, type: 'socks' | 'http', source = 'Direct'): ProxyNode | null {
  try {
    const url = new URL(uri);
    const server = url.hostname;
    const server_port = parseInt(url.port, 10) || (type === 'socks' ? 1080 : 80);
    const tag = decodeURIComponent(url.hash ? url.hash.replace(/^#/, '') : `${server}:${server_port}`);

    return {
      id: genId(),
      tag,
      type,
      server,
      server_port,
      uuid: url.username || undefined,
      password: url.password || undefined,
      source,
      sourceType: 'snippet',
      flag: detectFlag(tag),
    };
  } catch {
    return null;
  }
}

// Convert a single Mihomo / Clash Proxy object to Sing-box ProxyNode
export function convertClashProxyToNode(proxy: any, source = 'Clash'): ProxyNode | null {
  if (!proxy || typeof proxy !== 'object' || !proxy.name) return null;

  const tag = String(proxy.name);
  const typeStr = String(proxy.type || '').toLowerCase();
  const server = String(proxy.server || '');
  const server_port = parseInt(proxy.port, 10) || 443;

  let type: ProxyType = 'vless';
  const node: ProxyNode = {
    id: genId(),
    tag,
    type: 'vless',
    server,
    server_port,
    source,
    sourceType: 'file',
    flag: detectFlag(tag),
    rawConfig: proxy,
  };

  switch (typeStr) {
    case 'ss':
    case 'shadowsocks': {
      node.type = 'shadowsocks';
      node.method = proxy.cipher;
      node.password = proxy.password;
      if (proxy.plugin) {
        node.plugin = proxy.plugin;
        node.plugin_opts = proxy['plugin-opts'] ? JSON.stringify(proxy['plugin-opts']) : undefined;
      }
      break;
    }
    case 'vmess': {
      node.type = 'vmess';
      node.uuid = proxy.uuid;
      node.alter_id = parseInt(proxy.alterId, 10) || 0;
      node.security = proxy.cipher || 'auto';
      if (proxy.tls) {
        node.tls = {
          enabled: true,
          server_name: proxy.servername || proxy.sni,
          insecure: proxy['skip-cert-verify'] === true,
          alpn: proxy.alpn,
        };
      }
      const net = (proxy.network || 'tcp').toLowerCase();
      if (net === 'ws') {
        node.transport = {
          type: 'ws',
          path: proxy['ws-opts']?.path || proxy['ws-path'] || '/',
          headers: proxy['ws-opts']?.headers || proxy['ws-headers'],
        };
      } else if (net === 'grpc') {
        node.transport = {
          type: 'grpc',
          service_name: proxy['grpc-opts']?.['grpc-service-name'] || '',
        };
      }
      break;
    }
    case 'vless': {
      node.type = 'vless';
      node.uuid = proxy.uuid;
      node.flow = proxy.flow;
      if (proxy.tls || proxy.reality) {
        node.tls = {
          enabled: true,
          server_name: proxy.servername || proxy.sni,
          insecure: proxy['skip-cert-verify'] === true,
          alpn: proxy.alpn,
        };
        if (proxy.reality || proxy['reality-opts']) {
          const ro = proxy['reality-opts'] || proxy.reality;
          node.tls.reality = {
            enabled: true,
            public_key: ro['public-key'] || ro.publicKey || '',
            short_id: ro['short-id'] || ro.shortId || '',
          };
        }
      }
      const net = (proxy.network || 'tcp').toLowerCase();
      if (net === 'ws') {
        node.transport = {
          type: 'ws',
          path: proxy['ws-opts']?.path || '/',
          headers: proxy['ws-opts']?.headers,
        };
      } else if (net === 'grpc') {
        node.transport = {
          type: 'grpc',
          service_name: proxy['grpc-opts']?.['grpc-service-name'] || '',
        };
      }
      break;
    }
    case 'trojan': {
      node.type = 'trojan';
      node.password = proxy.password;
      node.tls = {
        enabled: true,
        server_name: proxy.sni || proxy.servername || server,
        insecure: proxy['skip-cert-verify'] === true,
        alpn: proxy.alpn,
      };
      const net = (proxy.network || 'tcp').toLowerCase();
      if (net === 'ws') {
        node.transport = {
          type: 'ws',
          path: proxy['ws-opts']?.path || '/',
          headers: proxy['ws-opts']?.headers,
        };
      } else if (net === 'grpc') {
        node.transport = {
          type: 'grpc',
          service_name: proxy['grpc-opts']?.['grpc-service-name'] || '',
        };
      }
      break;
    }
    case 'hysteria2':
    case 'hy2': {
      node.type = 'hysteria2';
      node.password = proxy.password;
      node.tls = {
        enabled: true,
        server_name: proxy.sni || server,
        insecure: proxy['skip-cert-verify'] === true,
        alpn: proxy.alpn,
      };
      if (proxy.obfs || proxy['obfs-password']) {
        node.obfs = {
          type: proxy.obfs || 'salamander',
          password: proxy['obfs-password'],
        };
      }
      if (proxy.up) node.up_mbps = parseInt(String(proxy.up).replace(/[^0-9]/g, ''), 10);
      if (proxy.down) node.down_mbps = parseInt(String(proxy.down).replace(/[^0-9]/g, ''), 10);
      break;
    }
    case 'tuic': {
      node.type = 'tuic';
      node.uuid = proxy.uuid;
      node.password = proxy.password;
      node.congestion_control = proxy['congestion-controller'] || 'bbr';
      node.tls = {
        enabled: true,
        server_name: proxy.sni || server,
        insecure: proxy['skip-cert-verify'] === true,
        alpn: proxy.alpn || ['h3'],
      };
      break;
    }
    case 'wireguard': {
      node.type = 'wireguard';
      node.private_key = proxy['private-key'];
      node.peer_public_key = proxy['public-key'];
      node.pre_shared_key = proxy['preshared-key'];
      node.local_address = proxy.ip ? [proxy.ip] : proxy.ips;
      node.reserved = proxy.reserved;
      node.mtu = proxy.mtu;
      break;
    }
    case 'socks5':
    case 'socks': {
      node.type = 'socks';
      node.uuid = proxy.username;
      node.password = proxy.password;
      break;
    }
    case 'http':
    case 'https': {
      node.type = 'http';
      node.uuid = proxy.username;
      node.password = proxy.password;
      if (proxy.tls) {
        node.tls = { enabled: true, server_name: proxy.sni || server };
      }
      break;
    }
    default:
      return null;
  }

  return node;
}

// Convert Sing-box Outbound Object to ProxyNode
export function convertSingboxOutboundToNode(out: any, source = 'Sing-box'): ProxyNode | null {
  if (!out || typeof out !== 'object') return null;
  // Ignore selector, urltest, direct, block, dns outbounds
  const ignoredTypes = ['selector', 'urltest', 'direct', 'block', 'dns', 'socks', 'http'];
  if (ignoredTypes.includes(out.type) && (!out.server || !out.server_port)) return null;

  const tag = out.tag || `${out.server}:${out.server_port}`;
  const node: ProxyNode = {
    id: genId(),
    tag,
    type: out.type,
    server: out.server || '',
    server_port: out.server_port || 443,
    uuid: out.uuid,
    password: out.password,
    security: out.security,
    method: out.method,
    flow: out.flow,
    congestion_control: out.congestion_control,
    up_mbps: out.up_mbps,
    down_mbps: out.down_mbps,
    source,
    sourceType: 'file',
    flag: detectFlag(tag),
    rawConfig: out,
  };

  if (out.tls) {
    node.tls = {
      enabled: out.tls.enabled !== false,
      server_name: out.tls.server_name,
      insecure: out.tls.insecure,
      alpn: out.tls.alpn,
    };
    if (out.tls.reality) {
      node.tls.reality = {
        enabled: out.tls.reality.enabled !== false,
        public_key: out.tls.reality.public_key,
        short_id: out.tls.reality.short_id,
      };
    }
  }

  if (out.transport) {
    node.transport = {
      type: out.transport.type,
      path: out.transport.path,
      headers: out.transport.headers,
      service_name: out.transport.service_name,
    };
  }

  if (out.obfs) {
    node.obfs = {
      type: out.obfs.type,
      password: out.obfs.password,
    };
  }

  return node;
}

// Master Universal Parser: Takes raw string, file content, or subscription payload
export function parseAnyContent(content: string, sourceName = 'Imported'): ProxyNode[] {
  if (!content || typeof content !== 'string') return [];
  const trimmed = content.trim();
  const nodes: ProxyNode[] = [];

  // 1. Try parsing as JSON (Sing-box config / V2Ray / Singbox Outbounds)
  if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
    try {
      const parsed = JSON.parse(trimmed);
      // Sing-box full config with outbounds
      if (parsed.outbounds && Array.isArray(parsed.outbounds)) {
        for (const out of parsed.outbounds) {
          const n = convertSingboxOutboundToNode(out, sourceName);
          if (n) nodes.push(n);
        }
        if (nodes.length > 0) return nodes;
      }
      // Outbounds array directly
      if (Array.isArray(parsed)) {
        for (const item of parsed) {
          const n = convertSingboxOutboundToNode(item, sourceName);
          if (n) nodes.push(n);
        }
        if (nodes.length > 0) return nodes;
      }
      // Single outbound object
      if (parsed.type && parsed.server) {
        const n = convertSingboxOutboundToNode(parsed, sourceName);
        if (n) return [n];
      }
    } catch {
      // Not JSON, continue
    }
  }

  // 2. Try parsing as YAML (Mihomo / Clash)
  try {
    const yamlObj = load(trimmed) as any;
    if (yamlObj && typeof yamlObj === 'object') {
      if (yamlObj.proxies && Array.isArray(yamlObj.proxies)) {
        for (const p of yamlObj.proxies) {
          const n = convertClashProxyToNode(p, sourceName);
          if (n) nodes.push(n);
        }
        if (nodes.length > 0) return nodes;
      }
    }
  } catch {
    // Not YAML, continue
  }

  // 3. Try checking if it's Base64 encoded subscription (single continuous string)
  if (!trimmed.includes('\n') && !trimmed.startsWith('vless://') && !trimmed.startsWith('vmess://') && !trimmed.startsWith('ss://')) {
    const decoded = safeBase64Decode(trimmed);
    if (decoded && (decoded.includes('://') || decoded.includes('proxies:') || decoded.includes('outbounds:'))) {
      return parseAnyContent(decoded, sourceName);
    }
  }

  // 4. Parse line by line (supports mixed lines of vless, vmess, ss, trojan, hy2, tuic, wireguard)
  const lines = trimmed.split(/[\r\n]+/);
  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#') || line.startsWith('//')) continue;

    if (line.startsWith('vless://')) {
      const n = parseVlessUri(line, sourceName);
      if (n) nodes.push(n);
    } else if (line.startsWith('vmess://')) {
      const n = parseVmessUri(line, sourceName);
      if (n) nodes.push(n);
    } else if (line.startsWith('ss://')) {
      const n = parseShadowsocksUri(line, sourceName);
      if (n) nodes.push(n);
    } else if (line.startsWith('trojan://')) {
      const n = parseTrojanUri(line, sourceName);
      if (n) nodes.push(n);
    } else if (line.startsWith('hy2://') || line.startsWith('hysteria2://')) {
      const n = parseHysteria2Uri(line, sourceName);
      if (n) nodes.push(n);
    } else if (line.startsWith('tuic://')) {
      const n = parseTuicUri(line, sourceName);
      if (n) nodes.push(n);
    } else if (line.startsWith('socks5://') || line.startsWith('socks://')) {
      const n = parseSocksOrHttpUri(line, 'socks', sourceName);
      if (n) nodes.push(n);
    } else if (line.startsWith('http://') || line.startsWith('https://')) {
      const n = parseSocksOrHttpUri(line, 'http', sourceName);
      if (n) nodes.push(n);
    }
  }

  return nodes;
}
