#!/usr/bin/env python3
"""Ephemeral loopback-only SOCKS5 test exit; records CONNECT metadata, never content."""
import ipaddress,json,select,socket,socketserver,struct,threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
counts={'connect_attempts':0,'connect_successes':0,'targets':[]};lock=threading.Lock()
def exact(s,n):
 data=b''
 while len(data)<n:
  x=s.recv(n-len(data))
  if not x:raise ConnectionError('eof')
  data+=x
 return data
class Socks(socketserver.BaseRequestHandler):
 def handle(self):
  client=self.request;client.settimeout(15)
  try:
   version,n=exact(client,2);methods=exact(client,n)
   if version!=5 or 0 not in methods:return
   client.sendall(b'\x05\x00')
   version,cmd,_,kind=exact(client,4)
   if version!=5 or cmd!=1:return
   if kind==1:host=socket.inet_ntop(socket.AF_INET,exact(client,4))
   elif kind==4:host=socket.inet_ntop(socket.AF_INET6,exact(client,16))
   elif kind==3:host=exact(client,exact(client,1)[0]).decode('ascii')
   else:return
   port=struct.unpack('!H',exact(client,2))[0]
   addresses=socket.getaddrinfo(host,port,type=socket.SOCK_STREAM)
   if port!=443 or not addresses or not all(ipaddress.ip_address(a[4][0]).is_global for a in addresses):
    client.sendall(b'\x05\x02\x00\x01'+bytes(6));return
   with lock:counts['connect_attempts']+=1
   remote=socket.create_connection((host,port),timeout=15)
   with lock:
    counts['connect_successes']+=1;counts['targets'].append({'host':host,'port':port})
   client.sendall(b'\x05\x00\x00\x01'+bytes(6))
   with remote:
    while True:
     readable,_,_=select.select([client,remote],[],[],20)
     if not readable:return
     for source in readable:
      data=source.recv(65536)
      if not data:return
      (remote if source is client else client).sendall(data)
  except (OSError,ConnectionError,ValueError):return
class Stats(BaseHTTPRequestHandler):
 def do_GET(self):
  with lock:body=json.dumps(counts).encode()
  self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(body)
 def log_message(self,*args):pass
if __name__=='__main__':
 server=socketserver.ThreadingTCPServer(('127.0.0.1',18971),Socks);server.daemon_threads=True
 threading.Thread(target=server.serve_forever,daemon=True).start()
 ThreadingHTTPServer(('127.0.0.1',18972),Stats).serve_forever()
