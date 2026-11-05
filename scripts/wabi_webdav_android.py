#!/usr/bin/env python3
"""API34 local backup acceptance; production UI with isolated synthetic profile only."""
import os,json,re,subprocess,time,urllib.request,socket,urllib.parse,xml.etree.ElementTree as ET,sqlite3,hashlib,zipfile,tarfile,io
from pathlib import Path
P='io.chainbox.app';out=Path('wabi-evidence');out.mkdir(exist_ok=True)
def adb(*a):return subprocess.check_output(['adb',*a],text=True,timeout=60)
def shell(*a):return adb('shell',*a)
def snap(tag,photo=True):
 shell('uiautomator','dump','/sdcard/window.xml');x=shell('cat','/sdcard/window.xml');(out/(tag+'.xml')).write_text(x)
 if photo:(out/(tag+'.png')).write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p'],timeout=30))
 return ET.fromstring(x[x.index('<hierarchy'):])
def tap(n,delay=1):
 b=list(map(int,re.findall(r'\d+',n.attrib['bounds'])));shell('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(delay)
def find(text,tag,scroll=0):
 w,h=map(int,shell('wm','size').strip().split()[-1].split('x'))
 for i in range(scroll+1):
  r=snap(tag+str(i));ns=[n for n in r.iter('node') if text in n.get('text','') or text==n.get('content-desc')]
  if ns:return ns[0]
  if i<scroll:shell('input','swipe',str(w//2),str(int(h*.8)),str(w//2),str(int(h*.25)),'400')
 raise AssertionError('Missing UI: '+text)
def click(text,tag,scroll=0):tap(find(text,tag,scroll))
def launch():
 shell('am','force-stop',P);shell('monkey','-p',P,'-c','android.intent.category.LAUNCHER','1');time.sleep(5)
vpn_checks={}
def proxy_stats():
 with urllib.request.urlopen('http://127.0.0.1:18972/stats',timeout=5) as r:return json.load(r)
def start_vpn(tag):
 for depth in range(5):
  r=snap(tag+'-vpn-navigation'+str(depth))
  tabs=[n for n in r.iter('node') if n.get('text')=='Dashboard']
  if tabs:tap(tabs[0]);break
  shell('input','keyevent','4');time.sleep(1)
 else:raise AssertionError('Dashboard unavailable')
 click('Start',tag+'-vpn-start')
 for i in range(12):
  r=snap(tag+'-vpn-running'+str(i));texts=[n.get('text','') for n in r.iter('node')]
  if 'Connection request' in texts or 'Deprecated Warning' in texts:
   click('OK',tag+'-vpn-dialog');time.sleep(3);continue
  allows=[n for n in r.iter('node') if n.get('text') in ('Allow','ALLOW')]
  if allows:tap(allows[0]);continue
  if any(n.get('content-desc')=='Stop' for n in r.iter('node')):break
  time.sleep(2)
 else:raise AssertionError('VPN did not reach running UI')
 addr=shell('ip','addr');assert '172.19.0.1' in addr,addr
 connectivity=shell('dumpsys','connectivity');assert 'TRANSPORT_VPN' in connectivity or 'Transports: VPN' in connectivity,connectivity[-5000:]
 (out/(tag+'-vpn-addresses.txt')).write_text(addr)
 (out/(tag+'-vpn-connectivity.txt')).write_text(connectivity)
 vpn_checks[tag]={'tun_up':True,'vpn_transport_observed':True,'proxy_before':proxy_stats()}
 shell('input','keyevent','4') if False else None

def route_after(tag):
 state=proxy_stats();before=vpn_checks[tag]['proxy_before'];vpn_checks[tag]['proxy_after']=state
 assert state['connect_successes']>before['connect_successes'],(tag,before,state)
 assert '172.19.0.1' in shell('ip','addr'),tag
 vpn_checks[tag]['tun_still_up']=True

def backup_screen(tag):
 launch()
 start_vpn(tag)
 r=snap(tag+'-current')
 # Android restores the last navigation route after process recreation.
 if any(n.get('text')=='备份与恢复' for n in r.iter('node')):return
 click('Settings',tag+'-settings');click('Backup and Restore',tag+'-backup',4)
def pick_download(filename,tag):
 # DocumentsUI defaults to Recent. Explicitly use the Downloads root.
 r=snap(tag+'-picker')
 roots=[n for n in r.iter('node') if n.get('content-desc')=='Show roots']
 if roots:
  tap(roots[0]);click('Downloads',tag+'-downloads')
 click(filename,tag+'-file',5)
 # Some DocumentsUI versions select then require Open; only click observed button.
 time.sleep(.5)
 r=snap(tag+'-after-select')
 opens=[n for n in r.iter('node') if n.get('text','').upper()=='OPEN' and n.get('package','').endswith('documentsui')]
 if opens:tap(opens[0],0)
def db_snapshot(tag):
 shell('am','force-stop',P)
 folder=out/tag;folder.mkdir(exist_ok=True)
 blob=subprocess.check_output(['adb','exec-out','run-as',P,'tar','-cf','-','databases'],timeout=30)
 with tarfile.open(fileobj=io.BytesIO(blob)) as archive:archive.extractall(folder,filter='data')
 db=sqlite3.connect(folder/'databases/profiles.db');merged=sqlite3.connect(out/(tag+'.db'));db.backup(merged);merged.close();rows=db.execute('select id,name,typed from profiles order by id').fetchall();db.close()
 return [{'id':i,'name':n,'typed_sha256':hashlib.sha256(t).hexdigest()} for i,n,t in rows]

launch()
r=snap('first-launch');decline=[n for n in r.iter('node') if n.get('text') in ('No thanks','No, thanks')]
if decline:tap(decline[0])
uri='sing-box://import-remote-profile?url=https%3A%2F%2Fraw.githubusercontent.com%2Fdukangalex%2FAngelaBox%2F2c8013aba285be459a65c85747fa2cb224ffb770%2Fscripts%2Fwabi_egress_allow.json#Backup-Fixture'
shell('am','start','-a','android.intent.action.VIEW','-d',uri,'-p',P);time.sleep(3)
click('OK','import-confirm');time.sleep(2)
r=snap('after-import-confirm');decline=[n for n in r.iter('node') if n.get('text') in ('No thanks','No, thanks')]
if decline:tap(decline[0])
click('Create','import-create');time.sleep(15)
before=db_snapshot('before-backup');assert len(before)==1 and before[0]['name']=='Backup-Fixture',before
# Synthetic test-only password; no real user credential is read or used.
password='WabiFixture2026'

def fill_edits(values,tag):
 for i,value in enumerate(values):
  r=snap(tag+str(i));edits=[n for n in r.iter('node') if n.get('class')=='android.widget.EditText'];assert len(edits)==len(values),(len(edits),len(values))
  tap(edits[i]);shell('input','keycombination','113','29');shell('input','text',value);shell('input','keyevent','4')
def configure(tag):
 backup_screen(tag);click('编辑',tag+'-editor');fill_edits([os.environ['WABI_HTTPS_BASE']+'/dav','fixture','WabiDav2026','backup.zip'],tag+'-fill');click('保存',tag+'-save');time.sleep(10)
configure('dav-config')
click('备份数据到 WebDAV','dav-export');fill_edits([password,password],'dav-export-pw');click('Continue','dav-export-confirm');time.sleep(20)
snap('dav-upload-result');route_after('dav-config')
sealed=(out/'webdav-upload.bin').read_bytes();assert sealed[:5]==b'ABKE\x01'
events=[json.loads(x) for x in (out/'webdav-events.jsonl').read_text().splitlines()];assert any(x['method']=='PUT' and x['code']==201 for x in events)
# Modify a synthetic profile only; restore must recover the backed up value.
db_snapshot('before-mutation');mutated=out/'before-mutation.db';db=sqlite3.connect(mutated);db.execute("update profiles set name='Changed-after-WebDAV-backup'");db.commit();db.close()
adb('push',str(mutated),'/data/local/tmp/wabi-mutated.db');shell('run-as',P,'cp','/data/local/tmp/wabi-mutated.db','databases/profiles.db');shell('run-as',P,'rm','-f','databases/profiles.db-wal','databases/profiles.db-shm')
changed=db_snapshot('changed');assert changed[0]['name']=='Changed-after-WebDAV-backup'
backup_screen('dav-restore');click('通过 WebDAV 恢复数据','dav-restore-click');time.sleep(15)
fill_edits([password],'dav-restore-pw');click('Restore','dav-restore-submit');time.sleep(20)
route_after('dav-restore')
after=db_snapshot('after-webdav-restore');assert after[0]['name']=='Backup-Fixture' and after[0]['id']==before[0]['id'],after
# Invalid/cut responses must not alter data and must leave no restore scratch.
checks={}
for mode in ['invalid','truncated']:
 (out/'webdav-mode').write_text(mode)
 backup_screen(mode);click('通过 WebDAV 恢复数据',mode+'-click');time.sleep(40 if mode=='truncated' else 10);snap(mode+'-result')
 route_after(mode)
 scratch=shell('run-as',P,'sh','-c',"'if test -e cache/chainbox-restore.zip; then wc -c < cache/chainbox-restore.zip; else echo ABSENT; fi'").strip()
 current=db_snapshot('after-'+mode);assert current==after,(mode,current,after)
 checks[mode]={'data_unchanged':True,'scratch':scratch}
(out/'webdav-summary.json').write_text(json.dumps({'roundtrip_passed':True,'checks':checks,'product_sha':'613c77d2efb5c07662b568db116820e34b651074','before':before,'changed':changed,'after':after,'encrypted_size':len(sealed),'vpn_checks':vpn_checks,'scope':'API34 production UI, synthetic HTTPS WebDAV fixture, actual App VPN/TUN on through controlled SOCKS exit','not_covered':['real provider','physical device','VPN interruption during WebDAV','all backup combinations']},indent=2))
assert all(x['scratch']=='ABSENT' for x in checks.values()),checks
print('WEBDAV ACCEPTANCE PASSED')
