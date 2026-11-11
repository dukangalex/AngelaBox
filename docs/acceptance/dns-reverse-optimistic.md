# DNS reverse mapping and optimistic cache

Community lead: https://github.com/SagerNet/sing-box/issues/4695

Reproduced on 2026-11-11 using the official 1.15.0-alpha.13 binary and the
project's released v1.15.0-chain.11 binary (revision b4a0320eabbb9b2835055b6dfcc094f25706421f).
The included loopback-only reproduction starts two UDP DNS transports and a TCP
 echo server, with an isolated CLI config. It makes no system DNS changes and
uses no real provider or user configuration.

```sh
python3 scripts/repro_dns_reverse_optimistic.py /path/to/sing-box ./dns-repro-output
```

Both binaries reproduced the same difference:

- Optimistic off: the second short-transport answer gets TTL 300; an IP-addressed
  SOCKS connection continues to match its domain route after 1.25 seconds.
- Optimistic on: the second short-transport answer gets TTL 1; the domain route
  stops matching after 1.25 seconds, despite an identical earlier long-transport
  answer still having about 297.5 seconds left. A new query restores the route.

This is relevant to AngelaBox because the web configurator emits
`reverse_mapping: true` and `independent_cache: true`, while Android DNS Protect
previously supplied `optimistic: true` when absent. The mitigation changes only
that implicit default: leave optimistic off when reverse mapping is enabled.
Explicit user values remain unchanged. Reverse mapping disabled/missing retains
the prior optimistic default. Existing in-memory sessions need a config reload.

Scope: native Linux CLI behavior of the pinned core, not Android device or TUN
acceptance. This does not fix the core reverse-map lifetime logic, prove all
multi-transport combinations safe, or affect explicit `optimistic: true` profiles.
It should be reconsidered after a kernel-level fix is verified, not removed merely
because the upstream issue closes.
