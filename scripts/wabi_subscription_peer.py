import json,threading,time
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
   # A five-byte prefix may remain inside HTTPS proxy/client buffering.
   # Send enough to cross buffers, while retaining an incomplete body.
   if path=='mid-file':body=b'synthetic-subscription\n'*(32*1024)
  self.send_response(code)
  if path=='redirect':self.send_header('Location','/redirect-target')
  self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store');self.end_headers()
  if path in ('mid-body','mid-file'):
   prefix=256*1024 if path=='mid-file' else 5
   self.wfile.write(body[:prefix]);self.wfile.flush();time.sleep(45)
   self.wfile.write(body[prefix:])
  else:self.wfile.write(body)
ThreadingHTTPServer(('127.0.0.1',18970),H).serve_forever()
