# Chain outbound (kernel contract)

This directory is documentation only. The Android app does **not** compile
this Go code into libbox.

The real implementation lives in the kernel fork:

- repo: https://github.com/dukangalex/sing-box
- branch: `chain-dev`
- files: `option/chain.go`, `option/chain_compile.go`, `protocol/chain/outbound.go`

## JSON the app must emit

```json
{
  "type": "chain",
  "tag": "chainbox-chain-1-1",
  "outbounds": ["entry-hop", "landing-hop"]
}
```

Packet path order: `outbounds[0]` is closest to the client (前置/入口).
The last tag is the exit hop (落地). IP checks must show the last hop.

The kernel clones later hops and sets `detour` to the previous hop, then
dials the last clone. Do not reverse this list in the app.

That is the entire public schema (`option.ChainOutboundOptions`). Unknown
fields (historically `fail_closed`) make sing-box refuse to decode the config.

See `docs/CHAIN.md` in the kernel repository.
