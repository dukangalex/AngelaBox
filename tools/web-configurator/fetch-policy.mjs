import { isIP } from 'node:net';

/** Apply the IPv4 policy to every equivalent mapped IPv6 representation. */
export function blockedIp(ip) {
  let raw = ip.toLowerCase().replace(/^\[|\]$/g, '');
  if (isIP(raw) === 6) {
    // WHATWG canonicalization compresses expanded IPv6 and normalizes dotted
    // mapped tails to hex; do not classify a hex tail as unrelated IPv6.
    raw = new URL(`http://[${raw}]/`).hostname.slice(1, -1);
    if (raw.startsWith('::ffff:')) {
      const words = raw.slice(7).split(':');
      if (words.length !== 2) return true;
      const high = Number.parseInt(words[0], 16);
      const low = Number.parseInt(words[1], 16);
      raw = `${high >>> 8}.${high & 255}.${low >>> 8}.${low & 255}`;
    } else {
      return raw === '::1' || raw === '::' || raw.startsWith('fe80:') || raw.startsWith('fc') || raw.startsWith('fd');
    }
  }
  if (isIP(raw) !== 4) return true;
  const [a, b] = raw.split('.').map(Number);
  return a === 0 || a === 10 || a === 127 || a === 255 ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 100 && b >= 64 && b <= 127);
}

const USER_AGENTS = Object.freeze({
  singbox: 'sing-box/1.11.0 (Linux; x86_64)',
  clash: 'ClashMeta/v1.18.8 (Mihomo)',
  v2ray: 'v2rayN/6.39',
  curl: 'curl/8.4.0',
});

/** Reject nonstrings without coercing untrusted objects or inherited keys. */
export function selectUserAgent(value) {
  if (typeof value !== 'string') return null;
  return Object.hasOwn(USER_AGENTS, value) ? USER_AGENTS[value] : USER_AGENTS.singbox;
}
