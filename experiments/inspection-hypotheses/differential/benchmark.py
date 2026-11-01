"""Synthetic-only provider benchmark. Ground-truth file is never sent.
No App automatic action, production config or personal data is used.
"""
import json,time,urllib.request,urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parent;OUT=ROOT/'evidence'
cases=json.loads((OUT/'measured-cases.json').read_text())
# Explicitly rebuild the observation allowlist; arbitrary source text is not sent.
fields=('id','scope','resolution','connect','tls','headers','http_status')
cases=[{'id':c['id'],'observations':[{k:o[k] for k in fields} for o in c['observations']]} for c in cases]
system='''You are a read-only diagnostic assistant. Interpret only measured synthetic-loopback observations. P and C are independent local fixture targets, not an actual VPN or public Internet. Observed TCP/TLS/HTTP phases are point-in-time evidence, not root-cause proof. No normal DNS test took place. Never generalize to proxy settings, Internet, other apps, or real DNS. Compare P with C where measured; unknown/not_checked is not success or failure. Generate a useful provisional hypothesis and a falsifiable optional next check. No logs, credentials, configuration, subscription contents, bypassing VPN or public DNS queries. No automatic actions. Do not claim a unique root cause. Return only JSON object {"cases":[{"id":<same id>,"evidence":["P","C" as actually used],"hypothesis":<short Chinese text>,"uncertainty":<short Chinese text>,"next_check":<short Chinese text>}]}. Include all 8 cases exactly once. No catalog is provided: generate your own concise explanation from evidence. Use only 1 short sentence per field.'''
body={'model':'gpt-oss-20b','stream':False,'temperature':0,'max_tokens':3000,'messages':[{'role':'system','content':system},{'role':'user','content':json.dumps(cases,separators=(',',':'))}]}
(OUT/'diagnostic-request.json').write_text(json.dumps(body,ensure_ascii=False,indent=2)+'\n')
record={'synthetic_only':True,'cases':len(cases),'ground_truth_sent':False};start=time.monotonic()
try:
 q=urllib.request.Request('https://text.pollinations.ai/openai/chat/completions',data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Accept':'application/json','Authorization':'Bearer synthetic-test'})
 with urllib.request.urlopen(q,timeout=90) as r:
  response=json.loads(r.read(65537))
  # Keep the visible answer and transport metadata, not provider reasoning.
  for choice in response.get('choices',[]):
   message=choice.get('message',{})
   message.pop('reasoning',None);message.pop('reasoning_content',None)
  record.update(status=r.status,response=response)
except urllib.error.HTTPError as e:record.update(status=e.code,error=e.read(2048).decode(errors='replace'))
except Exception as e:record.update(exception=type(e).__name__)
record['seconds']=round(time.monotonic()-start,2)
(OUT/'diagnostic-response.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(record,ensure_ascii=False,indent=2))
