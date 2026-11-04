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

import os,urllib.request,urllib.parse
base=os.environ['WABI_HTTPS_BASE']
sha=os.environ['GITHUB_SHA']
def button(label,tag):tap(find(label,tag,5))
def peer_counts():
 with urllib.request.urlopen('http://127.0.0.1:18970/stats',timeout=5) as r:return json.load(r)
def proxy_counts():
 with urllib.request.urlopen('http://127.0.0.1:18972/stats',timeout=5) as r:return json.load(r)
def request(mode,stage):
 # Remove prior result before starting; missing/new file is never an old success.
 shell('run-as',P,'rm','-f','no_backup/wabi-https-result.json')
 shell('am','start','-W','-n',P+'/io.nekohasekai.sfa.inspection.WabiTransportActivity','--es','endpoint',base,'--es','mode',mode)
 for _ in range(80):
  try:
   raw=shell('run-as',P,'cat','no_backup/wabi-https-result.json');result=json.loads(raw);break
  except (subprocess.CalledProcessError,json.JSONDecodeError):time.sleep(2)
 else:raise AssertionError('bounded synthetic HTTPS entry did not finish')
 (out/(stage+'-result.json')).write_text(json.dumps(result,indent=2))
 snap(stage)
 assert result['passed'],result
 shell('am','force-stop',P) if stage=='baseline' else shell('input','keyevent','4')
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
  if len(tabs)==1:tap(tabs[0])
  elif len(backs)==1:tap(backs[0])
  else:raise AssertionError('cannot return to Dashboard from observed profile picker')
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
start_vpn('allow');request('success','routed')
routed_peer=peer_counts();routed_proxy=proxy_counts()
(out/'routed-counts.json').write_text(json.dumps({'peer':routed_peer,'socks':routed_proxy},indent=2))
assert routed_peer=={'success':2},routed_peer
assert routed_proxy['connect_successes']>=1,routed_proxy
stop_vpn()
start_vpn('reject');request('reject','rejected')
blocked_peer=peer_counts();blocked_proxy=proxy_counts()
(out/'blocked-counts.json').write_text(json.dumps({'peer':blocked_peer,'socks':blocked_proxy},indent=2))
assert blocked_peer==routed_peer,(blocked_peer,routed_peer)
assert blocked_proxy==routed_proxy,(blocked_proxy,routed_proxy)
stop_vpn()
summary={'passed':True,'production_transport_unchanged':True,'synthetic_payload':True,'actual_tun':True,'baseline_peer':baseline_peer,'baseline_proxy':baseline_proxy,'routed_peer':routed_peer,'routed_proxy':routed_proxy,'blocked_peer':blocked_peer,'blocked_proxy':blocked_proxy,'real_model_provider':False,'remote_proxy_exit':False,'dns_mode':'existing DNS protection setting off in controlled emulator; explicit real UDP DNS; production guard unchanged'}
(out/'controlled-egress-result.json').write_text(json.dumps(summary,indent=2))
(out/'controlled-egress-scope.txt').write_text('API34 emulator, actual VpnService/TUN and sing-box SOCKS outbound on a controlled runner-host exit. Production InspectionAi/system DNS/HTTPS unchanged; debug synthetic entry only. Reject control must prevent both peer request and SOCKS CONNECT. This is not a real model provider, remote/geographic exit, user-consent UI or physical-device proof. No automatic AI retry.\n')
print('Controlled TUN/SOCKS allow-vs-reject assertions passed; synthetic peer only.')
