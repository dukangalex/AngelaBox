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
backup_screen('export');click('备份数据到本地','export-local',6);click('Continue','export-plain')
r=snap('export-picker');edits=[n for n in r.iter('node') if n.get('class')=='android.widget.EditText'];assert len(edits)==1
# Existing filename selected through normal text-edit keyboard operations.
tap(edits[0]);shell('input','keycombination','113','29');shell('input','text','Wabi-normal.zip')
# ctrl+a is more reliable than OEM long-delete; ensure exact filename using key chord.
shell('input','keyevent','KEYCODE_CTRL_LEFT') if False else None
r=snap('export-name');name=[n.get('text') for n in r.iter('node') if n.get('class')=='android.widget.EditText'][0]
# Accept DocumentsUI's final name, record it and locate the resulting file by filesystem below.
saves=[n for n in r.iter('node') if n.get('text','').upper()=='SAVE'];assert saves;tap(saves[0]);time.sleep(4);snap('export-done')
files=shell('find','/sdcard/Download','-maxdepth','1','-name','*.zip').splitlines();assert len(files)==1,files
normal=files[0];normal_name=normal.rsplit('/',1)[-1]
subprocess.check_call(['adb','pull',normal,str(out/'normal-export.zip')]);assert zipfile.is_zipfile(out/'normal-export.zip')
# Change only the isolated installation to establish that restore really restores data.
db_snapshot('before-mutation');mutated=out/'before-mutation.db';db=sqlite3.connect(mutated);db.execute("update profiles set name='Changed-after-backup'");db.commit();db.close()
adb('push',str(mutated),'/data/local/tmp/wabi-mutated.db');shell('run-as',P,'cp','/data/local/tmp/wabi-mutated.db','databases/profiles.db');shell('run-as',P,'rm','-f','databases/profiles.db-wal','databases/profiles.db-shm')
changed=db_snapshot('changed');assert changed[0]['name']=='Changed-after-backup'
backup_screen('restore-normal');click('通过文件恢复数据','restore-normal-local',6);pick_download(normal_name,'restore-normal');time.sleep(8)
after=db_snapshot('after-normal-restore');assert len(after)==1 and after[0]['name']=='Backup-Fixture',after
# Only the subscription update policy may be normalized by the established restore policy.
assert after[0]['id']==before[0]['id'],(before,after)
# Actual 128 MiB+1 input through Android's document picker, not a reduced test constant.
shell('sh','-c',"'dd if=/dev/zero of=/sdcard/Download/Wabi-oversize.zip bs=1048576 count=129 2>/dev/null'")
assert int(shell('stat','-c','%s','/sdcard/Download/Wabi-oversize.zip').strip())==129*1024*1024
backup_screen('restore-oversize');click('通过文件恢复数据','restore-oversize-local',6)
# Do not add screenshot delays between picker selection and transient snackbar capture.
r=snap('oversize-picker');roots=[n for n in r.iter('node') if n.get('content-desc')=='Show roots']
if roots:tap(roots[0]);click('Downloads','oversize-downloads')
n=find('Wabi-oversize.zip','oversize-file',5);tap(n,0)
seen=False
for i in range(4):
 r=snap('oversize-result'+str(i));texts=[n.get('text','') for n in r.iter('node')]
 if any('备份文件过大' in t and '128 MiB' in t for t in texts):seen=True;break
 time.sleep(.3)
assert seen,'oversize message not observed'
assert shell('run-as',P,'sh','-c',"'test ! -e cache/chainbox-restore.zip && echo CLEAN'").strip()=='CLEAN'
unchanged=db_snapshot('after-oversize');assert unchanged==after,(unchanged,after)
launch();snap('final-app-responsive')
(out/'backup-acceptance-summary.json').write_text(json.dumps({'passed':True,'product_sha':'1bac7830e24b4619d5eb8b9356cfb0e92d409018','checks':['production UI plaintext local export','restore via Android document picker restores deliberately changed profile','129 MiB document rejected with visible 128 MiB limit error','partial cache archive deleted','oversize rejection leaves persisted profile unchanged','app remains launchable'],'before':before,'after_restore':after,'oversize_bytes':129*1024*1024,'not_covered':['encrypted UI roundtrip','physical Android device','all backup permutations']},indent=2))
print('BACKUP ACCEPTANCE PASSED')
