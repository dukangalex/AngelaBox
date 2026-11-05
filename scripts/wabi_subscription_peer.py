import json,threading
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
  self.send_response(code)
  if path=='redirect':self.send_header('Location','/redirect-target')
  self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store');self.end_headers()
  self.wfile.write(body)
ThreadingHTTPServer(('127.0.0.1',18970),H).serve_forever()
