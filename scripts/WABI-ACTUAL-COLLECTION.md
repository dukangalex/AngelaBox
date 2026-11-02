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

Run 41355568772 repeated the stopped-service online/offline privacy controls,
but the continuation stopped at test navigation: profile creation opens Edit Profile,
not the tabbed Dashboard. Raw screenshot/XML show the fixture was created; the test
now exits via its observed Back control before choosing Dashboard. This is not
evidence of a running-VPN product failure or a successful running-VPN acceptance.

Run 41357452771 reached a running synthetic VPN with tun0 172.19.0.1 and
actual TUNNEL_1A=ok. It then exposed a product failure: Wi-Fi and mobile both
disconnected in system logs but the old report stayed reviewable because the
default network remained the VPN. Candidate 6d9b24d also observes INTERNET +
NOT_VPN network callbacks; actual Android confirmation is still required.
No provider send occurred. Old run 41357409731 is confirmed cancelled.
