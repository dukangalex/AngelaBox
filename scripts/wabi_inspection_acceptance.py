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
 for i in range(scroll+1):
  root=snap(tag+str(i)); nodes=[n for n in root.iter('node') if text in n.attrib.get('text','') or text==n.attrib.get('content-desc')]
  if nodes:return nodes[0]
  shell('input','swipe','500','1600','500','600','350');time.sleep(1)
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
launch(); inspector()
assert local('settings') in ('','{}'), 'fresh install must be opt-out'
assert not local('ai'), 'no model on fresh install'
root=snap('initial'); assert 'You decide' in ET.tostring(root,encoding='unicode')
button('Run local checks','probe-consent'); snap('probe-dialog');assert not local('result'),'opening consent must not create report'
tap_dialog_button('Cancel');assert not local('result'),'cancel must not create report'
# Enable periodic local-only observation and verify persisted flag across a process restart.
root=snap('auto'); switches=[n for n in root.iter('node') if n.attrib.get('checkable')=='true']; assert switches
# Switch semantics may expose role via checkable; use exact bounds from observed tree.
tap(switches[0]);time.sleep(3);assert json.loads(local('settings'))['enabled'] is True
shell('am','force-stop',P);launch();inspector();assert json.loads(local('settings'))['enabled'] is True
root=snap('auto-after-restart');switches=[n for n in root.iter('node') if n.attrib.get('checkable')=='true'];tap(switches[0]);time.sleep(2);assert json.loads(local('settings'))['enabled'] is False
# An expressly accepted local diagnostic (not an AI request).
button('Run local checks','probe2');snap('probe-dialog2');tap_dialog_button('Run local checks')
for i in range(60):
 if local('result'):break
 time.sleep(2)
report=json.loads(local('result'));assert len(report['facts'])>=2
assert not local('ai'), 'probes must not auto-configure AI'
snap('local-report');button('Clear local report','clear');assert not local('result')
(out/'acceptance.json').write_text(json.dumps({'default_off':True,'probe_consent_cancel_no_report':True,'setting_restart_persistence':True,'disable_saved':True,'local_probe_report':report,'clear_report':True,'live_ai_not_tested':True},indent=2))
print('PASS: consent, opt-out, saved settings, local diagnostics and clearing; live AI not tested')
