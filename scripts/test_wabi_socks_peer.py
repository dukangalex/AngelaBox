import importlib.util,socket,socketserver,struct,threading,unittest
from unittest.mock import patch
from pathlib import Path
spec=importlib.util.spec_from_file_location('peer',Path(__file__).with_name('wabi_socks_peer.py'));peer=importlib.util.module_from_spec(spec);spec.loader.exec_module(peer)
class SocksTest(unittest.TestCase):
 def setUp(self):
  peer.counts.update(connect_attempts=0,connect_successes=0,targets=[])
  self.server=socketserver.ThreadingTCPServer(('127.0.0.1',0),peer.Socks);self.server.daemon_threads=True
  threading.Thread(target=self.server.serve_forever,daemon=True).start()
 def tearDown(self):self.server.shutdown();self.server.server_close()
 def connect(self):
  s=socket.socket();s.settimeout(2);s.connect(self.server.server_address);s.sendall(b'\x05\x01\x00');self.assertEqual(peer.exact(s,2),b'\x05\x00');return s
 def test_private_destination_is_rejected_without_connection(self):
  with self.connect() as s:
   s.sendall(b'\x05\x01\x00\x01'+socket.inet_aton('127.0.0.1')+struct.pack('!H',443));self.assertEqual(peer.exact(s,10)[1],2)
  self.assertEqual(peer.counts['connect_attempts'],0)
 def test_other_port_is_rejected(self):
  with self.connect() as s:
   s.sendall(b'\x05\x01\x00\x01'+socket.inet_aton('1.1.1.1')+struct.pack('!H',80));self.assertEqual(peer.exact(s,10)[1],2)
  self.assertEqual(peer.counts['connect_attempts'],0)
 def test_public_connect_relays_both_directions_and_counts_success(self):
  remote,endpoint=socket.socketpair()
  with patch.object(peer.socket,'create_connection',return_value=remote):
   with self.connect() as s:
    s.sendall(b'\x05\x01\x00\x01'+socket.inet_aton('1.1.1.1')+struct.pack('!H',443));self.assertEqual(peer.exact(s,10)[1],0)
    s.sendall(b'fixed-synthetic');self.assertEqual(peer.exact(endpoint,15),b'fixed-synthetic')
    endpoint.sendall(b'fixed-reply');self.assertEqual(peer.exact(s,11),b'fixed-reply')
  endpoint.close();self.assertEqual(peer.counts['connect_attempts'],1);self.assertEqual(peer.counts['connect_successes'],1)
 def test_non_connect_command_cannot_open_socket(self):
  with self.connect() as s:
   s.sendall(b'\x05\x03\x00\x01');self.assertEqual(s.recv(1),b'')
  self.assertEqual(peer.counts['connect_attempts'],0)
if __name__=='__main__':unittest.main()
