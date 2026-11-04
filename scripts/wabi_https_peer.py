#!/usr/bin/env python3
"""Ephemeral loopback origin behind TLS tunnel. Only synthetic payloads accepted; no request logging."""
import json, threading, time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
COUNTS=Counter(); LOCK=threading.Lock()
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def reply(self,status,body,headers=None):
  data=body if isinstance(body,bytes) else json.dumps(body,separators=(',',':')).encode()
  self.send_response(status)
  for k,v in (headers or {}).items():self.send_header(k,v)
  self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.end_headers()
  try:self.wfile.write(data)
  except (BrokenPipeError,ConnectionResetError):pass
 def do_GET(self):
  if self.path=='/health':return self.reply(200,{'fixture':'synthetic-only'})
  if self.path=='/route-probe':
   with LOCK:COUNTS['route_probe']+=1
   return self.reply(200,{'fixture':'synthetic-only','probe':'active-network'})
  if self.path=='/stats':
   with LOCK: snapshot=dict(COUNTS)
   return self.reply(200,snapshot)
  with LOCK:COUNTS['unexpected_get']+=1
  self.reply(404,{})
 def do_POST(self):
  case=self.path.split('/')[1]
  if self.path!=f'/{case}/v1/chat/completions' or case not in ('success','rate-limit','redirect','malformed','oversize','stale-response','stale-before'):
   return self.reply(404,{})
  try:
   n=int(self.headers.get('Content-Length','0')); assert 0<n<16384
   assert self.headers.get('Authorization')=='Bearer synthetic-only-not-a-secret'
   data=json.loads(self.rfile.read(n)); assert data['model']=='synthetic-model' and data['stream'] is False
   msgs=data['messages']; assert len(msgs)==2 and msgs[1]['role']=='user'
   assert json.loads(msgs[1]['content'])=={'checks':[{'id':'network','state':'OBSERVED','code':'present'}]}
  except Exception:
   with LOCK:COUNTS['invalid_synthetic_request']+=1
   return self.reply(400,{})
  with LOCK:COUNTS[case]+=1
  if case=='rate-limit':return self.reply(429,{'error':'synthetic limit'})
  if case=='redirect':return self.reply(302,{}, {'Location':'/redirect-target'})
  if case=='malformed':return self.reply(200,b'not-json')
  if case=='oversize':return self.reply(200,b' '*65537)
  if case=='stale-response':time.sleep(2)
  self.reply(200,{'choices':[{'finish_reason':'stop','message':{'role':'assistant','content':'{"review":[]}'}}]})
if __name__=='__main__':ThreadingHTTPServer(('127.0.0.1',18970),Handler).serve_forever()
