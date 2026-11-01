"""Validate injected stage observations, not model semantic quality or App behavior."""
import json
from pathlib import Path
root=Path(__file__).resolve().parent/'evidence'
cases=json.loads((root/'measured-cases.json').read_text())
truth=json.loads((root/'ground-truth.json').read_text())
assert len(cases)==8 and len({c['id'] for c in cases})==8
observations={c['id']:{o['id']:o for o in c['observations']} for c in cases}
for case in truth:
 pair=observations[case['id']];p,c=pair['P'],pair['C'];kind=case['injected']
 if kind=='healthy':assert p['http_status']==c['http_status']==200
 elif kind=='target_status':assert p['http_status']==503 and c['http_status']==200
 elif kind=='target_delay':assert p['headers']=='timeout' and c['http_status']==200
 elif kind=='shared_delay':assert p['headers']==c['headers']=='timeout' and p['tls']==c['tls']=='ok'
 elif kind=='target_tls':assert p['connect']=='ok' and p['tls']=='failed' and c['http_status']==200
 elif kind=='target_connect':assert p['connect']=='refused' and c['http_status']==200
 elif kind=='both_connect':assert p['connect']==c['connect']=='refused'
 elif kind=='unmeasured_control':assert p['headers']=='timeout' and c['resolution']=='unknown' and c['connect']=='not_checked'
 else:raise AssertionError(kind)
print('8 injected observation patterns verified; no model accuracy claim')
