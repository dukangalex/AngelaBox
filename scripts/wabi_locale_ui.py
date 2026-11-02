#!/usr/bin/env python3
"""Synthetic provider; real Android app context/renderer, three application locales."""
import json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
P='io.chainbox.app'; out=Path('wabi-evidence');out.mkdir(exist_ok=True)
def adb(*a): return subprocess.check_output(['adb',*a],text=True,timeout=60)
def shell(*a): return adb('shell',*a)
def snap(tag):
 for attempt in range(5):
  dump=shell('uiautomator','dump','/sdcard/window.xml')
  if 'UI hierchary dumped to' in dump or 'UI hierarchy dumped to' in dump:
   x=shell('cat','/sdcard/window.xml'); (out/(tag+'.xml')).write_text(x)
   (out/(tag+'.png')).write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p'],timeout=60));return ET.fromstring(x[x.index('<hierarchy'):])
  time.sleep(2)
 raise AssertionError('UI hierarchy unavailable after retries: '+tag)
def tap(n):
 b=list(map(int,re.findall(r'\d+',n.attrib['bounds'])));shell('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(2)
def find(text,tag,scroll=0):
 size=shell('wm','size').strip().split()[-1]; width,height=map(int,size.split('x'))
 for i in range(scroll+1):
  root=snap(tag+str(i)); nodes=[n for n in root.iter('node') if text in n.attrib.get('text','') or text==n.attrib.get('content-desc')]
  if nodes:return nodes[0]
  if i<scroll:shell('input','swipe',str(width//2),str(round(height*.82)),str(width//2),str(round(height*.25)),'450');time.sleep(1)
 raise AssertionError('UI control absent: '+text)
def dialog_confirm(tag, text):
 root=snap(tag)
 nodes=[n for n in root.iter('node') if n.attrib.get('text')==text]
 assert len(nodes)==2, 'expected title and action in dialog: '+repr([(n.attrib.get('text'),n.attrib.get('bounds')) for n in nodes])
 tap(nodes[-1])
def local(name):
 try:return shell('run-as',P,'cat','no_backup/inspection-'+name+'.json').strip()
 except subprocess.CalledProcessError:return ''
results=[]
for locale,tools,entry,run,review,expected,priority in [
 ('en-US','Tools','App inspector','Run local checks','Review evidence for AI','AI selections are for reference only','Local evidence to review first: VPN service'),
 ('zh-CN','工具','App 巡检','立即本地巡检','预览 AI 分析数据','AI 选择仅供参考','本地证据优先复核：VPN 服务'),
 ('zh-TW','工具','App 巡檢','立即本機巡檢','預覽 AI 分析資料','AI 選擇僅供參考','本地證據優先複核：VPN 服務')]:
 shell('pm','clear',P)
 sdk=int(shell('getprop','ro.build.version.sdk').strip())
 if sdk >= 33:
  shell('cmd','locale','set-app-locales',P,'--user','0','--locales',locale)
  actual=shell('cmd','locale','get-app-locales',P,'--user','0').strip()
  assert locale in actual, 'system app locale mismatch: '+actual
  (out/(locale+'-platform-locale.txt')).write_text(actual)
 else:
  shell('run-as',P,'mkdir','-p','files'); shell(f"run-as {P} sh -c 'echo {locale} > files/wabi-test-locale'")
 shell('monkey','-p',P,'-c','android.intent.category.LAUNCHER','1');time.sleep(7)
 root=snap(locale+'-launch')
 for n in root.iter('node'):
  if n.attrib.get('text') in ('OK','Continue','Got it','确定','確定'):tap(n);break
 tap(find(tools,locale+'-tools',4));tap(find(entry,locale+'-entry',5))
 assert local('ai'), 'synthetic config absent'
 tap(find(run,locale+'-run',5));dialog_confirm(locale+'-run-dialog',run)
 for i in range(60):
  if local('result'):break
  time.sleep(2)
 report=json.loads(local('result')); assert report['facts']==[{'id':'network','state':'OBSERVED','code':'present'},{'id':'service','state':'OBSERVED','code':'stopped'},{'id':'TUNNEL','state':'NOT_CHECKED','code':'not_started'},{'id':'DNS','state':'UNKNOWN','code':'canary_not_resolved'}]
 # A local-only summary must render actual bounded observations without invoking the transport.
 (out/(locale+'-pre-summary-epoch.txt')).write_text(adb('logcat','-d','-s','WABI_EPOCH:I','*:S'))
 adb('logcat','-c')
 local_label={'en-US':'Local evidence summary (no AI)','zh-CN':'本地证据摘要（不使用 AI）','zh-TW':'本地證據摘要（不使用 AI）'}[locale]
 tap(find(local_label,locale+'-local-button',5))
 local_node=find(priority,locale+'-local-result',12); local_text=local_node.attrib.get('text','')
 assert all(v in local_text for v in ('present','stopped','not_started','canary_not_resolved')), repr(local_text)
 assert expected not in local_text, repr(local_text)
 logs=adb('logcat','-d','-s','WABI_TEST:I','*:S')
 assert 'AI_REQUEST_ATTEMPT' not in logs, logs
 # Navigate back to Tools and reopen the inspector at the top. Long reverse swipes
 # previously left this screen on both emulators; verify the actual destination.
 shell('input','keyevent','KEYCODE_BACK'); time.sleep(2)
 tap(find(entry,locale+'-reenter-inspector',5))
 tap(find(review,locale+'-review',5)); send_label={'en-US':'Send this evidence once','zh-CN':'仅发送本次证据','zh-TW':'僅傳送本次證據'}[locale]; tap(find(send_label,locale+'-send-dialog',3))
 node=find(expected,locale+'-result',12); text=node.attrib.get('text',''); assert expected in text and priority in text, repr(text)
 assert {'en-US':'not priority','zh-CN':'不分优先级','zh-TW':'不分優先順序'}[locale] in text, repr(text)
 assert all(v in text for v in ('present','stopped','not_started','canary_not_resolved')), repr(text)
 assert text.index(priority) < text.index({'en-US':'Local observations from this run','zh-CN':'本次本地记录','zh-TW':'本次本地紀錄'}[locale]), repr(text)
 if locale=='en-US': assert not re.search(r'[\u4e00-\u9fff]',text), repr(text)
 if locale=='zh-TW': assert '採集時未運行' in text and '活动网络' not in text,repr(text)
 results.append({'locale':locale,'result_text':text,'expected_and_priority':True})
(out/'locale-results.json').write_text(json.dumps({'synthetic_transport':True,'external_provider_requests':False,'locale_set_via_test_only_appcompat_hook':True,'production_source_base':'a570811','locales':results},ensure_ascii=False,indent=2))
print('PASS: real Android UI renders synthetic evidence in each app locale; no external provider')
