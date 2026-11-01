"""Offline forensic assessment only; never repairs output for application use."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parent
r=json.loads((ROOT/'evidence/v2b/response.json').read_text())
s=r['response']['choices'][0]['message']['content']
request=json.loads((ROOT/'evidence/v2b/request.json').read_text())
inputs=json.loads(request['messages'][1]['content'])
expected=[c['id'] for c in inputs]
try:
    json.loads(s)
    strict=True;err=None
except json.JSONDecodeError as e:
    strict=False;err=str(e)
# Prefix read is exclusively forensic: the actual protocol must reject entire answer.
prefix,end=json.JSONDecoder().raw_decode(s)
by_id={c['id']:c for c in prefix['cases']}
results=[]
reasons={
 '34a6defd': ['Claims network link is without anomaly from two transactions; limitations do not explicitly bound to independent synthetic fixtures.', 'Follow-up repeats at unspecified same interval; adds no valid distinction beyond fixed baseline.'],
 '5e00e846': ['Phase summary loses successful TCP/TLS detail; explanations add unmeasured network loss/rate limiting possibilities without scope.', 'Successful longer wait is asserted to prove timeout-threshold cause; it only establishes delayed headers within the new bound. No incremental distinction beyond baseline.'],
 '22e6d856': ['Claims of normal C are not bounded to one synthetic transaction; alternative certificate/protocol causes remain unmeasured.', 'Changing TLS version or certificate violates read-only/no-configuration-change protocol. Outcomes overstate cause and do not distinguish alternatives safely.'],
 'd0010e4e': ['Case omitted, including the required unmeasured-control limitation.']}
for c in inputs:
    id=c['id'];a=by_id.get(id)
    exact=bool(a) and all(any(o['id']==f['ref'] and f['field'] in o and o[f['field']]==f['value'] for o in c['observations']) for f in a['facts'])
    results.append({'id':id,'visible_case':a is not None,'cited_values_match_input':exact,'protocol_shape':0,'scope_uncertainty':0,'safe_followup':int(id in ('34a6defd','5e00e846')),'discrimination':0,'incremental_value':0,'review_reasons':reasons[id]})
out={'provider_status':r['status'],'seconds':r['seconds'],'strict_json_accepted':strict,'strict_json_error':err,'trailing_characters':s[end:],'expected_ids':expected,'visible_prefix_ids':list(by_id),'application_outcome':'Reject entire output; prefix assessment is forensic only, not repaired acceptance.','semantic_review':'Assistant qualitative scoring, not independent or blinded accuracy measurement.','denominator':4,'gate_passed':False,'full_eight_case_gate':'Unfulfilled; initial request returned HTTP400 without a scored response.','cases':results,'comparison':'No valid incremental diagnostic value demonstrated against preregistered local baseline. Controlled longer-wait experiment independently validates a useful bounded observation, not model diagnosis reliability.'}
(ROOT/'evidence/v2b/assessment.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(out,ensure_ascii=False,indent=2))
