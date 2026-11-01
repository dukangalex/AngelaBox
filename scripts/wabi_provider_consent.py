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
launch();inspector()
assert local('ai'), 'test-only synthetic config not installed'
button('Run local checks','local-start');snap('probe-dialog');tap_dialog_button('Run local checks')
for i in range(60):
 if local('result'):break
 time.sleep(2)
report=json.loads(local('result'))
expected=[{'id':'TUNNEL','state':'ATTENTION','code':'timeout'},{'id':'DNS','state':'UNKNOWN','code':'canary_not_resolved'},{'id':'LATENCY','state':'NOT_CHECKED','code':'skipped'}]
assert report['facts']==expected, 'unexpected real data; do not send'
assert attempts()==0
button('Review evidence for AI','review-cancel');snap('review-before-cancel');assert attempts()==0
dialog_tap('Cancel','cancel-dialog');assert attempts()==0,'cancel initiated AI request'
button('Review evidence for AI','review-send');snap('review-before-send');assert attempts()==0
review = subprocess.check_output(['tesseract',str(out/'review-before-send.png'),'stdout'],stderr=subprocess.DEVNULL,text=True)
(out/'review-ocr.txt').write_text(review)
assert 'example.com' in review.lower() and 'synthetic-ui' in review.lower(), 'provider/model disclosure not visible'
# In-memory responses: no network provider or billing claim is possible.
def top():
 size=shell('wm','size').strip().split()[-1];w,h=map(int,size.split('x'))
 for _ in range(6):shell('input','swipe',str(w//2),str(round(h*.25)),str(w//2),str(round(h*.83)),'250')
 time.sleep(1)
results=[]
for case,expected_message in [(1,'provider returned an unsuccessful HTTP status'),(2,'response matched a local sensitive-advice rule'),(3,'仅为此时点观察，未确定根因')]:
 if case > 1:
  top();button('Review evidence for AI','review-send-'+str(case));snap('review-before-send-'+str(case))
 dialog_tap('Send','send-dialog-'+str(case))
 node=find(expected_message,'result-'+str(case),scroll=12)
 root=snap('result-confirmed-'+str(case));text=ET.tostring(root,encoding='unicode')
 assert attempts()==case,(case,attempts())
 if case<3:
  assert 'AI analysis failed' not in text,'generic failure still shown'
  assert '仅为此时点观察，未确定根因' not in text,'unexpected interpretation'
 results.append({'case':case,'expected_ui_message':expected_message,'attempts':attempts()})
(out/'synthetic-request-diagnostics.txt').write_text(adb('logcat','-d','-s','WABI_TEST:I','*:S'))
(out/'failure-ui-acceptance.json').write_text(json.dumps({'cancel_no_attempt':True,'cases':results,'synthetic_in_memory_transport':True,'external_provider_requests':False,'production_modified':True,'does_not_validate_model_quality':True},ensure_ascii=False,indent=2))
print('PASS: cancel zero attempts; HTTP rejection, sensitive rejection and synthetic text have distinct UI results; no automatic retry')
