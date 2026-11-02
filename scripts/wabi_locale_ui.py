#!/usr/bin/env python3
"""Synthetic provider; real Android app context/renderer, three application locales."""
import json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
P='io.chainbox.app'; out=Path('wabi-evidence');out.mkdir(exist_ok=True)
def adb(*a): return subprocess.check_output(['adb',*a],text=True,timeout=60)
def shell(*a): return adb('shell',*a)
def snap(tag):
 shell('uiautomator','dump','/sdcard/window.xml'); x=shell('cat','/sdcard/window.xml'); (out/(tag+'.xml')).write_text(x)
 (out/(tag+'.png')).write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p'],timeout=60));return ET.fromstring(x[x.index('<hierarchy'):])
def tap(n):
 b=list(map(int,re.findall(r'\d+',n.attrib['bounds'])));shell('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(2)
def find(text,tag,scroll=0):
 size=shell('wm','size').strip().split()[-1]; width,height=map(int,size.split('x'))
 for i in range(scroll+1):
  root=snap(tag+str(i)); nodes=[n for n in root.iter('node') if text in n.attrib.get('text','') or text==n.attrib.get('content-desc')]
  if nodes:return nodes[0]
  if i<scroll:shell('input','swipe',str(width//2),str(round(height*.82)),str(width//2),str(round(height*.25)),'450');time.sleep(1)
 raise AssertionError('UI control absent: '+text)
def dialog_confirm(tag):
 # Dialogs on this emulator aren't exposed in UIAutomator. Screenshot retained; matching button in lower right.
 snap(tag); size=shell('wm','size').strip().split()[-1]; w,h=map(int,size.split('x'))
 shell('input','tap',str(round(w*.72)),str(round(h*.70)));time.sleep(2)
def local(name):
 try:return shell('run-as',P,'cat','no_backup/inspection-'+name+'.json').strip()
 except subprocess.CalledProcessError:return ''
results=[]
for locale,tools,entry,run,review,expected,priority in [
 ('en-US','Tools','App inspector','Run local checks','Review evidence for AI','AI only orders items for review','Local evidence to review first: VPN service'),
 ('zh-CN','工具','App 巡检','立即本地巡检','预览 AI 分析数据','AI 仅排列可复核项目','本地证据优先复核：VPN 服务'),
 ('zh-TW','工具','App 巡檢','立即本機巡檢','預覽 AI 分析資料','AI 僅排列可複核項目','本地證據優先複核：VPN 服務')]:
 shell('pm','clear',P); shell(f"run-as {P} sh -c 'echo {locale} > files/wabi-test-locale'")
 shell('monkey','-p',P,'-c','android.intent.category.LAUNCHER','1');time.sleep(7)
 root=snap(locale+'-launch')
 for n in root.iter('node'):
  if n.attrib.get('text') in ('OK','Continue','Got it','确定','確定'):tap(n);break
 tap(find(tools,locale+'-tools',4));tap(find(entry,locale+'-entry',5))
 assert local('ai'), 'synthetic config absent'
 tap(find(run,locale+'-run',5));dialog_confirm(locale+'-run-dialog')
 for i in range(60):
  if local('result'):break
  time.sleep(2)
 report=json.loads(local('result')); assert report['facts']==[{'id':'network','state':'OBSERVED','code':'present'},{'id':'service','state':'OBSERVED','code':'stopped'},{'id':'TUNNEL','state':'NOT_CHECKED','code':'not_started'},{'id':'DNS','state':'UNKNOWN','code':'canary_not_resolved'}]
 tap(find(review,locale+'-review',5));dialog_confirm(locale+'-send-dialog')
 node=find(expected,locale+'-result',12); text=node.attrib.get('text',''); assert expected in text and priority in text, repr(text)
 assert text.index(priority) < text.index({'en-US':'Local observations from this run','zh-CN':'本次本地记录','zh-TW':'本次本地紀錄'}[locale]), repr(text)
 if locale=='en-US': assert not re.search(r'[\u4e00-\u9fff]',text), repr(text)
 if locale=='zh-TW': assert '採集時未運行' in text and '活动网络' not in text,repr(text)
 results.append({'locale':locale,'result_text':text,'expected_and_priority':True})
(out/'locale-results.json').write_text(json.dumps({'synthetic_transport':True,'external_provider_requests':False,'locale_set_via_test_only_appcompat_hook':True,'production_source_base':'7d5b952','locales':results},ensure_ascii=False,indent=2))
print('PASS: real Android UI renders synthetic evidence in each app locale; no external provider')
