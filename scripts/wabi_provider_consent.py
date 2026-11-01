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
assert 'pollinations' in review.lower() and 'gpt-oss' in review.lower(), 'provider/model disclosure not visible'
# The screenshot proves the consent screen was displayed; the Send control starts exactly one request.
dialog_tap('Send','send-dialog')
for i in range(60):
 time.sleep(2)
 if i in (4, 12, 22): shell('input','swipe','500','1500','500','400','450');time.sleep(1)
 root=snap('after-send')
 text=ET.tostring(root,encoding='unicode')
 if 'AI analysis failed' in text or 'AI interpretation' in text:break
attempt_count=attempts()
assert attempt_count == 1, f'expected one attempt, got {attempt_count}'
(out/'ai-result-tree.xml').write_text(text)
(out/'synthetic-request-diagnostics.txt').write_text(adb('logcat','-d','-s','WABI_TEST:I','*:S'))
(out/'consent-boundaries.json').write_text(json.dumps({'cancel_no_attempt':True,'send_one_attempt':True,'synthetic_fixture':True,'production_modified':True},indent=2))
if 'AI analysis failed' in text: raise AssertionError('Application reported AI analysis failed; provider response and cause not yet established')
assert 'AI interpretation' in text, 'No AI result visible before deadline'
(out/'acceptance-ai.json').write_text(json.dumps({'synthetic_fixture':True,'facts':expected,'cancel_no_attempt':True,'send_one_attempt':True,'real_provider_result_visible':True,'production_build_modified_for_fixture':True},indent=2))
print('PASS: synthetic Android consent canceled without attempt; confirmed send produced one real-provider response')
