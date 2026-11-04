#!/usr/bin/env python3
"""Run unmodified production HTTPS transport through debug-only synthetic entry. Not UI-consent/model proof."""
import json,os,subprocess,time,urllib.request
from pathlib import Path
out=Path('wabi-evidence');out.mkdir(exist_ok=True)
P='io.chainbox.app'
def shell(*args):return subprocess.check_output(['adb','shell',*args],text=True,timeout=35)
base=os.environ['WABI_HTTPS_BASE']
shell('am','start','-W','-n',P+'/io.nekohasekai.sfa.inspection.WabiTransportActivity','--es','endpoint',base)
for _ in range(110):
 try:
  raw=shell('run-as',P,'cat','no_backup/wabi-https-result.json');result=json.loads(raw);break
 except (subprocess.CalledProcessError,json.JSONDecodeError):time.sleep(2)
else:raise AssertionError('bounded HTTPS activity did not finish')
(out/'controlled-https-result.json').write_text(json.dumps(result,indent=2))
with urllib.request.urlopen('http://127.0.0.1:18970/stats',timeout=5) as response:counts=json.load(response)
(out/'controlled-https-peer-counts.json').write_text(json.dumps(counts,indent=2))
expected={k:1 for k in ('success','rate-limit','redirect','malformed','oversize','stale-response')}
assert result['passed'],result
assert counts==expected,(counts,expected)
(out/'controlled-https-scope.txt').write_text('API34 emulator; production InspectionAi.analyze, system DNS, RemoteUrlGuard, HTTPClient.openPinned, default CA/SNI/hostname validation; no resolver/transport substitution. Synthetic fixed payload and compatible test peer, not actual model provider, real user data, UI consent or physical proxy-exit proof. No VPN enabled for this phase.\n')
print('Controlled production HTTPS cases and exact peer counts passed (synthetic peer, no VPN).')
