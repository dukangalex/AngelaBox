"""One synthetic-only protocol-v2 call. No execution of model suggestions."""
import json,time,urllib.request,urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parent; OUT=ROOT/'evidence'/'v2b';OUT.mkdir(exist_ok=True)
fields=('id','scope','resolution','connect','tls','headers','http_status')
cases=json.loads((ROOT/'evidence'/'measured-cases.json').read_text())
cases=[c for c in cases if c['id'] in ('34a6defd','5e00e846','22e6d856','d0010e4e')]
cases=[{'id':c['id'],'observations':[{k:o[k] for k in fields} for o in c['observations']]} for c in cases]
system='''You are a read-only diagnostic assistant interpreting point-in-time synthetic-loopback fixture observations. P and C are independent, not real VPN/public Internet, and have no assumed shared cause. Ground truth is not supplied. Generate your own provisional causal alternatives and a useful falsifiable optional follow-up; there is NO diagnosis catalogue. Copy supporting observation facts exactly. Never confuse successful TCP/TLS with network unreachability. Unknown/not_checked is missing measurement, not failure/success. HTTP 200 is only this transaction, not general health. Preserve causal alternatives and scope. No logs, credentials, payload capture, actual configuration changes, public targets or DNS queries, disabling verification, or bypass. Follow-up must be a read-only controlled synthetic-fixture experiment, not arbitrary commands or autonomous action. Explain two distinct predicted observable outcomes and how each would change hypotheses, without claiming unique cause. Avoid generic 'check network' or 'monitor'. Healthy transaction can be retested at a specified later sample to distinguish observed persistence/transience. Return ONLY JSON {"cases":[{"id":string,"facts":[{"ref":"P"|"C","field":"connect"|"tls"|"headers"|"http_status","value":observed exact string/int/null}],"phase_summary":short generated Chinese sentence,"possible_explanations":[two generated Chinese strings],"limitations":short Chinese sentence explicitly bounding scope/uncertainty,"follow_up":{"test":one concrete optional controlled synthetic test in Chinese,"if_a":predicted outcome and implication in Chinese,"if_b":different predicted outcome and implication in Chinese}}]}. Include all four IDs exactly once, exact fields, no extra text. Each text field one short sentence; cite only supplied facts. No automatic actions.'''
body={'model':'gpt-oss-20b','stream':False,'temperature':0,'max_tokens':8192,'reasoning_effort':'low','messages':[{'role':'system','content':system},{'role':'user','content':json.dumps(cases,separators=(',',':'))}]}
(OUT/'request.json').write_text(json.dumps(body,ensure_ascii=False,indent=2)+'\n')
record={'synthetic_only':True,'cases':len(cases),'ground_truth_sent':False,'plan_commit':__import__('subprocess').check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip()};start=time.monotonic()
try:
 q=urllib.request.Request('https://text.pollinations.ai/openai/chat/completions',data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Accept':'application/json','Authorization':'Bearer synthetic-test'})
 with urllib.request.urlopen(q,timeout=110) as r:
  response=json.loads(r.read(131073))
  for choice in response.get('choices',[]):
   choice.get('message',{}).pop('reasoning',None);choice.get('message',{}).pop('reasoning_content',None)
  record.update(status=r.status,response=response)
except urllib.error.HTTPError as e:record.update(status=e.code,error=e.read(2048).decode(errors='replace'))
except Exception as e:record.update(exception=type(e).__name__)
record['seconds']=round(time.monotonic()-start,2)
(OUT/'response.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(record,ensure_ascii=False,indent=2))
