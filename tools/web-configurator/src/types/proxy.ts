export type ProxyType =
  | 'vless'
  | 'vmess'
  | 'shadowsocks'
  | 'trojan'
  | 'hysteria2'
  | 'hysteria'
  | 'tuic'
  | 'wireguard'
  | 'socks'
  | 'http'
  | 'direct'
  | 'block';

export interface ProxyNode {
  id: string;
  tag: string;
  type: ProxyType;
  server: string;
  server_port: number;
  uuid?: string;
  password?: string;
  security?: string;
  alter_id?: number;
  method?: string; // shadowsocks cipher (e.g. 2022-blake3-aes-128-gcm, aes-256-gcm)
  plugin?: string;
  plugin_opts?: string;
  flow?: string; // xtls-rprx-vision
  network?: string; // tcp, ws, grpc, http, etc.
  tls?: {
    enabled: boolean;
    server_name?: string;
    insecure?: boolean;
    alpn?: string[];
    reality?: {
      enabled: boolean;
      public_key: string;
      short_id?: string;
    };
  };
  transport?: {
    type: 'tcp' | 'ws' | 'grpc' | 'http' | 'httpupgrade';
    path?: string;
    headers?: Record<string, string>;
    service_name?: string;
  };
  congestion_control?: 'bbr' | 'cubic' | 'new_reno';
  up_mbps?: number;
  down_mbps?: number;
  obfs?: {
    type?: string;
    password?: string;
  };
  // Wireguard specific
  system_interface?: boolean;
  interface_name?: string;
  local_address?: string[];
  private_key?: string;
  peer_public_key?: string;
  pre_shared_key?: string;
  reserved?: number[];
  mtu?: number;

  source?: string;
  sourceType?: 'sub' | 'file' | 'manual' | 'snippet';
  flag?: string;
  latency?: number | null; // in ms
  lastChecked?: string;
  rawConfig?: any;
}

export interface Subscription {
  id: string;
  name: string;
  url: string;
  type: 'auto' | 'clash' | 'base64' | 'singbox';
  nodeCount: number;
  updatedAt: string;
  status: 'idle' | 'updating' | 'success' | 'error';
  errorMessage?: string;
  userInfo?: {
    upload?: number;
    download?: number;
    total?: number;
    expire?: number;
  };
  nodes: ProxyNode[];
}

export interface ConfigOptions {
  tunEnabled: boolean;
  tunStack: 'system' | 'gvisor' | 'mixed';
  tunInterface: string;
  tunStrictRoute: boolean;
  mixedPort: number;
  socksPort: number;
  clashApiPort: number;
  dnsMode: 'fakeip' | 'doh' | 'udp';
  directDns: string;
  remoteDns: string;
  fakeIpRange: string;
  routingMode: 'rule' | 'global' | 'direct';
  blockAds: boolean;
  bypassChina: boolean;
  routeOpenAI: boolean;
  routeTelegram: boolean;
  urlTestInterval: string;
  urlTestTolerance: number;
  logLevel: 'trace' | 'debug' | 'info' | 'warn' | 'error';
}

export interface AuditFinding {
  id: string;
  level: 'critical' | 'warning' | 'info' | 'pass';
  title: string;
  description: string;
  recommendation: string;
  category: 'security' | 'privilege' | 'dns_leak' | 'singbox_syntax' | 'performance';
  line?: number;
}

export interface ScriptAuditReport {
  score: number;
  findings: AuditFinding[];
  scriptType: string;
  auditedLines: number;
  timestamp: string;
}
