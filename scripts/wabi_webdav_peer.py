#!/usr/bin/env python3
"""Disposable WebDAV-compatible PUT/GET fixture. Synthetic credentials/data only."""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import base64,json,time,socket
ROOT=Path('wabi-evidence');ROOT.mkdir(exist_ok=True)
AUTH='Basic '+base64.b64encode(b'fixture:WabiDav2026').decode()
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def log(self,code,count=0):
  with (ROOT/'webdav-events.jsonl').open('a') as f:f.write(json.dumps({'method':self.command,'path':self.path,'code':code,'bytes':count,'time':time.time()})+'\n')
 def response(self,code,data=b'',head=False):
  self.send_response(code);self.send_header('Content-Length',str(len(data)));self.send_header('Connection','close');self.end_headers()
  if not head:self.wfile.write(data)
  self.log(code,len(data))
 def allowed(self):
  if self.headers.get('Authorization')!=AUTH:self.response(401);return False
  return True
 def do_OPTIONS(self):
  if self.allowed():self.response(200)
 def do_HEAD(self):
  if self.allowed():self.response(200,head=True)
 def do_PUT(self):
  if not self.allowed():return
  data=self.rfile.read(int(self.headers.get('Content-Length','0')));(ROOT/'webdav-upload.bin').write_bytes(data);self.response(201)
 def do_GET(self):
  if self.path=='/health':self.response(200,b'synthetic-only');return
  if not self.allowed():return
  mode=(ROOT/'webdav-mode').read_text().strip() if (ROOT/'webdav-mode').exists() else 'normal'
  if mode=='invalid':self.response(200,b'<html>not a backup</html>');return
  if mode=='truncated':
   self.send_response(200);self.send_header('Content-Length','1048576');self.end_headers();self.wfile.write(b'ABKE\x01'+b'X'*65531);self.wfile.flush();self.log(200,65536);self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
  p=ROOT/'webdav-upload.bin'
  self.response(200,p.read_bytes()) if p.exists() else self.response(404)
ThreadingHTTPServer(('127.0.0.1',18970),Handler).serve_forever()
