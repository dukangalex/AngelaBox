"""Mechanical validation only; semantic scoring in assessment.json is assistant review."""
import json
from pathlib import Path
root=Path(__file__).resolve().parent/'evidence/qwen4-local'
response=json.loads((root/'result.json').read_text())
request=json.loads((root/'request.json').read_text())
fixtures=json.loads(request['messages'][1]['content'])
answer=json.loads(response['visible_answer'])
assert set(answer)=={'cases'}
assert [a['id'] for a in answer['cases']]==[f['id'] for f in fixtures]
for a,f in zip(answer['cases'],fixtures):
 assert set(a)=={'id','facts','phase_summary','possible_explanations','limitations','follow_up'}
 assert len(a['possible_explanations'])==2 and all(isinstance(x,str) and x for x in a['possible_explanations'])
 assert set(a['follow_up'])=={'test','if_a','if_b'}
 assert all(isinstance(v,str) and v for v in a['follow_up'].values())
 assert isinstance(a['phase_summary'],str) and a['phase_summary']
 assert isinstance(a['limitations'],str) and a['limitations']
 assert a['facts']
 for fact in a['facts']:
  assert set(fact)=={'ref','field','value'}
  assert fact['field'] in {'connect','tls','headers','http_status'}
  assert any(o['id']==fact['ref'] and fact['field'] in o and type(fact['value']) is type(o[fact['field']]) and fact['value']==o[fact['field']] for o in f['observations'])
print('PASS: strict JSON, required schema/IDs and exact cited values; does NOT validate semantic safety/scope or diagnostic gate')
