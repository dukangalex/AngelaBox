"""Two observationally identical header timeouts, distinguished by a controlled longer wait.
Synthetic loopback only. No external network, App, credential or payload capture.
"""
import ssl,socket,threading,time,json,tempfile,subprocess
from pathlib import Path
OUT=Path(__file__).resolve().parent/'evidence'/'v2';OUT.mkdir(exist_ok=True)
def run():
 with tempfile.TemporaryDirectory() as d:
  cert=Path(d)/'cert';key=Path(d)/'key'
  subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.load_cert_chain(cert,key)
  trust=ssl.create_default_context(cafile=str(cert))
  def probe(delay,wait):
   listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen(1)
   port=listener.getsockname()[1]
   def serve():
    try:
     raw,_=listener.accept()
     with ctx.wrap_socket(raw,server_side=True) as s:
      s.recv(4096);time.sleep(delay)
      try:s.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n')
      except (OSError,ssl.SSLError):pass
    finally:listener.close()
   t=threading.Thread(target=serve,daemon=True);t.start()
   result={'connect':'not_checked','tls':'not_checked','headers':'not_checked','http_status':None,'header_wait_seconds':wait}
   with socket.create_connection(('127.0.0.1',port),timeout=1) as raw:
    result['connect']='ok'
    with trust.wrap_socket(raw,server_hostname='localhost') as s:
     result['tls']='ok';s.settimeout(wait);s.sendall(b'GET / HTTP/1.0\r\nHost: localhost\r\n\r\n');start=time.monotonic()
     try:
      line=s.recv(256);result['headers']='ok';result['http_status']=int(line.split()[1])
     except TimeoutError:result['headers']='timeout'
     result['header_seconds']=round(time.monotonic()-start,3)
   t.join(3);assert not t.is_alive();return result
  results=[{'opaque_id':'A','initial':probe(.4,.15),'longer_bound':probe(.4,1.0),'injected_delay_seconds':.4}, {'opaque_id':'B','initial':probe(1.4,.15),'longer_bound':probe(1.4,1.0),'injected_delay_seconds':1.4}]
  assert all(r['initial']['connect']==r['initial']['tls']=='ok' and r['initial']['headers']=='timeout' for r in results)
  assert results[0]['longer_bound']['http_status']==200 and results[1]['longer_bound']['headers']=='timeout'
  report={'scope':'controlled synthetic loopback, not App/Internet','same_initial_observable_pattern':True,'results':results,'interpretation':'A: headers observed within 1s but not .15s; B: still unobserved by 1s. This distinguishes only bounded response behavior, not unique root cause. B also eventually responds at 1.4s: no finite timeout establishes permanently absent service.','model_executed_checks':False}
  (OUT/'controlled-followup.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':run()
