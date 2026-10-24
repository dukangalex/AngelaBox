import { dump } from 'js-yaml';
import { ConfigOptions, ProxyNode } from '../types/proxy';

// Convert single ProxyNode to compliant Sing-box outbound object
export function nodeToSingboxOutbound(node: ProxyNode): Record<string, any> {
  const base: Record<string, any> = {
    type: node.type,
    tag: node.tag,
    server: node.server,
    server_port: node.server_port,
  };

  switch (node.type) {
    case 'vless': {
      base.uuid = node.uuid;
      if (node.flow) base.flow = node.flow;
      if (node.tls?.enabled) {
        base.tls = {
          enabled: true,
          server_name: node.tls.server_name,
          insecure: node.tls.insecure || false,
          alpn: node.tls.alpn,
        };
        if (node.tls.reality?.enabled) {
          base.tls.reality = {
            enabled: true,
            public_key: node.tls.reality.public_key,
            short_id: node.tls.reality.short_id,
          };
        }
      }
      if (node.transport) {
        base.transport = {
          type: node.transport.type,
          path: node.transport.path,
          headers: node.transport.headers,
          service_name: node.transport.service_name,
        };
      }
      break;
    }

    case 'vmess': {
      base.uuid = node.uuid;
      base.security = node.security || 'auto';
      base.alter_id = node.alter_id || 0;
      if (node.tls?.enabled) {
        base.tls = {
          enabled: true,
          server_name: node.tls.server_name,
          insecure: node.tls.insecure || false,
          alpn: node.tls.alpn,
        };
      }
      if (node.transport) {
        base.transport = {
          type: node.transport.type,
          path: node.transport.path,
          headers: node.transport.headers,
          service_name: node.transport.service_name,
        };
      }
      break;
    }

    case 'shadowsocks': {
      base.method = node.method || 'aes-256-gcm';
      base.password = node.password;
      if (node.plugin) {
        base.plugin = node.plugin;
        base.plugin_opts = node.plugin_opts;
      }
      break;
    }

    case 'trojan': {
      base.password = node.password;
      base.tls = {
        enabled: true,
        server_name: node.tls?.server_name || node.server,
        insecure: node.tls?.insecure || false,
        alpn: node.tls?.alpn,
      };
      if (node.transport) {
        base.transport = {
          type: node.transport.type,
          path: node.transport.path,
          headers: node.transport.headers,
          service_name: node.transport.service_name,
        };
      }
      break;
    }

    case 'hysteria2': {
      base.password = node.password;
      base.tls = {
        enabled: true,
        server_name: node.tls?.server_name || node.server,
        insecure: node.tls?.insecure || false,
        alpn: node.tls?.alpn,
      };
      if (node.obfs?.type) {
        base.obfs = {
          type: node.obfs.type,
          password: node.obfs.password,
        };
      }
      if (node.up_mbps) base.up_mbps = node.up_mbps;
      if (node.down_mbps) base.down_mbps = node.down_mbps;
      break;
    }

    case 'tuic': {
      base.uuid = node.uuid;
      base.password = node.password;
      base.congestion_control = node.congestion_control || 'bbr';
      base.tls = {
        enabled: true,
        server_name: node.tls?.server_name || node.server,
        insecure: node.tls?.insecure || false,
        alpn: node.tls?.alpn || ['h3'],
      };
      break;
    }

    case 'wireguard': {
      base.private_key = node.private_key;
      base.peer_public_key = node.peer_public_key;
      base.pre_shared_key = node.pre_shared_key;
      base.local_address = node.local_address || ['10.0.0.2/32'];
      base.reserved = node.reserved;
      base.mtu = node.mtu || 1420;
      break;
    }

    case 'socks': {
      if (node.uuid) base.username = node.uuid;
      if (node.password) base.password = node.password;
      break;
    }

    case 'http': {
      if (node.uuid) base.username = node.uuid;
      if (node.password) base.password = node.password;
      if (node.tls?.enabled) {
        base.tls = {
          enabled: true,
          server_name: node.tls.server_name || node.server,
        };
      }
      break;
    }
  }

  return base;
}

// Convert ProxyNode to Shareable URI Link
export function nodeToUri(node: ProxyNode): string {
  const encTag = encodeURIComponent(node.tag);
  switch (node.type) {
    case 'vless': {
      let params = `type=${node.transport?.type || 'tcp'}`;
      if (node.tls?.reality?.enabled) {
        params += `&security=reality&pbk=${node.tls.reality.public_key || ''}`;
        if (node.tls.reality.short_id) params += `&sid=${node.tls.reality.short_id}`;
        if (node.tls.server_name) params += `&sni=${node.tls.server_name}`;
        params += `&fp=chrome`;
      } else if (node.tls?.enabled) {
        params += `&security=tls`;
        if (node.tls.server_name) params += `&sni=${node.tls.server_name}`;
      }
      if (node.flow) params += `&flow=${node.flow}`;
      if (node.transport?.type === 'ws') {
        params += `&path=${encodeURIComponent(node.transport.path || '/')}`;
        if (node.transport.headers?.Host) params += `&host=${node.transport.headers.Host}`;
      } else if (node.transport?.type === 'grpc') {
        params += `&serviceName=${node.transport.service_name || ''}`;
      }
      return `vless://${node.uuid}@${node.server}:${node.server_port}?${params}#${encTag}`;
    }

    case 'vmess': {
      const v = {
        v: '2',
        ps: node.tag,
        add: node.server,
        port: node.server_port,
        id: node.uuid,
        aid: node.alter_id || 0,
        scy: node.security || 'auto',
        net: node.transport?.type || 'tcp',
        type: 'none',
        host: node.transport?.headers?.Host || '',
        path: node.transport?.path || '',
        tls: node.tls?.enabled ? 'tls' : '',
        sni: node.tls?.server_name || '',
      };
      return `vmess://${btoa(unescape(encodeURIComponent(JSON.stringify(v))))}`;
    }

    case 'shadowsocks': {
      const auth = btoa(`${node.method}:${node.password}`);
      return `ss://${auth}@${node.server}:${node.server_port}#${encTag}`;
    }

    case 'trojan': {
      let params = `sni=${node.tls?.server_name || node.server}`;
      if (node.transport?.type === 'ws') {
        params += `&type=ws&path=${encodeURIComponent(node.transport.path || '/')}`;
      }
      return `trojan://${encodeURIComponent(node.password || '')}@${node.server}:${node.server_port}?${params}#${encTag}`;
    }

    case 'hysteria2': {
      let params = `sni=${node.tls?.server_name || node.server}`;
      if (node.tls?.insecure) params += `&insecure=1`;
      if (node.obfs?.password) params += `&obfs=${node.obfs.type || 'salamander'}&obfs-password=${node.obfs.password}`;
      return `hysteria2://${encodeURIComponent(node.password || '')}@${node.server}:${node.server_port}?${params}#${encTag}`;
    }

    case 'tuic': {
      return `tuic://${node.uuid}:${encodeURIComponent(node.password || '')}@${node.server}:${node.server_port}?congestion_control=${node.congestion_control || 'bbr'}&sni=${node.tls?.server_name || node.server}#${encTag}`;
    }

    default:
      return `${node.type}://${node.server}:${node.server_port}#${encTag}`;
  }
}

// Default Generation Options
export const defaultOptions: ConfigOptions = {
  tunEnabled: true,
  tunStack: 'mixed',
  tunInterface: 'singbox0',
  tunStrictRoute: true,
  mixedPort: 2080,
  socksPort: 1080,
  clashApiPort: 9090,
  dnsMode: 'fakeip',
  directDns: 'https://223.5.5.5/dns-query',
  remoteDns: 'https://1.1.1.1/dns-query',
  fakeIpRange: '198.18.0.0/15',
  routingMode: 'rule',
  blockAds: true,
  bypassChina: true,
  routeOpenAI: true,
  routeTelegram: true,
  urlTestInterval: '3m',
  urlTestTolerance: 50,
  logLevel: 'info',
};

// Master Sing-box Config Generator
export function generateSingboxConfig(nodes: ProxyNode[], opts: ConfigOptions = defaultOptions): Record<string, any> {
  const validNodes = nodes.filter((n) => n.server && n.server_port);
  const nodeOutbounds = validNodes.map(nodeToSingboxOutbound);
  const nodeTags = validNodes.map((n) => n.tag);

  // Inbounds
  const inbounds: any[] = [];
  if (opts.tunEnabled) {
    inbounds.push({
      type: 'tun',
      tag: 'tun-in',
      interface_name: opts.tunInterface || 'singbox0',
      inet4_address: '172.19.0.1/30',
      inet6_address: 'fdfe:dcba:9876::1/126',
      mtu: 9000,
      auto_route: true,
      strict_route: opts.tunStrictRoute,
      stack: opts.tunStack,
      sniff: true,
      sniff_override_destination: false,
    });
  }

  if (opts.mixedPort > 0) {
    inbounds.push({
      type: 'mixed',
      tag: 'mixed-in',
      listen: '127.0.0.1',
      listen_port: opts.mixedPort,
      sniff: true,
    });
  }

  // DNS Servers & Rules
  const dnsServers: any[] = [
    {
      tag: 'dns-remote',
      address: opts.remoteDns,
      detour: '🚀 节点选择',
    },
    {
      tag: 'dns-direct',
      address: opts.directDns,
      detour: 'direct',
    },
    {
      tag: 'dns-block',
      address: 'rcode://success',
    },
  ];

  if (opts.dnsMode === 'fakeip') {
    dnsServers.push({
      tag: 'dns-fakeip',
      address: 'fakeip',
    });
  }

  const dnsRules: any[] = [
    {
      outbound: 'any',
      server: 'dns-direct',
    },
    {
      rule_set: 'geosite-category-ads-all',
      server: 'dns-block',
    },
    {
      rule_set: 'geosite-cn',
      server: 'dns-direct',
    },
  ];

  if (opts.dnsMode === 'fakeip') {
    dnsRules.push({
      query_type: ['A', 'AAAA'],
      server: 'dns-fakeip',
    });
  }

  // Outbound Groups
  const outbounds: any[] = [];

  // 1. Selector
  const selectorOutbounds = ['♻️ 自动优选', ...nodeTags, 'direct'];
  outbounds.push({
    type: 'selector',
    tag: '🚀 节点选择',
    outbounds: selectorOutbounds.length > 2 ? selectorOutbounds : ['direct', 'block'],
    default: nodeTags.length > 0 ? '♻️ 自动优选' : 'direct',
  });

  // 2. URLTest (Auto low-latency)
  if (nodeTags.length > 0) {
    outbounds.push({
      type: 'urltest',
      tag: '♻️ 自动优选',
      outbounds: nodeTags,
      url: 'https://www.gstatic.com/generate_204',
      interval: opts.urlTestInterval || '3m',
      tolerance: opts.urlTestTolerance || 50,
    });
  }

  // 3. User node outbounds
  outbounds.push(...nodeOutbounds);

  // 4. Built-in outbounds
  outbounds.push(
    { type: 'direct', tag: 'direct' },
    { type: 'block', tag: 'block' },
    { type: 'dns', tag: 'dns-out' }
  );

  // Routing Rules & RuleSets
  const routeRules: any[] = [
    { protocol: 'dns', outbound: 'dns-out' },
    { inbound: 'dns-in', outbound: 'dns-out' },
    { ip_is_private: true, outbound: 'direct' },
  ];

  if (opts.blockAds) {
    routeRules.push({ rule_set: 'geosite-category-ads-all', outbound: 'block' });
  }

  if (opts.routeOpenAI) {
    routeRules.push({ rule_set: 'geosite-openai', outbound: '🚀 节点选择' });
  }

  if (opts.routeTelegram) {
    routeRules.push({ rule_set: 'geoip-telegram', outbound: '🚀 节点选择' });
  }

  if (opts.bypassChina) {
    routeRules.push({ rule_set: ['geosite-cn', 'geoip-cn'], outbound: 'direct' });
  }

  routeRules.push({ rule_set: 'geosite-geolocation-!cn', outbound: '🚀 节点选择' });

  const rule_sets: any[] = [
    {
      tag: 'geosite-category-ads-all',
      type: 'remote',
      format: 'binary',
      url: 'https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs',
      download_detour: 'direct',
    },
    {
      tag: 'geosite-cn',
      type: 'remote',
      format: 'binary',
      url: 'https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs',
      download_detour: 'direct',
    },
    {
      tag: 'geosite-geolocation-!cn',
      type: 'remote',
      format: 'binary',
      url: 'https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-geolocation-!cn.srs',
      download_detour: 'direct',
    },
    {
      tag: 'geoip-cn',
      type: 'remote',
      format: 'binary',
      url: 'https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs',
      download_detour: 'direct',
    },
  ];

  if (opts.routeOpenAI) {
    rule_sets.push({
      tag: 'geosite-openai',
      type: 'remote',
      format: 'binary',
      url: 'https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-openai.srs',
      download_detour: 'direct',
    });
  }

  if (opts.routeTelegram) {
    rule_sets.push({
      tag: 'geoip-telegram',
      type: 'remote',
      format: 'binary',
      url: 'https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-telegram.srs',
      download_detour: 'direct',
    });
  }

  const finalConfig: Record<string, any> = {
    $schema: 'https://sing-box.sagernet.org/schema/configuration.json',
    log: {
      disabled: false,
      level: opts.logLevel,
      timestamp: true,
    },
    dns: {
      servers: dnsServers,
      rules: dnsRules,
      final: 'dns-remote',
      strategy: 'prefer_ipv4',
      independent_cache: true,
      reverse_mapping: true,
    },
    inbounds,
    outbounds,
    route: {
      rules: routeRules,
      rule_set: rule_sets,
      auto_detect_interface: true,
      final: opts.routingMode === 'direct' ? 'direct' : '🚀 节点选择',
    },
    experimental: {
      cache_file: {
        enabled: true,
        path: 'cache.db',
        store_fakeip: opts.dnsMode === 'fakeip',
      },
      clash_api: {
        external_controller: `127.0.0.1:${opts.clashApiPort}`,
        external_ui: 'ui',
        secret: '',
        default_mode: opts.routingMode,
      },
    },
  };

  if (opts.dnsMode === 'fakeip') {
    finalConfig.dns.fakeip = {
      enabled: true,
      inet4_range: opts.fakeIpRange || '198.18.0.0/15',
      inet6_range: 'fc00::/18',
    };
  }

  return finalConfig;
}

// Convert ProxyNode list to Mihomo / Clash YAML format
export function generateClashYaml(nodes: ProxyNode[], opts: ConfigOptions = defaultOptions): string {
  const clashProxies: any[] = [];

  for (const n of nodes) {
    const p: Record<string, any> = {
      name: n.tag,
      server: n.server,
      port: n.server_port,
    };

    switch (n.type) {
      case 'shadowsocks':
        p.type = 'ss';
        p.cipher = n.method || 'aes-256-gcm';
        p.password = n.password;
        break;
      case 'vmess':
        p.type = 'vmess';
        p.uuid = n.uuid;
        p.alterId = n.alter_id || 0;
        p.cipher = n.security || 'auto';
        if (n.tls?.enabled) {
          p.tls = true;
          p.servername = n.tls.server_name;
          p['skip-cert-verify'] = n.tls.insecure;
        }
        if (n.transport?.type === 'ws') {
          p.network = 'ws';
          p['ws-opts'] = { path: n.transport.path || '/' };
        }
        break;
      case 'vless':
        p.type = 'vless';
        p.uuid = n.uuid;
        p.flow = n.flow;
        if (n.tls?.reality?.enabled) {
          p.reality = true;
          p.servername = n.tls.server_name;
          p['reality-opts'] = {
            'public-key': n.tls.reality.public_key,
            'short-id': n.tls.reality.short_id,
          };
          p['client-fingerprint'] = 'chrome';
        } else if (n.tls?.enabled) {
          p.tls = true;
          p.servername = n.tls.server_name;
          p['skip-cert-verify'] = n.tls.insecure;
        }
        if (n.transport?.type === 'ws') {
          p.network = 'ws';
          p['ws-opts'] = { path: n.transport.path || '/' };
        } else if (n.transport?.type === 'grpc') {
          p.network = 'grpc';
          p['grpc-opts'] = { 'grpc-service-name': n.transport.service_name || '' };
        }
        break;
      case 'trojan':
        p.type = 'trojan';
        p.password = n.password;
        p.sni = n.tls?.server_name || n.server;
        p['skip-cert-verify'] = n.tls?.insecure;
        break;
      case 'hysteria2':
        p.type = 'hysteria2';
        p.password = n.password;
        p.sni = n.tls?.server_name || n.server;
        p['skip-cert-verify'] = n.tls?.insecure;
        if (n.obfs?.password) p['obfs-password'] = n.obfs.password;
        break;
      case 'tuic':
        p.type = 'tuic';
        p.uuid = n.uuid;
        p.password = n.password;
        p.sni = n.tls?.server_name || n.server;
        p['congestion-controller'] = n.congestion_control || 'bbr';
        break;
      default:
        continue;
    }

    clashProxies.push(p);
  }

  const proxyNames = clashProxies.map((p) => p.name);

  const clashConfig: Record<string, any> = {
    port: opts.mixedPort,
    'socks-port': opts.socksPort,
    'allow-lan': false,
    mode: opts.routingMode,
    'log-level': opts.logLevel,
    'external-controller': `127.0.0.1:${opts.clashApiPort}`,
    dns: {
      enable: true,
      listen: '0.0.0.0:1053',
      'enhanced-mode': opts.dnsMode === 'fakeip' ? 'fake-ip' : 'redir-host',
      'fake-ip-range': opts.fakeIpRange,
      nameserver: [opts.directDns],
      'fallback-filter': { geoip: true, geoip_code: 'CN' },
    },
    proxies: clashProxies,
    'proxy-groups': [
      {
        name: '🚀 节点选择',
        type: 'select',
        proxies: ['♻️ 自动优选', ...proxyNames, 'DIRECT'],
      },
      {
        name: '♻️ 自动优选',
        type: 'url-test',
        proxies: proxyNames.length > 0 ? proxyNames : ['DIRECT'],
        url: 'http://www.gstatic.com/generate_204',
        interval: 300,
      },
    ],
    rules: [
      'GEOIP,PRIVATE,DIRECT',
      'GEOSITE,category-ads-all,REJECT',
      'GEOSITE,CN,DIRECT',
      'GEOIP,CN,DIRECT',
      'MATCH,🚀 节点选择',
    ],
  };

  return dump(clashConfig, { indent: 2, lineWidth: -1 });
}
