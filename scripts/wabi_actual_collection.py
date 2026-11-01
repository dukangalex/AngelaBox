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
 before=json.loads(local('result'))['at'] if local('result') else -1
 button('Run local checks',name+'-start');snap(name+'-probe-dialog');dialog_tap('checks',name+'-probe-confirm')
 for i in range(90):
  value=local('result')
  if value and json.loads(value)['at']>before: break
  time.sleep(2)
 else: raise AssertionError('no new local result: '+name)
 r=json.loads(value);(out/(name+'-local.json')).write_text(value)
 button('Review evidence for AI',name+'-review');snap(name+'-review-screen')
 lines=[line.split('WABI_REVIEW:',1)[1].strip() for line in adb('logcat','-d','-s','WABI_REVIEW:I','*:S').splitlines() if 'WABI_REVIEW:' in line]
 p=json.loads(base64.b64decode(lines[-1]));(out/(name+'-payload.json')).write_text(json.dumps(p,indent=2))
 assert set(p)=={'schema','app_version','kernel_version','checks','limitations'}
 assert p['checks']==r['facts'], (name,p['checks'],r['facts'])
 assert all(set(f)=={'id','state','code'} for f in p['checks'])
 assert all(k not in p for k in ('profile','network','context','at','service'))
 assert attempts()==0
 dialog_tap('Cancel',name+'-cancel');assert attempts()==0
 return r
online=take_collection('online')
assert next(f for f in online['facts'] if f['id']=='network') == {'id':'network','state':'OBSERVED','code':'present'}
shell('svc','wifi','disable');shell('svc','data','disable');time.sleep(8)
try: offline=take_collection('offline')
finally: shell('svc','wifi','enable');shell('svc','data','enable')
def field(report,id):return next((x for x in report['facts'] if x['id']==id),None)
summary={'production_collection_unchanged':True,'pre_send_payload_instrumented':True,'provider_sent':False,'online':{'network':field(online,'network'),'service':field(online,'service'),'tunnel':field(online,'TUNNEL'),'dns':field(online,'DNS')},'offline':{'network':field(offline,'network'),'service':field(offline,'service'),'tunnel':field(offline,'TUNNEL'),'dns':field(offline,'DNS')},'offline_loss_observed':field(offline,'network')=={'id':'network','state':'ATTENTION','code':'absent'},'app_version':'e164338cb8e311562565bdbc450b7572a50e5e6f','test_head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()}
(out/'actual-collection-report.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
assert summary['offline_loss_observed'], 'offline control did not observe network loss'
log=adb('logcat','-d')
(out/'collection-logcat.txt').write_text(log)
assert 'FATAL EXCEPTION' not in log, 'crash observed during acceptance'
assert attempts()==0, 'AI request occurred despite cancellation'
print(json.dumps(summary,indent=2))
