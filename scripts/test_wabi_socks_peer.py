import importlib.util,socket,socketserver,struct,threading,time,unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('peer',Path(__file__).with_name('wabi_socks_peer.py'));peer=importlib.util.module_from_spec(spec);spec.loader.exec_module(peer)
class SocksTest(unittest.TestCase):
 def setUp(self):
  deadline=time.monotonic()+3
  while peer.snapshot()['active_sessions'] and time.monotonic()<deadline:time.sleep(.05)
  self.assertEqual(peer.snapshot()['active_sessions'],0)
  self.assertTrue(peer.reset_phase())
  self.server=socketserver.ThreadingTCPServer(('127.0.0.1',0),peer.Socks);self.server.daemon_threads=True
  threading.Thread(target=self.server.serve_forever,daemon=True).start()
 def tearDown(self):self.server.shutdown();self.server.server_close()
 def connect(self):
  s=socket.socket();s.settimeout(2);s.connect(self.server.server_address);s.sendall(b'\x05\x01\x00');self.assertEqual(peer.exact(s,2),b'\x05\x00');return s
 def test_private_destination_is_rejected_without_connection(self):
  with self.connect() as s:
   s.sendall(b'\x05\x01\x00\x01'+socket.inet_aton('127.0.0.1')+struct.pack('!H',443));self.assertEqual(peer.exact(s,10)[1],2)
  self.assertEqual(peer.snapshot()['connect_attempts'],0)
 def test_other_port_is_rejected(self):
  with self.connect() as s:
   s.sendall(b'\x05\x01\x00\x01'+socket.inet_aton('1.1.1.1')+struct.pack('!H',80));self.assertEqual(peer.exact(s,10)[1],2)
  self.assertEqual(peer.snapshot()['connect_attempts'],0)
 def test_public_connect_relays_both_directions_and_records_timestamps(self):
  remote,endpoint=socket.socketpair()
  with patch.object(peer.socket,'create_connection',return_value=remote):
   with self.connect() as s:
    s.sendall(b'\x05\x01\x00\x01'+socket.inet_aton('1.1.1.1')+struct.pack('!H',443));self.assertEqual(peer.exact(s,10)[1],0)
    s.sendall(b'fixed-synthetic');self.assertEqual(peer.exact(endpoint,15),b'fixed-synthetic')
    endpoint.sendall(b'fixed-reply');self.assertEqual(peer.exact(s,11),b'fixed-reply')
  endpoint.close()
  deadline=time.monotonic()+3
  while peer.snapshot()['active_sessions'] and time.monotonic()<deadline:time.sleep(.02)
  state=peer.snapshot()
  self.assertEqual(state['connect_attempts'],1);self.assertEqual(state['connect_successes'],1)
  self.assertEqual(state['active_sessions'],0)
  self.assertEqual(len(state['events']),1)
  event=state['events'][0]
  self.assertEqual(event['phase'],state['phase'])
  self.assertTrue(event['started_at']);self.assertTrue(event['finished_at'])
  self.assertLessEqual(event['started_monotonic_ns'],event['finished_monotonic_ns'])
  self.assertEqual(event['status'],'connected')
 def test_non_connect_command_cannot_open_socket(self):
  with self.connect() as s:
   s.sendall(b'\x05\x03\x00\x01');self.assertEqual(s.recv(1),b'')
  self.assertEqual(peer.snapshot()['connect_attempts'],0)
 def test_phase_reset_clears_counts_only_when_no_sessions_are_active(self):
  with peer.lock:
   peer.counts['active_sessions']=1
  before=peer.snapshot()['phase']
  self.assertFalse(peer.reset_phase())
  self.assertEqual(peer.snapshot()['phase'],before)
  with peer.lock:
   peer.counts['active_sessions']=0
   peer.counts['connect_attempts']=3
   peer.counts['events']=[{'phase':before}]
  self.assertTrue(peer.reset_phase())
  state=peer.snapshot()
  self.assertEqual(state['phase'],before+1)
  self.assertEqual(state['connect_attempts'],0)
  self.assertEqual(state['events'],[])
  self.assertEqual(state['active_sessions'],0)
if __name__=='__main__':unittest.main()
