import json,threading,time,random
from collections import Counter
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
counts=Counter();lock=threading.Lock()
class H(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  path=self.path.strip('/')
  if path=='health': body=b'synthetic-only';code=200
  elif path=='stats':
   with lock:body=json.dumps(dict(counts)).encode()
   code=200
  else:
   with lock:counts[path]+=1
   body=b'synthetic-subscription';code=503 if path=='failure' else 200
   if path=='redirect':code=302
   if path=='mid-failure':time.sleep(45);code=503
   # Incompressible synthetic bytes plus explicit streaming headers keep the
   # controlled HTTPS relay from buffering a highly repetitive prefix.
   if path in ('mid-file','stream-preflight'):body=random.Random(42).randbytes(2*1024*1024)
  self.send_response(code)
  if path=='redirect':self.send_header('Location','/redirect-target')
  self.send_header('Content-Length',str(len(body)))
  self.send_header('Cache-Control','no-store, no-transform')
  self.send_header('Content-Type','application/octet-stream')
  self.end_headers()
  if path in ('mid-body','mid-file','stream-preflight'):
   prefix=1024*1024 if path in ('mid-file','stream-preflight') else 5
   self.wfile.write(body[:prefix]);self.wfile.flush();time.sleep(45)
   self.wfile.write(body[prefix:])
  else:self.wfile.write(body)
ThreadingHTTPServer(('127.0.0.1',18970),H).serve_forever()
