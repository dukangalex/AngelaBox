#!/usr/bin/env python3
"""API34 local backup acceptance; production UI with isolated synthetic profile only."""
import json,re,subprocess,time,xml.etree.ElementTree as ET,sqlite3,hashlib,zipfile,tarfile,io
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
def backup_screen(tag):
 launch()
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
backup_screen('export-encrypted');click('备份数据到本地','export-local',6)
r=snap('export-password-dialog');edits=[n for n in r.iter('node') if n.get('class')=='android.widget.EditText'];assert len(edits)==2
for n in edits:tap(n);shell('input','text',password)
shell('input','keyevent','4');click('Continue','export-encrypted-continue')
r=snap('export-picker');edits=[n for n in r.iter('node') if n.get('class')=='android.widget.EditText'];assert len(edits)==1
tap(edits[0]);shell('input','keycombination','113','29');shell('input','text','Wabi-encrypted.zip')
r=snap('export-name');saves=[n for n in r.iter('node') if n.get('text','').upper()=='SAVE'];assert saves;tap(saves[0]);time.sleep(15);snap('encrypted-export-done')
files=shell('find','/sdcard/Download','-maxdepth','1','-name','*.zip').splitlines();assert len(files)==1,files
normal=files[0];normal_name=normal.rsplit('/',1)[-1]
subprocess.check_call(['adb','pull',normal,str(out/'encrypted-export.zip')])
sealed=(out/'encrypted-export.zip').read_bytes();assert sealed[:5]==b'ABKE\x01' and not zipfile.is_zipfile(out/'encrypted-export.zip')
# Mutate only the synthetic emulator database, then prove wrong-password rejection.
db_snapshot('before-mutation');mutated=out/'before-mutation.db';db=sqlite3.connect(mutated);db.execute("update profiles set name='Changed-after-encrypted-backup'");db.commit();db.close()
adb('push',str(mutated),'/data/local/tmp/wabi-mutated.db');shell('run-as',P,'cp','/data/local/tmp/wabi-mutated.db','databases/profiles.db');shell('run-as',P,'rm','-f','databases/profiles.db-wal','databases/profiles.db-shm')
changed=db_snapshot('changed');assert changed[0]['name']=='Changed-after-encrypted-backup'
backup_screen('restore-wrong');click('通过文件恢复数据','restore-wrong-local',6);pick_download(normal_name,'restore-wrong');time.sleep(2)
r=snap('wrong-password-dialog');edits=[n for n in r.iter('node') if n.get('class')=='android.widget.EditText'];assert len(edits)==1
tap(edits[0]);shell('input','text','IncorrectFixture');shell('input','keyevent','4');click('Restore','wrong-password-submit');time.sleep(15)
r=snap('wrong-password-error');assert any('Incorrect password' in n.get('text','') for n in r.iter('node'))
wrong=db_snapshot('after-wrong-password');assert wrong==changed,(wrong,changed)
backup_screen('restore-encrypted');click('通过文件恢复数据','restore-encrypted-local',6);pick_download(normal_name,'restore-encrypted');time.sleep(2)
r=snap('correct-password-dialog');edits=[n for n in r.iter('node') if n.get('class')=='android.widget.EditText'];assert len(edits)==1
tap(edits[0]);shell('input','text',password);shell('input','keyevent','4');click('Restore','correct-password-submit');time.sleep(20)
after=db_snapshot('after-encrypted-restore');assert len(after)==1 and after[0]['name']=='Backup-Fixture' and after[0]['id']==before[0]['id'],after
assert shell('run-as',P,'sh','-c',"'test ! -e cache/chainbox-backup-plain.zip && test ! -e cache/chainbox-restore-plain.zip && echo CLEAN'").strip()=='CLEAN'
launch();snap('final-app-responsive')
(out/'encrypted-backup-summary.json').write_text(json.dumps({'passed':True,'product_sha':'1bac7830e24b4619d5eb8b9356cfb0e92d409018','checks':['production UI encrypted local export','ABKE v1 container not plaintext ZIP','wrong password visible rejection','wrong password persisted profiles unchanged','correct password restores deliberately modified synthetic profile','plaintext scratch files absent','app remains launchable'],'before':before,'changed':changed,'after_wrong':wrong,'after_restore':after,'encrypted_size':len(sealed),'not_covered':['physical Android device','WebDAV','all backup permutations']},indent=2))
print('ENCRYPTED BACKUP ACCEPTANCE PASSED')
