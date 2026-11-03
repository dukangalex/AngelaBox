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
def local(name):
 try:return shell('run-as',P,'cat','no_backup/inspection-'+name+'.json').strip()
 except subprocess.CalledProcessError:return ''
def launch():shell('monkey','-p',P,'-c','android.intent.category.LAUNCHER','1');time.sleep(4)
def inspector():
 # OS compatibility dialog on Android 9.
 root=snap('launch')
 for n in root.iter('node'):
  if n.attrib.get('text') in ('OK','Continue','Got it'):tap(n);break
 tap(find('Tools','tools',4));tap(find('App inspector','entry',5));snap('inspector')
def button(t,tag):tap(find(t,tag,5))
def tap_dialog_button(label):
 # Android 9's Compose AlertDialog is visible in screenshots but omitted from
 # UIAutomator's accessibility dump (it returns the underlying screen tree).
 # Use the measured dialog button positions relative to the emulator viewport.
 size=shell('wm','size').strip().split()[-1]
 width,height=map(int,size.split('x'))
 fraction_x=0.38 if label=='Cancel' else 0.72
 shell('input','tap',str(round(width*fraction_x)),str(round(height*0.70)))
 time.sleep(2)
def dialog_tap(word, tag):
 snap(tag)
 image=out/(tag+'.png')
 tsv=subprocess.check_output(['tesseract',str(image),'stdout','tsv'],stderr=subprocess.DEVNULL,text=True)
 matches=[]
 for line in tsv.splitlines()[1:]:
  cols=line.split('\t')
  if len(cols)==12 and cols[11].strip().lower().strip('_:')==word.lower():
   x,y,w,h=map(int,cols[6:10]); matches.append((y,x,w,h))
 if not matches: raise AssertionError(f'dialog button {word} not found by OCR: {tsv}')
 y,x,w,h=max(matches); shell('input','tap',str(x+w//2),str(y+h//2));time.sleep(2)
def attempts():
 return adb('logcat','-d','-s','WABI_TEST:I','*:S').count('AI_REQUEST_ATTEMPT')

import base64
launch();inspector()
assert local('ai'), 'test-only configuration not installed'
def take_collection(name):
 previous_attempts=attempts()
 before=json.loads(local('result'))['at'] if local('result') else -1
 button('Run local checks',name+'-start');snap(name+'-probe-dialog');dialog_tap('checks',name+'-probe-confirm')
 for i in range(90):
  value=local('result')
  if value and json.loads(value)['at']>before: break
  time.sleep(2)
 else: raise AssertionError('no new local result: '+name)
 r=json.loads(value);(out/(name+'-local.json')).write_text(value)
 (out/(name+'-route.txt')).write_text(adb('logcat','-d','-s','WABI_ROUTE:I','*:S'))
 button('Review evidence for AI',name+'-review');snap(name+'-review-screen')
 lines=[line.split('WABI_REVIEW:',1)[1].strip() for line in adb('logcat','-d','-s','WABI_REVIEW:I','*:S').splitlines() if 'WABI_REVIEW:' in line]
 p=json.loads(base64.b64decode(lines[-1]));(out/(name+'-payload.json')).write_text(json.dumps(p,indent=2))
 assert set(p)=={'schema','app_version','kernel_version','checks','limitations'}
 assert p['checks']==r['facts'], (name,p['checks'],r['facts'])
 assert all(set(f)=={'id','state','code'} for f in p['checks'])
 assert all(k not in p for k in ('profile','network','context','at','service'))
 assert attempts()==previous_attempts
 dialog_tap('Cancel',name+'-cancel');assert attempts()==previous_attempts
 return r
online=take_collection('online')
assert next(f for f in online['facts'] if f['id']=='network') == {'id':'network','state':'OBSERVED','code':'present'}
shell('svc','wifi','disable');shell('svc','data','disable');time.sleep(8)
try: offline=take_collection('offline')
finally: shell('svc','wifi','enable');shell('svc','data','enable')
def field(report,id):return next((x for x in report['facts'] if x['id']==id),None)
summary={'test_only_route_probe_added':True,'pre_send_payload_instrumented':True,'provider_sent':False,'online':{'network':field(online,'network'),'service':field(online,'service'),'tunnel':field(online,'TUNNEL'),'dns':field(online,'DNS')},'offline':{'network':field(offline,'network'),'service':field(offline,'service'),'tunnel':field(offline,'TUNNEL'),'dns':field(offline,'DNS')},'offline_loss_observed':field(offline,'network')=={'id':'network','state':'ATTENTION','code':'absent'},'test_head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()}
(out/'actual-collection-report.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
assert summary['offline_loss_observed'], 'offline control did not observe network loss'
log=adb('logcat','-d')
(out/'collection-logcat.txt').write_text(log)
assert 'FATAL EXCEPTION' not in log, 'crash observed during acceptance'
assert attempts()==0, 'AI request occurred despite cancellation'
# Continue with a public synthetic direct-TUN fixture, imported through ordinary UI.
# This does not inject reports, network outcomes, TunnelGate state, or probe transport.
import urllib.parse
fixture='https://raw.githubusercontent.com/dukangalex/AngelaBox/72321df1037c0390c62a0b342ca02aa1e7b6a0a1/scripts/wabi_vpn_fixture.json'
link='sing-box://import-remote-profile?url='+urllib.parse.quote(fixture,safe='')+'#Wabi-Inspection-Synthetic'
shell('am','start','-a','android.intent.action.VIEW','-d',link,'-p',P)
time.sleep(4)
button('OK','fixture-import-confirm');button('Create','fixture-create');time.sleep(12)
# Creation navigates to Edit Profile, not the tabbed root (observed in run
# 41355568772). Exit using its observed Back control before selecting a tab.
root=snap('fixture-created')
assert any(n.attrib.get('text')=='Edit Profile' for n in root.iter('node')), 'fixture was not created'
# The import can originate in the inspector sub-screen, whose tab bar is hidden.
# Walk only observed Back/Close controls until the actual Dashboard tab appears.
for depth in range(4):
 root=snap('fixture-return-root'+str(depth))
 tabs=[n for n in root.iter('node') if n.attrib.get('text')=='Dashboard']
 if tabs:
  tap(tabs[0]);break
 backs=[n for n in root.iter('node') if n.attrib.get('content-desc') in ('Back','Close')]
 assert len(backs)==1, 'no unique observed Back/Close navigation control'
 tap(backs[0])
else: raise AssertionError('tabbed root did not appear after profile creation')
button('Start','fixture-start')
root=snap('fixture-start-result')
if any('Connection request' in n.attrib.get('text','') for n in root.iter('node')):
 button('OK','fixture-vpn-permission');time.sleep(8)
for i in range(8):
 root=snap('fixture-running'+str(i))
 # Notification permission may appear before Android's VPN consent dialog.
 # Re-evaluate the current tree after each system dialog instead of assuming order.
 if any('Connection request' in n.attrib.get('text','') for n in root.iter('node')):
  ok=[n for n in root.iter('node') if n.attrib.get('text')=='OK']
  assert len(ok)==1, 'VPN consent dialog lacks a unique OK button'
  tap(ok[0]);time.sleep(8);continue
 if any(n.attrib.get('text')=='Deprecated Warning' for n in root.iter('node')):
  button('OK','fixture-deprecation');continue
 if any(n.attrib.get('text') in ('Allow','ALLOW') for n in root.iter('node')):
  node=next(n for n in root.iter('node') if n.attrib.get('text') in ('Allow','ALLOW'));tap(node);continue
 if any(n.attrib.get('content-desc')=='Stop' for n in root.iter('node')): break
 time.sleep(4)
else: raise AssertionError('synthetic VPN did not reach running UI')
addresses=shell('ip','addr');(out/'running-addresses.txt').write_text(addresses)
assert '172.19.0.1' in addresses, 'synthetic TUN address not present'
button('Tools','running-tools');button('App inspector','running-inspector')
running=take_collection('running')
assert running['service'] is True
assert field(running,'service')=={'id':'service','state':'OBSERVED','code':'running'}
assert field(running,'TUNNEL')['code']!='not_started'
probe_ids={'TUNNEL_1A','TUNNEL_1B','TUNNEL_2A','TUNNEL_2B'}
probes=[f for f in running['facts'] if f['id'] in probe_ids]
assert 1<=len(probes)<=4, probes
assert all(set(f)=={'id','state','code'} for f in probes)
assert len(running['facts'])<=10
assert attempts()==0
(out/'route-log.txt').write_text(adb('logcat','-d','-s','WABI_ROUTE:I','*:S'))
summary['running']={'service':field(running,'service'),'tunnel':field(running,'TUNNEL'),'attempts':probes,'profile_is_synthetic':True,'test_only_route_probe_added':True}
(out/'actual-collection-report.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
# A reviewed payload must not be sent after the evidence context changes.
button('Review evidence for AI','stale-review');snap('stale-review-open')
# Network loss invalidates the review. Cancelling remains zero requests, and a new
# review must be disabled until new local observations exist.
shell('svc','wifi','disable');shell('svc','data','disable');time.sleep(8)
(out/'stale-connectivity.txt').write_text(shell('dumpsys','connectivity'))
(out/'stale-addresses.txt').write_text(shell('ip','addr'))
# Exercise the exact previously-open review's Send handler after network loss.
# The test-only transport seam fails locally if entered: no provider can receive data.
assert attempts()==0
dialog_tap('Send','stale-review-send')
assert attempts()==1, 'stale Send did not exercise production analyzeUsing guard'
transport_opens=adb('logcat','-d','-s','WABI_TEST:I','*:S').count('AI_TRANSPORT_OPEN')
assert transport_opens==0, 'stale evidence reached transport opener'
# The fixed error is below the viewport after the long observation card.
# Reveal and record it; do not treat an off-screen node as a product failure.
find('evidence became stale','stale-error-scroll',5)
root=snap('stale-send-result')
# Error text is fixed local UI, not provider content.
assert any('evidence became stale' in n.attrib.get('text','').lower() for n in root.iter('node')), ET.tostring(root,encoding='unicode')
root=snap('stale-after-network-change')
reviews=[n for n in root.iter('node') if n.attrib.get('text')=='Review evidence for AI']
assert len(reviews)==1
# Compose exposes enabled on its clickable parent rather than its text child.
parent=next((n for n in root.iter('node') if reviews[0] in list(n)),None)
assert reviews[0].attrib.get('enabled')=='false' or (parent is not None and parent.attrib.get('enabled')=='false'), ET.tostring(root,encoding='unicode')
assert attempts()==1
shell('svc','wifi','enable');shell('svc','data','enable')
# Regaining an uplink must not revive the already-invalidated snapshot. Then
# actual recollection must recover the usable preview without sending anything.
time.sleep(12)
(out/'restored-connectivity.txt').write_text(shell('dumpsys','connectivity'))
assert json.loads(local('result'))==running, 'network restoration silently replaced local evidence'
def assert_review_disabled(tag):
 root=snap(tag)
 reviews=[n for n in root.iter('node') if n.attrib.get('text')=='Review evidence for AI']
 assert len(reviews)==1, 'expected one review control'
 parent=next((n for n in root.iter('node') if reviews[0] in list(n)),None)
 assert reviews[0].attrib.get('enabled')=='false' or (parent is not None and parent.attrib.get('enabled')=='false'), ET.tostring(root,encoding='unicode')
assert_review_disabled('restored-still-stale')
assert attempts()==1
recovered=take_collection('recovered')
assert recovered['at']>running['at']
assert field(recovered,'network')=={'id':'network','state':'OBSERVED','code':'present'}
assert recovered['service'] is True
assert any(f['id'] in probe_ids and f['code']=='ok' for f in recovered['facts']), 'no actual successful post-recovery TUN probe'
# Persisted evidence must not become fresh merely by reopening the app process.
# The Android process is genuinely stopped and relaunched, not a new Activity.
pid_before=shell('pidof',P).strip()
shell('am','force-stop',P);time.sleep(2)
launch();inspector()
pid_after=shell('pidof',P).strip()
assert pid_before and pid_after and pid_before!=pid_after
(out/'restart-pids.json').write_text(json.dumps({'before':pid_before,'after':pid_after}))
assert json.loads(local('result'))==recovered, 'restart changed persisted local evidence'
find('Review evidence for AI','restart-review',5)
assert_review_disabled('restart-still-stale')
assert attempts()==1
# Clear must delete the retained report, not AI config, and cause no request.
button('Clear local report','clear-local-report');time.sleep(2)
assert not local('result'), 'clear did not remove local report'
assert local('ai'), 'clear unexpectedly removed model configuration'
assert_review_disabled('clear-review-disabled')
assert attempts()==1
summary['recovery']={'old_evidence_still_stale':True,'new_evidence_at':recovered['at'],'service':field(recovered,'service'),'network':field(recovered,'network'),'successful_tunnel_probe':True,'cancel_no_additional_ai_calls':True}
summary['restart']={'new_process':True,'persisted_evidence_stale':True,'no_additional_ai_calls':True}
summary['clear']={'report_deleted':True,'model_config_retained':True,'review_disabled':True,'no_additional_ai_calls':True}
summary['stale_review_disabled_after_network_change']=True
summary['cancel_zero_ai_calls']=True  # asserted independently after each of three cancels
summary['stale_send_analyze_calls']=attempts()
summary['stale_send_transport_opens']=transport_opens
summary['stale_send_rejected_before_transport']=True
(out/'actual-collection-report.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
log=adb('logcat','-d');(out/'collection-logcat.txt').write_text(log)
assert 'FATAL EXCEPTION' not in log
print(json.dumps(summary,indent=2))
