# PR41 real Android local collection acceptance

Nonpublishing branch; production candidate e164338cb8e311562565bdbc450b7572a50e5e6f.

Run 41353158271 (test head 7d2964982ad778fcbbc89dd65caa1cdb79b1d0ec)
completed successfully. Downloaded raw artifacts were independently checked:
- Two fresh actual local reports; network present online and absent after Wi-Fi/data disabled.
- Pre-send payload contains only schema/version/check IDs, states, codes and fixed limitations;
  local profile ID, network handle, context token and timestamp are absent.
- Both AI review dialogs cancelled with zero analyze invocations; no fatal Android exception.
- Test-only synthetic model config, review payload logging and invocation counter are injected.
  Collection and probes are unchanged. No provider send occurs in this test.

Limits: service was stopped, so TUNNEL was not_started and no individual probe outcomes
were collected. This is Android 9 x86_64, not a device/network matrix, and cannot prove
AI diagnostic gain. VPN routing correctness and real model quality remain separate gates.

The continuation imports an immutable public synthetic direct-TUN profile using app UI,
starts VPN with ordinary OS consent, and requires actual probe outcomes in the reviewed
payload. It also requires network change to disable further review until recollection.
No model request or production config mutation is permitted by the acceptance script.
