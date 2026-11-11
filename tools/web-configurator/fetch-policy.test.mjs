import test from 'node:test';
import assert from 'node:assert/strict';
import { blockedIp, selectUserAgent } from './fetch-policy.mjs';

for (const address of [
  '127.0.0.1', '10.0.0.1', '169.254.169.254', '192.168.1.1',
  '::1', '0:0:0:0:0:0:0:1', '::', 'fe80::1', 'fd00::1',
  '::ffff:127.0.0.1', '::ffff:7f00:1', '0:0:0:0:0:ffff:7f00:1',
  '::ffff:a00:1', '::ffff:a9fe:a9fe', '::ffff:c0a8:101',
  '[::ffff:7f00:1]', 'garbage',
]) {
  test(`reject ${address}`, () => assert.equal(blockedIp(address), true));
}
for (const address of ['8.8.8.8', '::ffff:808:808', '::ffff:8.8.8.8', '2606:4700:4700::1111']) {
  test(`allow public ${address}`, () => assert.equal(blockedIp(address), false));
}
test('URL canonicalized mapped loopback is blocked', () => {
  for (const url of ['http://[::ffff:127.0.0.1]/', 'http://[0:0:0:0:0:ffff:7f00:1]/']) {
    assert.equal(blockedIp(new URL(url).hostname), true);
  }
});
test('nonstring user agents are rejected without key coercion', () => {
  for (const value of [null, {}, { toString: null }, [], true, 1]) {
    assert.equal(selectUserAgent(value), null);
  }
});
test('known agent and unknown-string fallback retain behavior', () => {
  assert.equal(selectUserAgent('curl'), 'curl/8.4.0');
  for (const value of ['unknown', '__proto__', 'constructor', 'toString']) {
    assert.equal(selectUserAgent(value), selectUserAgent('singbox'));
  }
});
