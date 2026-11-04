#!/usr/bin/env python3
"""Nonpublishing emulator acceptance: exercise actual UI and local consent boundaries."""
import json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
P='io.chainbox.app'; out=Path('wabi-evidence');out.mkdir(exist_ok=True)
def adb(*a): return subprocess.check_output(['adb',*a],text=True,timeout=40)
def shell(*a): return adb('shell',*a)
def snap(tag):
 shell('uiautomator','dump','/sdcard/window.xml'); x=shell('cat','/sdcard/window.xml'); (out/(tag+'.xml')).write_text(x)
 (out/(tag+'.png')).write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p'],timeout=40));return ET.fromstring(x[x.index('<hierarchy'):])
def tap(n):
 b=list(map(int,re.findall(r'\d+',n.attrib['bounds'])));shell('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(2)
def find(text,tag,scroll=0):
 # Emulator dimensions vary; keep the swipe inside the display and move upward
 # so Compose scroll containers reveal controls below the current viewport.
 size=shell('wm','size').strip().split()[-1]
 width,height=map(int,size.split('x'))
 for i in range(scroll+1):
  root=snap(tag+str(i)); nodes=[n for n in root.iter('node') if text in n.attrib.get('text','') or text==n.attrib.get('content-desc')]
  if nodes:return nodes[0]
  if i < scroll:
   shell('input','swipe',str(width//2),str(round(height*.82)),str(width//2),str(round(height*.25)),'450');time.sleep(1)
 raise AssertionError('UI control absent: '+text)

import os,urllib.request,urllib.parse,datetime,socket
base=os.environ['WABI_HTTPS_BASE']
sha=os.environ['GITHUB_SHA']
def button(label,tag):tap(find(label,tag,5))
def peer_counts():
 with urllib.request.urlopen('http://127.0.0.1:18970/stats',timeout=5) as r:return json.load(r)
def proxy_counts():
 with urllib.request.urlopen('http://127.0.0.1:18972/stats',timeout=5) as r:return json.load(r)
def reset_proxy_phase():
 req=urllib.request.Request('http://127.0.0.1:18972/reset',method='POST',data=b'')
 with urllib.request.urlopen(req,timeout=5) as r:state=json.load(r)
 assert state['active_sessions']==0,state
 assert state['connect_attempts']==0 and state['connect_successes']==0,state
 return state
def wait_proxy_quiet(tag,quiet_seconds=8,max_seconds=60):
 started=time.monotonic();quiet_since=None;previous=None
 while time.monotonic()-started<max_seconds:
  state=proxy_counts()
  counters=(state['connect_attempts'],state['connect_successes'])
  if state['active_sessions']==0 and counters==previous:
   if quiet_since is None:quiet_since=time.monotonic()
   elif time.monotonic()-quiet_since>=quiet_seconds:
    (out/(tag+'-quiescence.json')).write_text(json.dumps({'quiet_seconds':quiet_seconds,'elapsed_seconds':round(time.monotonic()-started,2),'final_stats':state},indent=2))
    return state
  else:quiet_since=None
  previous=counters
  time.sleep(1)
 raise AssertionError(f'proxy did not reach {quiet_seconds}s quiet with no active SOCKS sessions within {max_seconds}s: {proxy_counts()}')
def utc_now():return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='milliseconds')
def request(mode,stage):
 # Remove prior result before starting; missing/new file is never an old success.
 shell('run-as',P,'rm','-f','no_backup/wabi-https-result.json')
 request_started_at=utc_now();request_started_monotonic_ns=time.monotonic_ns()
 shell('am','start','-W','-n',P+'/io.nekohasekai.sfa.inspection.WabiTransportActivity','--es','endpoint',base,'--es','mode',mode)
 for _ in range(80):
  try:
   raw=shell('run-as',P,'cat','no_backup/wabi-https-result.json');result=json.loads(raw);break
  except (subprocess.CalledProcessError,json.JSONDecodeError):time.sleep(2)
 else:raise AssertionError('bounded synthetic HTTPS entry did not finish')
 result['harness_timing']={'started_at':request_started_at,'started_monotonic_ns':request_started_monotonic_ns,'completed_at':utc_now(),'completed_monotonic_ns':time.monotonic_ns()}
 (out/(stage+'-result.json')).write_text(json.dumps(result,indent=2))
 snap(stage)
 assert result['passed'],result
 shell('am','force-stop',P) if stage=='baseline' else shell('input','keyevent','4')
 return result
def active_network_probe():
 shell('run-as',P,'rm','-f','no_backup/wabi-active-network-result.json')
 started_at=utc_now();started_ns=time.monotonic_ns()
 shell('am','start','-W','-n',P+'/io.nekohasekai.sfa.inspection.WabiTransportActivity','--es','endpoint',base,'--es','mode','active-network-probe')
 for _ in range(40):
  try:
   result=json.loads(shell('run-as',P,'cat','no_backup/wabi-active-network-result.json'));break
  except (subprocess.CalledProcessError,json.JSONDecodeError):time.sleep(2)
 else:raise AssertionError('activeNetwork-bound route probe did not finish')
 result['harness_timing']={'started_at':started_at,'started_monotonic_ns':started_ns,'completed_at':utc_now(),'completed_monotonic_ns':time.monotonic_ns()}
 (out/'active-network-probe-result.json').write_text(json.dumps(result,indent=2))
 snap('active-network-probe')
 return result

def start_vpn(mode):
 shell('monkey','-p',P,'-c','android.intent.category.LAUNCHER','1');time.sleep(5)
 uri=f'https://raw.githubusercontent.com/dukangalex/AngelaBox/{sha}/scripts/wabi_egress_{mode}.json'
 link='sing-box://import-remote-profile?url='+urllib.parse.quote(uri,safe='')+'#Controlled-'+mode
 shell('am','start','-a','android.intent.action.VIEW','-d',link,'-p',P);time.sleep(4)
 button('OK',mode+'-import-confirm');button('Create',mode+'-create');time.sleep(12)
 for depth in range(5):
  root=snap(mode+'-navigation'+str(depth))
  tabs=[n for n in root.iter('node') if n.attrib.get('text')=='Dashboard']
  if tabs:tap(tabs[0]);break
  backs=[n for n in root.iter('node') if n.attrib.get('content-desc') in ('Back','Close')]
  assert len(backs)==1,'no unique observed Back/Close control'
  tap(backs[0])
 else:raise AssertionError('root Dashboard did not appear')
 # Import creates a profile but does not necessarily select it when another
 # profile already exists. Choose the mode explicitly in the observed picker.
 dashboard=snap(mode+'-before-select')
 labels=[n for n in dashboard.iter('node') if n.attrib.get('text','').startswith('Controlled-')]
 assert labels,'dashboard selected profile label absent'
 labels.sort(key=lambda n:int(re.findall(r'\d+',n.attrib['bounds'])[1]))
 tap(labels[0])
 picker=snap(mode+'-picker')
 choices=[n for n in picker.iter('node') if n.attrib.get('text')=='Controlled-'+mode]
 assert len(choices)==1,'requested mode not uniquely present in profile picker'
 tap(choices[0])
 selected=snap(mode+'-selected-before-start')
 assert any(n.attrib.get('text')=='Controlled-'+mode for n in selected.iter('node')),'profile selection did not apply'
 # Selection persists on Profiles; explicitly return to the observed dashboard.
 # Do not search/scroll for Start on the Profiles screen.
 for depth in range(4):
  root=snap(mode+'-return-dashboard'+str(depth))
  starts=[n for n in root.iter('node') if n.attrib.get('content-desc')=='Start' or n.attrib.get('text')=='Start']
  if starts:break
  tabs=[n for n in root.iter('node') if n.attrib.get('text')=='Dashboard']
  backs=[n for n in root.iter('node') if n.attrib.get('content-desc') in ('Back','Close')]
  # Profiles renders its visible toolbar up-arrow as an unlabeled Android
  # button, so it is absent from the Back/Close description query. Use the
  # system Back action only after the observed heading confirms this page,
  # then re-snapshot and require Dashboard Start before proceeding.
  profiles=any(n.attrib.get('text')=='Profiles' for n in root.iter('node'))
  if len(tabs)==1:tap(tabs[0])
  elif len(backs)==1:tap(backs[0])
  elif profiles:
   shell('input','keyevent','4');time.sleep(2)
  else:raise AssertionError('cannot return to Dashboard from observed screen')
 else:raise AssertionError('Dashboard Start did not appear after profile selection')
 assert any(n.attrib.get('text')=='Controlled-'+mode for n in root.iter('node')),'dashboard selected mode mismatch'
 button('Start',mode+'-start')
 for i in range(10):
  root=snap(mode+'-running'+str(i));texts=[n.attrib.get('text','') for n in root.iter('node')]
  if 'Connection request' in texts or 'Deprecated Warning' in texts:
   button('OK',mode+'-system-dialog');time.sleep(5);continue
  allowed=[n for n in root.iter('node') if n.attrib.get('text') in ('Allow','ALLOW')]
  if allowed:tap(allowed[0]);continue
  if any(n.attrib.get('content-desc')=='Stop' for n in root.iter('node')):break
  time.sleep(3)
 else:raise AssertionError('VPN did not reach running UI')
 addr=shell('ip','addr');(out/(mode+'-addresses.txt')).write_text(addr)
 assert '172.19.0.1' in addr,'actual TUN address absent'
 # The imported profile must be the mode under test, not the previous profile.
 dashboard=snap(mode+'-selected-profile')
 assert any('Controlled-'+mode in n.attrib.get('text','') for n in dashboard.iter('node')),'selected profile not proven'
def stop_vpn():
 shell('monkey','-p',P,'-c','android.intent.category.LAUNCHER','1');time.sleep(3)
 button('Stop','stop-vpn');time.sleep(5)
 assert '172.19.0.1' not in shell('ip','addr'),'TUN remained after Stop'
# Baseline proves peer can answer but no traffic is going through test SOCKS exit.
request('success','baseline')
baseline_peer=peer_counts();baseline_proxy=proxy_counts()
assert baseline_peer=={'success':1},baseline_peer
assert baseline_proxy['connect_attempts']==0,baseline_proxy
# Previous run 41795466117 proves default guard rejection. Select the
# existing non-FakeIP setting on this synthetic emulator. No transport bypass.
shell('am','start','-W','-n',P+'/io.nekohasekai.sfa.inspection.WabiTransportActivity','--es','endpoint',base,'--es','mode','controlled-real-dns')
assert 'dnsProtect=false' in shell('run-as',P,'cat','no_backup/wabi-real-dns-setting.txt')
start_vpn('allow')
# System apps may keep unrelated HTTPS sessions open while the VPN is active.
# Correlate this unique tunnel hostname's destination IP and SOCKS CONNECT time
# with the peer's one-shot route-probe counter instead of requiring global idle.
route_peer_before=peer_counts();route_proxy_before=proxy_counts()
active_probe=active_network_probe()
route_peer_after=peer_counts();route_proxy_after=proxy_counts()
route_host=urllib.parse.urlsplit(base).hostname
route_ips={item[4][0] for item in socket.getaddrinfo(route_host,443,type=socket.SOCK_STREAM)}
probe_start_ns=active_probe['harness_timing']['started_monotonic_ns']
probe_end_ns=active_probe['harness_timing']['completed_monotonic_ns']
prior_events=len(route_proxy_before['events'])
matching_route_events=[event for event in route_proxy_after['events'][prior_events:] if event.get('status')=='connected' and event.get('host') in route_ips and probe_start_ns<=event.get('started_monotonic_ns',0)<=probe_end_ns]
(out/'active-network-probe-counters.json').write_text(json.dumps({'peer_before':route_peer_before,'peer_after':route_peer_after,'proxy_before':route_proxy_before,'proxy_after':route_proxy_after,'route_host':route_host,'route_ips':sorted(route_ips),'matching_route_events':matching_route_events},indent=2))
assert active_probe.get('passed') is True,active_probe
assert active_probe.get('network_present') is True,active_probe
assert active_probe.get('transport_vpn') is True,active_probe
assert route_peer_after.get('route_probe',0)==route_peer_before.get('route_probe',0)+1,(route_peer_before,route_peer_after)
assert matching_route_events,{'route_ips':sorted(route_ips),'probe_start_ns':probe_start_ns,'probe_end_ns':probe_end_ns,'new_proxy_events':route_proxy_after['events'][prior_events:]}
route_result={'passed':True,'probe':active_probe,'destination_host':route_host,'destination_ips':sorted(route_ips),'matching_socks_connects':matching_route_events,'peer_before':route_peer_before,'peer_after':route_peer_after,'proxy_before':route_proxy_before,'proxy_after':route_proxy_after,'actual_tun':True,'synthetic_peer_only':True,'production_transport_unchanged':True}
(out/'active-network-route-result.json').write_text(json.dumps(route_result,indent=2))
if os.environ.get('WABI_ROUTE_ONLY') == '1':
 stop_vpn()
 (out/'controlled-egress-scope.txt').write_text('API34 emulator route-only acceptance: debug-only activeNetwork.openConnection to a unique synthetic HTTPS tunnel endpoint. Requires activeNetwork TRANSPORT_VPN, the one-shot endpoint counter, and a timestamp-correlated SOCKS CONNECT to the same tunnel IP. The emulator used the app VpnService/TUN; production app source and HTTPS transport unchanged. Synthetic peer only; not a real provider, remote/geographic exit, consent UI or physical-device proof. The prior run 41810330431 separately passed allow/reject controls.\n')
 print('activeNetwork-bound synthetic HTTPS request reached both peer and controlled SOCKS through API34 VPN/TUN.')
 raise SystemExit(0)

# Drain delayed allow-phase work before taking the reject-phase baseline. The
# bounded quiet period is conservative: any later connection is still counted
# in its timestamped phase and fails the rejection assertion rather than being
# silently attributed away.
routed_drain=wait_proxy_quiet('allow-drain')
routed_peer=peer_counts();routed_proxy=proxy_counts()
(out/'routed-counts.json').write_text(json.dumps({'peer':routed_peer,'socks':routed_proxy,'drain':routed_drain},indent=2))
reject_baseline=reset_proxy_phase()
(out/'reject-phase-baseline.json').write_text(json.dumps(reject_baseline,indent=2))
start_vpn('reject');request('reject','rejected')
blocked_peer=peer_counts();blocked_request_proxy=proxy_counts()
(out/'blocked-request-counts.json').write_text(json.dumps({'peer':blocked_peer,'socks':blocked_request_proxy},indent=2))
assert blocked_peer==routed_peer,(blocked_peer,routed_peer)
stop_vpn()
blocked_drain=wait_proxy_quiet('reject-drain')
blocked_peer=peer_counts();blocked_proxy=proxy_counts()
(out/'blocked-counts.json').write_text(json.dumps({'peer':blocked_peer,'socks':blocked_proxy,'drain':blocked_drain},indent=2))
assert blocked_proxy['connect_attempts']==0,blocked_proxy
assert blocked_proxy['connect_successes']==0,blocked_proxy
summary={'passed':True,'production_transport_unchanged':True,'active_network_probe':active_probe,'active_network_route_peer':route_peer_after,'active_network_route_proxy':route_proxy_after,'synthetic_payload':True,'actual_tun':True,'baseline_peer':baseline_peer,'baseline_proxy':baseline_proxy,'routed_peer':routed_peer,'routed_proxy':routed_proxy,'reject_phase_baseline':reject_baseline,'blocked_peer':blocked_peer,'blocked_proxy':blocked_proxy,'allow_quiet_seconds':8,'reject_quiet_seconds':8,'real_model_provider':False,'remote_proxy_exit':False,'dns_mode':'existing DNS protection setting off in controlled emulator; explicit real UDP DNS; production guard unchanged'}
(out/'controlled-egress-result.json').write_text(json.dumps(summary,indent=2))
(out/'controlled-egress-scope.txt').write_text('API34 emulator, actual VpnService/TUN and sing-box SOCKS outbound on a controlled runner-host exit. A debug-only activeNetwork.openConnection probe must be identified as VPN and observed at both the synthetic peer and SOCKS exit. Production InspectionAi/system DNS/HTTPS unchanged; no real model provider, remote/geographic exit, user-consent UI or physical-device proof. No automatic AI retry.\n')
print('Controlled TUN/SOCKS allow-vs-reject assertions passed; synthetic peer only.')
