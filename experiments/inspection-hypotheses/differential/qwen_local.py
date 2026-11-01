import json,subprocess,time,urllib.request,urllib.error
from pathlib import Path
R=Path(__file__).resolve().parent; O=R/'evidence/qwen-local'; O.mkdir(exist_ok=True)
original=json.loads((R/'evidence/v2/request.json').read_text()); case=json.loads(original['messages'][1]['content'])[0]
request={'model':'Qwen3-1.7B-Q4_K_M','temperature':0,'seed':1,'max_tokens':1800,'stream':False,'chat_template_kwargs':{'enable_thinking':False},'messages':[{'role':'system','content':original['messages'][0]['content'].replace('Include all eight IDs exactly once','Include this one ID exactly once')},{'role':'user','content':json.dumps([case],separators=(',',':'))}]}
(O/'request.json').write_text(json.dumps(request,ensure_ascii=False,indent=2)+'\n')
args=['/home/user/workspace/local-qwen/llama-b7633/llama-server','-m','/home/user/workspace/local-qwen/model.gguf','--host','127.0.0.1','--port','18762','-c','4096','-np','1','-t','2','--reasoning-budget','0','--no-webui']
record={'command':args,'runtime':'b7633','model_revision':'cd3d34a469f89b12676edce7d272750201959466','model_sha256':'228fb5627f7510b8b3516cdb6435e4b0d2a2bf330fe5b0ab19284a3570a8bb1f','plan_commit':subprocess.check_output(['git','rev-parse','HEAD']).decode().strip(),'available':False}
log=open(O/'server.log','w'); proc=subprocess.Popen(args,stdout=log,stderr=log)
try:
 for i in range(60):
  try:
   with urllib.request.urlopen('http://127.0.0.1:18762/health',timeout=2) as r:
    if r.status==200: break
  except Exception: pass
  if proc.poll() is not None: raise RuntimeError('Server exited '+str(proc.returncode))
  time.sleep(1)
 else: raise RuntimeError('Server did not become ready')
 start=time.monotonic()
 with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18762/v1/chat/completions',data=json.dumps(request).encode(),headers={'Content-Type':'application/json'}),timeout=450)as r: result=json.load(r)
 record.update(available=True,seconds=round(time.monotonic()-start,2),status=r.status,usage=result.get('usage'),timings=result.get('timings'),finish_reason=result['choices'][0].get('finish_reason'),visible_answer=result['choices'][0]['message'].get('content'))
except Exception as e:record.update(error_type=type(e).__name__,error=str(e))
finally:
 proc.terminate()
 try:proc.wait(timeout=10)
 except subprocess.TimeoutExpired:proc.kill();proc.wait()
 log.close();record['server_stopped']=True
 (O/'result.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n'); print(json.dumps(record,ensure_ascii=False,indent=2))
