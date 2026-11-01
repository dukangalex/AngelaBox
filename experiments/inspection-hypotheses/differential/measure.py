"""Loopback-only fault injection. Not an App/VPN test, no public targets.
No keys, URLs or addresses are exported in the measured observations.
"""
import http.server, ssl, socket, threading, tempfile, subprocess, time, json, hashlib
from pathlib import Path
OUT=Path(__file__).resolve().parent/'evidence'
OUT.mkdir(exist_ok=True)
class Server(http.server.ThreadingHTTPServer):
 daemon_threads=True
 def handle_error(self,*a): pass # timeout clients deliberately close their sockets
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  mode=self.server.mode
  if mode=='slow': time.sleep(.4)
  self.send_response(503 if mode=='unavailable' else 200);self.end_headers()
  try:self.wfile.write(b'synthetic')
  except (BrokenPipeError,ssl.SSLError):pass
 def log_message(self,*a):pass

def run():
 cases=[]
 with tempfile.TemporaryDirectory() as d:
  cert=Path(d)/'cert.pem';key=Path(d)/'key.pem'
  subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.load_cert_chain(cert,key)
  trust=ssl.create_default_context(cafile=str(cert))
  def probe(mode,ref):
   server=None;raw=None;tls=None
   if mode=='refused':
    sock=socket.socket();sock.bind(('127.0.0.1',0));port=sock.getsockname()[1];sock.close()
   else:
    server=Server(('127.0.0.1',0),Handler);server.mode=mode
    if mode!='plain':server.socket=ctx.wrap_socket(server.socket,server_side=True)
    port=server.server_address[1];threading.Thread(target=server.serve_forever,daemon=True).start()
   result={'id':ref,'scope':'synthetic_loopback','resolution':'fixture_loopback','connect':'not_checked','tls':'not_checked','headers':'not_checked','http_status':None}
   try:
    raw=socket.create_connection(('127.0.0.1',port),timeout=.15);result['connect']='ok'
    tls=trust.wrap_socket(raw,server_hostname='localhost');result['tls']='ok'
    tls.sendall(b'GET / HTTP/1.0\r\nHost: localhost\r\n\r\n')
    line=b''
    while b'\r\n' not in line and len(line)<128:line+=tls.recv(1)
    result['headers']='ok';result['http_status']=int(line.split()[1])
   except ConnectionRefusedError:result['connect']='refused'
   except ssl.SSLError:result['tls']='failed'
   except TimeoutError:
    if result['connect']!='ok':result['connect']='timeout'
    elif result['tls']!='ok':result['tls']='timeout'
    else:result['headers']='timeout'
   finally:
    if tls:tls.close()
    elif raw:raw.close()
    if server:server.shutdown();server.server_close()
   return result
  matrix=[('healthy','ok','ok'),('target_status','unavailable','ok'),('target_delay','slow','ok'),('shared_delay','slow','slow'),('target_tls','plain','ok'),('target_connect','refused','ok'),('both_connect','refused','refused'),('unmeasured_control','slow',None)]
  for i,(truth,a,b) in enumerate(matrix):
   case_id=hashlib.sha256(('case-v1-'+str(i)).encode()).hexdigest()[:8]
   primary=probe(a,'P');control=probe(b,'C') if b else {'id':'C','scope':'synthetic_loopback','resolution':'unknown','connect':'not_checked','tls':'not_checked','headers':'not_checked','http_status':None}
   cases.append({'id':case_id,'observations':[primary,control]})
  (OUT/'ground-truth.json').write_text(json.dumps([{'id':cases[i]['id'],'injected':x[0]} for i,x in enumerate(matrix)],indent=2)+'\n')
  (OUT/'measured-cases.json').write_text(json.dumps(cases,indent=2)+'\n')
  print(json.dumps(cases,indent=2))
if __name__=='__main__':run()
