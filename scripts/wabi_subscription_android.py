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

def sub_case(name,expected,up):
 before=peer_counts(); shell('run-as',P,'rm','-f','no_backup/subscription-result.json')
 shell('am','start','-W','-n',P+'/io.nekohasekai.sfa.inspection.WabiTransportActivity','--es','endpoint',base,'--es','case',name)
 for _ in range(70):
  try:
   result=json.loads(shell('run-as',P,'cat','no_backup/subscription-result.json'));break
  except (subprocess.CalledProcessError,json.JSONDecodeError):time.sleep(2)
 else:raise AssertionError('subscription call timeout')
 after=peer_counts()
 (out/(name+'-subscription.json')).write_text(json.dumps({'result':result,'peer_before':before,'peer_after':after,'proxy':proxy_counts()},indent=2))
 assert result['success']==expected,result
 assert result['tunnel_up']==up and result['active_vpn']==up,result
 if name=='blocked':assert after.get(name,0)==before.get(name,0),(before,after)
 else:assert after.get(name,0)==before.get(name,0)+1,(before,after)
 if expected:assert 'synthetic-subscription' in result['body'],result
 snap(name+'-after')
 return result
def mid_request_case(name):
 before=peer_counts()
 shell('run-as',P,'rm','-f','no_backup/subscription-result.json')
 started=utc_now()
 shell('am','start','-W','-n',P+'/io.nekohasekai.sfa.inspection.WabiTransportActivity','--es','endpoint',base,'--es','case',name)
 for _ in range(80):
  observed=peer_counts()
  if observed.get(name,0)==before.get(name,0)+1:break
  time.sleep(.25)
 else:raise AssertionError('request did not reach controlled peer while VPN up')
 # Peer holds headers/body for 45s. Stop the actual App VPN only after the
 # controlled peer observes this unique request, not before starting it.
 peer_seen=utc_now()
 stop_vpn()
 stopped=utc_now()
 for _ in range(80):
  try:
   result=json.loads(shell('run-as',P,'cat','no_backup/subscription-result.json'));break
  except (subprocess.CalledProcessError,json.JSONDecodeError):time.sleep(1)
 else:raise AssertionError('mid-request call did not finish in bounded window')
 # Observe beyond the server's delayed response and possible retry window.
 time.sleep(15)
 after=peer_counts()
 evidence={'result':result,'peer_before':before,'peer_after':after,'started_at':started,'peer_seen_at':peer_seen,'vpn_stopped_at':stopped,'completed_at':utc_now(),'proxy':proxy_counts(),'addresses_after':shell('ip','addr')}
 (out/(name+'-subscription.json')).write_text(json.dumps(evidence,indent=2))
 assert result['tunnel_up'] and result['active_vpn'],result
 assert not result['tunnel_up_after'] and not result['active_vpn_after'],result
 assert not result['success'],result
 assert after.get(name,0)==before.get(name,0)+1,evidence
 if name=='mid-file':assert not result['partial_file_exists'],result
 snap(name+'-after')
 return evidence
shell('am','start','-W','-n',P+'/io.nekohasekai.sfa.inspection.WabiTransportActivity','--es','mode','controlled-real-dns')
assert 'dnsProtect=false' in shell('run-as',P,'cat','no_backup/wabi-real-dns-setting.txt')
start_vpn('allow')
sub_case('ok',True,True)
sub_case('redirect',True,True)
assert peer_counts().get('redirect-target')==1,peer_counts()
sub_case('failure',False,True)
stop_vpn()
sub_case('stopped',True,False)
start_vpn('reject')
sub_case('blocked',False,True)
stop_vpn()
# Reuse the existing Controlled-allow profile rather than reimport duplicates.
def restart_allow():
 shell('monkey','-p',P,'-c','android.intent.category.LAUNCHER','1');time.sleep(3)
 root=snap('restart-allow')
 labels=[n for n in root.iter('node') if n.attrib.get('text','').startswith('Controlled-')]
 assert labels
 labels.sort(key=lambda n:int(re.findall(r'\d+',n.attrib['bounds'])[1]));tap(labels[0])
 root=snap('restart-picker');choices=[n for n in root.iter('node') if n.attrib.get('text')=='Controlled-allow'];assert len(choices)==1;tap(choices[0])
 shell('input','keyevent','4');time.sleep(2)
 button('Start','restart-start');time.sleep(6)
 assert '172.19.0.1' in shell('ip','addr')
for case in ('mid-failure','mid-body','mid-file'):
 restart_allow();mid_request_case(case)
(out/'subscription-scope.txt').write_text('API34 emulator; real VpnService/TUN and unmodified merged production HTTPClient getString/downloadToFile SUBSCRIPTION; synthetic HTTPS peer via runner SOCKS. Baseline success/redirect/503/blocked/stopped cases plus mid-request App VPN Stop after peer receipt, during delayed headers, partial body and file download. Peer counts prove no duplicate endpoint request; tests assert failure and partial-file cleanup. Not physical device, real subscription provider, crash/OS revoke, interface handover, all redirect/connection races, system kill switch, UI subscription persistence or complete release acceptance.\n')
print('Subscription VPN route and mid-request controls passed')
