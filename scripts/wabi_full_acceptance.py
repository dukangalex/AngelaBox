import subprocess, time, re, os, xml.etree.ElementTree as ET
P='io.chainbox.app'
def adb(*args, check=True):
 p=subprocess.run(['adb',*map(str,args)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=50)
 if check and p.returncode: raise Exception(f'adb {args}: {p.stdout}')
 return p.stdout
def shell(*a,check=True): return adb('shell',*a,check=check)
def ui(label):
 time.sleep(4); os.makedirs('wabi-evidence',exist_ok=True); name=re.sub(r'[^a-z0-9]+','-',label.lower()).strip('-')
 with open('wabi-evidence/ui-'+name+'.png','wb') as f: subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True,timeout=50)
 # The dump can return success without replacing an existing file: never read stale XML.
 s=''
 for attempt in range(4):
  shell('rm','-f','/sdcard/window.xml'); result=shell('uiautomator','dump','/sdcard/window.xml',check=False); print('UI_DUMP',label,attempt,result,flush=True)
  s=shell('cat','/sdcard/window.xml',check=False)
  if '<?xml' in s: break
  time.sleep(4)
 if '<?xml' not in s: raise Exception('UI dump unavailable for '+label+': '+result)
 root=ET.fromstring(s[s.find('<?xml'):])
 print('--- UI',label,'---'); print(s,flush=True); return root
def bounds(node):
 a=list(map(int,re.findall(r'\d+',node.attrib['bounds']))); return ((a[0]+a[2])//2,(a[1]+a[3])//2)
def clicktext(root,text):
 a=[n for n in root.iter() if n.get('text')==text or n.get('content-desc')==text]
 if not a: raise Exception('text not found: '+text)
 x,y=bounds(a[-1]); shell('input','tap',x,y); time.sleep(2)
def maybe(root,text):
 if any(n.get('text')==text for n in root.iter()): clicktext(root,text); return True
 return False
def launch():
 shell('monkey','-p',P,'-c','android.intent.category.LAUNCHER','1'); time.sleep(8)
def nav():
 root=ui('launch')
 for attempt in range(3):
  if not any(n.get('text')=='Check Update' for n in root.iter()): break
  if not (maybe(root,'No, thanks') or maybe(root,'Later') or maybe(root,'OK')): break
  time.sleep(8); root=ui('dismissed '+str(attempt))
 if any(n.get('content-desc')=='Drag handle' for n in root.iter()):
  shell('input','keyevent','4'); time.sleep(5); root=ui('closed sheet')
 if not any(n.get('text')=='Hide from recent tasks' for n in root.iter()):
  if any(n.get('text')=='Settings' or n.get('content-desc')=='Settings' for n in root.iter()):
   clicktext(root,'Settings'); root=ui('settings'); clicktext(root,'App'); root=ui('app settings')
 for i in range(15):
  if any(n.get('text')=='Hide from recent tasks' for n in root.iter()): return root
  size=shell('wm','size'); w,h=map(int,re.findall(r'(\d+)x(\d+)',size)[-1]); shell('input','swipe',str(w//2),str(int(h*.78)),str(w//2),str(int(h*.25)),'400'); root=ui('scroll '+str(i))
 raise Exception('hide setting unavailable')
def switch(root):
 title=next(n for n in root.iter() if n.get('text')=='Hide from recent tasks')
 parent=next(n for n in root.iter() if title in list(n))
 switches=[n for n in parent.iter() if n.get('checkable')=='true' and n.get('clickable')=='true']
 if len(switches)!=1: raise Exception('ambiguous switch '+str([(n.attrib) for n in switches]))
 n=switches[0]; print('TARGET_SWITCH',n.attrib,flush=True); return n
def toggle(root,expected_before):
 n=switch(root); assert n.get('checked')==expected_before, n.attrib
 x,y=bounds(n); shell('input','tap',x,y); time.sleep(3); root=ui('toggle'); assert switch(root).get('checked')!=expected_before; return root
def recents(label):
 shell('input','keyevent','3'); time.sleep(2); shell('input','keyevent','187'); time.sleep(4)
 os.makedirs('wabi-evidence',exist_ok=True)
 name=re.sub(r'[^a-z0-9]+','-',label.lower()).strip('-')
 with open('wabi-evidence/'+name+'.png','wb') as f: subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True,timeout=50)
 root=ui('recents '+label); print('DUMPSYS RECENTS',label, shell('dumpsys','activity','recents'),flush=True)
 shell('input','keyevent','3'); time.sleep(2)
def bootwait():
 adb('wait-for-device')
 for _ in range(180):
  if shell('getprop','sys.boot_completed').strip()=='1': break
  time.sleep(2)
 else: raise Exception('boot timeout')
 shell('input','keyevent','82'); time.sleep(5)

import urllib.parse, json

def save(label,text):
 os.makedirs('wabi-evidence',exist_ok=True)
 open('wabi-evidence/'+label+'.txt','w').write(text)
def home():
 launch(); r=ui('home')
 for i in range(4):
  if any(n.get('text')=='Check Update' for n in r.iter()):
   maybe(r,'No, thanks') or maybe(r,'Later') or maybe(r,'OK'); r=ui('dismiss update')
  elif any(n.get('content-desc')=='Drag handle' for n in r.iter()):
   shell('input','keyevent','4'); r=ui('dismiss sheet')
  else: break
 return r

def power(r,expected):
 clicktext(r,expected); time.sleep(5); r=ui('power '+expected)
 if any('Connection request' in n.get('text','') for n in r.iter()):
  clicktext(r,'OK'); time.sleep(12); r=ui('vpn approved')
 for i in range(6):
  if any(n.get('text')=='Deprecated Warning' for n in r.iter()):
   clicktext(r,'OK'); time.sleep(4); r=ui('deprecation acknowledged '+str(i)); continue
  if maybe(r,'Allow') or maybe(r,'ALLOW'):
   time.sleep(5); r=ui('permission allowed '+str(i)); continue
  break
 return r

r=home()
url='https://raw.githubusercontent.com/dukangalex/AngelaBox/72321df1037c0390c62a0b342ca02aa1e7b6a0a1/scripts/wabi_vpn_fixture.json'
link='sing-box://import-remote-profile?url='+urllib.parse.quote(url,safe='')+'#Wabi-VPN-Acceptance'
shell('am','start','-a','android.intent.action.VIEW','-d',link,'-p',P)
r=ui('remote import confirm'); clicktext(r,'OK'); r=ui('new remote profile'); clicktext(r,'Create'); time.sleep(15); r=ui('profile created')
for i in range(3):
 if any(n.get('text')=='Dashboard' for n in r.iter()): clicktext(r,'Dashboard'); r=ui('dashboard'); break
 shell('input','keyevent','4'); r=ui('back to dashboard')
# A single freshly imported profile should be selected by the normal creation flow.
# Probe from a separate ordinary app UID, not the privileged adb shell UID.
# A reject rule provides a direct VPN-routing control rather than assuming HTTP success means VPN routing.
def getfixture(port):
 shell('am','force-stop','org.wabi.vpnprobe')
 shell('am','start','-n','org.wabi.vpnprobe/.ProbeActivity','--es','url',f'http://10.0.2.2:{port}/')
 for i in range(5):
  time.sleep(3); root=ui('probe '+str(port)+' '+str(i))
  text=' '.join(n.get('text','') for n in root.iter())
  if 'WABI_PROBE_HTTP_' in text or 'WABI_PROBE_FAILED_' in text: return text
 raise Exception('Probe did not produce a result on port '+str(port))
control=getfixture(18081); save('control-before-vpn',control); assert 'WABI_PROBE_HTTP_200_ANGELABOX_ACCEPTANCE_OK' in control,'control fixture unavailable before VPN'
# Default LAN-direct overlay precedes the fixture's 10.0.2.2 reject rule.
# Disable it on this disposable emulator through the app UI before testing.
r=home(); clicktext(r,'Settings'); r=ui('settings for vpn routing control')
clicktext(r,'System Explicit'); r=ui('system explicit initial')
for i in range(24):
 if any(n.get('text')=='局域网直连' for n in r.iter()): break
 size=shell('wm','size'); w,h=map(int,re.findall(r'(\d+)x(\d+)',size)[-1]); shell('input','swipe',str(w//2),str(int(h*.78)),str(w//2),str(int(h*.25)),'400'); r=ui('find lan direct '+str(i))
else: raise Exception('LAN direct switch not found')
title=next(n for n in r.iter() if n.get('text')=='局域网直连')
parent=next(n for n in r.iter() if title in list(n))
controls=[n for n in parent.iter() if n.get('checkable')=='true' and n.get('clickable')=='true']
if len(controls)!=1: raise Exception('ambiguous LAN direct switch '+str([n.attrib for n in controls]))
print('LAN_DIRECT_BEFORE',controls[0].attrib,flush=True)
if controls[0].get('checked')=='true':
 x,y=bounds(controls[0]); shell('input','tap',x,y); r=ui('lan direct disabled')
 title=next(n for n in r.iter() if n.get('text')=='局域网直连')
 parent=next(n for n in r.iter() if title in list(n))
 controls=[n for n in parent.iter() if n.get('checkable')=='true' and n.get('clickable')=='true']
assert len(controls)==1 and controls[0].get('checked')=='false', 'LAN direct remains enabled'
print('LAN_DIRECT_AFTER',controls[0].attrib,flush=True)
shell('input','keyevent','4'); time.sleep(2); shell('input','keyevent','4'); time.sleep(2)
r=home(); r=power(r,'Start')
assert any(n.get('content-desc')=='Stop' for n in r.iter()),'Service did not reach running UI'
control=getfixture(18081); save('control-during-vpn',control); assert 'WABI_PROBE_FAILED_' in control,'VPN reject rule bypassed by test traffic'
r=home()

conn=shell('dumpsys','connectivity'); addr=shell('ip','addr'); save('vpn-connectivity',conn); save('vpn-addresses',addr)
assert '172.19.0.1' in addr, 'VPN TUN address not present'
http=getfixture(18080)
save('vpn-http-response',http); assert 'WABI_PROBE_HTTP_200_ANGELABOX_ACCEPTANCE_OK' in http,'HTTP through VPN failed'
r=home()
r=power(r,'Stop'); time.sleep(4); addr=shell('ip','addr'); save('vpn-stopped-addresses',addr); assert '172.19.0.1' not in addr,'TUN remained after stop'
control=getfixture(18081); save('control-after-vpn',control); assert 'WABI_PROBE_HTTP_200_ANGELABOX_ACCEPTANCE_OK' in control,'control fixture unavailable after VPN stop'
r=home()
r=power(r,'Start'); assert '172.19.0.1' in shell('ip','addr'),'TUN not recreated'
shell('am','force-stop',P); time.sleep(6); save('force-stop-addresses',shell('ip','addr')); r=home(); assert any(n.get('content-desc')=='Start' for n in r.iter()),'Stale running state after force-stop'
r=power(r,'Start'); assert '172.19.0.1' in shell('ip','addr'); r=power(r,'Stop')
# Exercise visible profile and logs/settings navigation, saving actual screenshots.
for label in ['Profiles','Logs','Settings']:
 r=home()
 if any(n.get('text')==label or n.get('content-desc')==label for n in r.iter()):
  clicktext(r,label); ui('opened '+label); shell('input','keyevent','4'); time.sleep(3)
r=home()
# Preserve and repeat the previously established recent-card toggle acceptance.
r=nav(); assert switch(r).get('checked')=='false'; r=toggle(r,'false'); recents('on'); shell('am','force-stop',P); launch(); r=nav(); assert switch(r).get('checked')=='true'; r=toggle(r,'true'); recents('off')
save('app-logcat',adb('logcat','-d'))
print('VPN/TUN import-start-http-stop-restart and recent-card acceptance completed',flush=True)

# Per-app exclusion: use the probe app to check actual routing, not just UI state.
def scroll_until(r,label,name):
 for i in range(18):
  if any(n.get('text')==label or n.get('content-desc')==label for n in r.iter()): return r
  size=shell('wm','size'); w,h=map(int,re.findall(r'(\d+)x(\d+)',size)[-1]); shell('input','swipe',w//2,int(h*.78),w//2,int(h*.25),400); r=ui(name+' '+str(i))
 raise Exception('not visible '+label)
def control_for(r,label):
 nodes=[n for n in r.iter() if n.get('text')==label]
 if len(nodes)!=1: raise Exception('unexpected label '+label+' count='+str(len(nodes)))
 title=nodes[0]
 parents={child:par for par in r.iter() for child in par}
 n=title
 while n in parents:
  n=parents[n]
  controls=[c for c in n.iter() if c.get('checkable')=='true' and c.get('clickable')=='true']
  if len(controls)==1: return controls[0]
 raise Exception('switch not found '+label)
def set_switch(r,label,value):
 n=control_for(r,label)
 if n.get('checked')!=value:
  x,y=bounds(n); shell('input','tap',x,y); r=ui('set '+label); n=control_for(r,label)
 assert n.get('checked')==value,(label,n.attrib)
 return r
# Navigate to the system-explicit override, and select unmanaged exclusion.
for i in range(5):
 r=ui('back-to-dashboard '+str(i))
 if any(n.get('content-desc')=='Start' for n in r.iter()): break
 shell('input','keyevent','4'); time.sleep(2)
else: r=home()
if not any(n.get('text')=='Settings' or n.get('content-desc')=='Settings' for n in r.iter()): r=home()
clicktext(r,'Settings'); r=ui('perapp-settings'); clicktext(r,'System Explicit'); r=ui('perapp-override')
r=scroll_until(r,'托管模式','perapp-search-managed'); r=set_switch(r,'托管模式','false')
r=scroll_until(r,'管理','perapp-search-manage'); clicktext(r,'管理'); r=ui('perapp-list')
# Search the installed probe so that selection is independent of list order.
clicktext(r,'Search'); r=ui('perapp-search'); shell('input','text','VPN%sRoute%sProbe'); time.sleep(7); r=ui('perapp-probe-search')
probe=[n for n in r.iter() if n.get('text')=='VPN Route Probe']
assert len(probe)==1,'probe app unavailable in app selection'
r=set_switch(r,'VPN Route Probe','true'); save('perapp-selection','excluded probe selected, unmanaged mode')
shell('input','keyevent','4'); shell('input','keyevent','4'); shell('input','keyevent','4'); r=home(); r=power(r,'Start'); assert '172.19.0.1' in shell('ip','addr')
excluded=getfixture(18081); save('perapp-excluded-result',excluded); assert 'WABI_PROBE_HTTP_200_ANGELABOX_ACCEPTANCE_OK' in excluded,'excluded app did not route direct'
r=home(); r=power(r,'Stop')
# Turn the setting off and verify the same app is once again subject to the VPN rule.
clicktext(r,'Settings'); r=ui('perapp-settings-disable'); clicktext(r,'System Explicit'); r=ui('perapp-override-disable'); r=scroll_until(r,'托管模式','perapp-return'); r=set_switch(r,'启用','false')
shell('input','keyevent','4'); shell('input','keyevent','4'); r=home(); r=power(r,'Start'); assert '172.19.0.1' in shell('ip','addr')
included=getfixture(18081); save('perapp-default-result',included); assert 'WABI_PROBE_FAILED_' in included,'VPN reject rule bypassed when per-app disabled'
r=home(); r=power(r,'Stop'); print('PER_APP_EXCLUDE_ROUTING_VERIFIED',flush=True)
