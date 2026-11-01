import json,time,urllib.request,subprocess
from pathlib import Path
from gradio_client import Client
R=Path(__file__).resolve().parent; O=R/'evidence/qwen-official'; O.mkdir(exist_ok=True)
original=json.loads((R/'evidence/v2/request.json').read_text())
cases=json.loads(original['messages'][1]['content'])
system=original['messages'][0]['content'].replace('Include all eight IDs exactly once','Include this one ID exactly once')
info=json.load(urllib.request.urlopen('https://qwen-qwen3-demo.hf.space/gradio_api/info',timeout=30))
params=info['named_endpoints']['/add_message']['parameters']
(O/'public-api-contract.json').write_text(json.dumps(params,ensure_ascii=False,indent=2)+'\n')
record={'plan_commit':subprocess.check_output(['git','rev-parse','HEAD']).decode().strip(),'synthetic_only':True,'model':'qwen3-235b-a22b','cases':[]}
client=Client('https://qwen-qwen3-demo.hf.space', verbose=False)
# Public toggle disables default thinking; no private state supplied.
client.predict(api_name='/toggle_thinking')
for case in cases:
 if record['cases']: client.predict(api_name='/new_chat'); client.predict(api_name='/toggle_thinking')
 prompt=system+'\n\nSynthetic observations:\n'+json.dumps([case],separators=(',',':'))
 entry={'id':case['id'],'input':prompt}; start=time.monotonic()
 try:
  result=client.predict(input_value=prompt,settings_form_value=params[1]['parameter_default'],api_name='/add_message')
  # Keep visible final text only; exclude any tool/thinking content.
  history=result[1] if isinstance(result,(list,tuple)) else result
  visible=[]
  if isinstance(history,dict): history=history.get('value',[])
  for msg in history or []:
   if msg.get('role')=='assistant':
    content=msg.get('content',[])
    if isinstance(content,str): visible.append(content)
    else: visible.extend(x.get('content','') for x in content if x.get('type')=='text')
  entry['visible_answer']='\n'.join(visible)
  entry['available']=bool(entry['visible_answer'])
 except Exception as e: entry.update(available=False,error_type=type(e).__name__,error=str(e)[:1000])
 entry['seconds']=round(time.monotonic()-start,2); record['cases'].append(entry)
 (O/'results.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({k:v for k,v in entry.items() if k!='input'},ensure_ascii=False),flush=True)
 if not entry.get('available'): break
