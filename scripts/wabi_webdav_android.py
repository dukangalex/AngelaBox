#!/usr/bin/env python3
"""API34 local backup acceptance; production UI with isolated synthetic profile only."""
import os,json,re,subprocess,time,urllib.request,socket,urllib.parse,xml.etree.ElementTree as ET,sqlite3,hashlib,zipfile,tarfile,io,csv
from pathlib import Path
P='io.chainbox.app';out=Path('wabi-evidence');out.mkdir(exist_ok=True)
def adb(*a):return subprocess.check_output(['adb',*a],text=True,timeout=60)
def shell(*a):return adb('shell',*a)
def snap(tag,photo=True):
 # Never consume a hierarchy left by an earlier failed idle-wait/dump.
 failures=[]
 for attempt in range(3):
  remote=f'/sdcard/wabi-{time.monotonic_ns()}.xml'
  try:
   subprocess.check_output(['adb','shell','uiautomator','dump',remote],text=True,timeout=15)
   x=shell('cat',remote)
   root=ET.fromstring(x[x.index('<hierarchy'):])
   assert root.tag=='hierarchy' and list(root.iter('node')), 'Empty UI hierarchy'
   (out/(tag+'.xml')).write_text(x)
   if photo:(out/(tag+'.png')).write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p'],timeout=15))
   return root
  except (subprocess.SubprocessError, ValueError, ET.ParseError, AssertionError) as e:
   failures.append(repr(e));time.sleep(.3)
  finally:
   subprocess.run(['adb','shell','rm','-f',remote],capture_output=True,timeout=5)
 (out/(tag+'-dump-failures.json')).write_text(json.dumps(failures,indent=2))
 raise AssertionError(('No fresh UI hierarchy',tag,failures))

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
 connectivity=shell('dumpsys','connectivity');assert 'TRANSPORT_VPN' in connectivity or re.search(r'\bTransports:[^\n]*\bVPN\b',connectivity),connectivity[-5000:]
 (out/(tag+'-vpn-addresses.txt')).write_text(addr)
 (out/(tag+'-vpn-connectivity.txt')).write_text(connectivity)
 vpn_checks[tag]={'tun_up':True,'vpn_transport_observed':True,'proxy_before':proxy_stats()}
 shell('input','keyevent','4') if False else None

def route_after(tag, expected_host, require_tun=True):
 state=proxy_stats();before=vpn_checks[tag]['proxy_before'];vpn_checks[tag]['proxy_after']=state
 # Persist measurements before any assertion so a later failure still leaves evidence.
 (out/(tag+'-proxy-stats.json')).write_text(json.dumps({'before':before,'after':state},indent=2))
 new_targets=state.get('targets',[])[len(before.get('targets',[])):]
 matched=[x for x in new_targets if x.get('host','').lower()==expected_host.lower() and x.get('port')==443]
 assert matched,(tag,expected_host,before,state)
 vpn_checks[tag]['webdav_host_via_socks']=matched
 if require_tun:
  assert '172.19.0.1' in shell('ip','addr'),tag
  vpn_checks[tag]['tun_still_up']=True
 else:
  # Successful restore deliberately soft-relaunches the app and stops BoxService;
  # its VPN may disconnect after the WebDAV GET has completed.
  vpn_checks[tag]['post_restore_tun_check']='not_required_after_successful_soft_relaunch'

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
  # CTRL+A is not reliably dispatched to Compose by shell keycombination.
  # Move to the end and delete the observed content, then verify exact value.
  tap(edits[i]);shell('input','keyevent','123')
  shell('input','keyevent',*(['67']*max(256,len(edits[i].get('text',''))+16)))
  shell('input','text',value);shell('input','keyevent','4')
  verified=snap(tag+str(i)+'-verified')
  fields=[n for n in verified.iter('node') if n.get('class')=='android.widget.EditText']
  assert len(fields)==len(values),(tag,i,len(fields))
  # Password controls mask their UI value; the synthetic peer checks auth.
  if fields[i].get('password')!='true':
   assert fields[i].get('text','')==value,(tag,i,'field value mismatch',fields[i].get('text',''),value)
def configure(tag):
 backup_screen(tag);click('编辑',tag+'-editor');fill_edits([os.environ['WABI_HTTPS_BASE']+'/dav','fixture','WabiDav2026','backup.zip'],tag+'-fill');click('保存',tag+'-save');time.sleep(10)
configure('dav-config')

# Editing and cancel must not leak draft values to the synthetic server.
def read_events():
 p=out/'webdav-events.jsonl'
 return [json.loads(x) for x in p.read_text().splitlines()] if p.exists() else []
start=len(read_events())
click('编辑','draft-open')
fill_edits([os.environ['WABI_HTTPS_BASE']+'/draftonly','draftuser','draftpass','draftonly.zip'],'draft-fill')
time.sleep(25)
draft_events=read_events()[start:]
(out/'editor-draft-events.json').write_text(json.dumps(draft_events,indent=2))
assert not any('draftonly' in e['path'] or e['code']==401 for e in draft_events),draft_events
click('取消','draft-cancel');click('编辑','draft-reopen')
r=snap('draft-reopened');edits=[n.get('text','') for n in r.iter('node') if n.get('class')=='android.widget.EditText']
assert len(edits)==4 and edits[0]==os.environ['WABI_HTTPS_BASE']+'/dav' and edits[1]=='fixture' and edits[3]=='backup.zip',edits
click('取消','draft-recancel')
# Saving a changed endpoint must actually launch a probe for that endpoint.
start=len(read_events());click('编辑','save-open')
fill_edits([os.environ['WABI_HTTPS_BASE']+'/savedprobe','fixture','WabiDav2026','backup.zip'],'save-fill')
click('保存','save-confirm')
for _ in range(50):
 saved_events=read_events()[start:]
 if any('/savedprobe/' in e['path'] and e['method']=='HEAD' and e['code']==200 for e in saved_events):break
 time.sleep(.5)
else:raise AssertionError(('No saved endpoint probe',saved_events))
(out/'editor-saved-events.json').write_text(json.dumps(saved_events,indent=2))
# Restore original configuration for the existing encrypted backup tests.
click('编辑','restore-editor');fill_edits([os.environ['WABI_HTTPS_BASE']+'/dav','fixture','WabiDav2026','backup.zip'],'restore-fill');click('保存','restore-save');time.sleep(8)
print('EDITOR DRAFT AND SAVE ACCEPTANCE PASSED')

def notification_words(tag):
 # A busy Compose progress indicator can prevent UIAutomator's idle wait;
 # its failed dump otherwise leaves a stale app hierarchy on /sdcard. Use
 # the current screenshot for this system-UI action, retaining OCR evidence.
 png=out/(tag+'.png')
 png.write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p'],timeout=15))
 tsv=subprocess.check_output(['tesseract',str(png),'stdout','--psm','11','tsv'],text=True,timeout=10)
 (out/(tag+'.tsv')).write_text(tsv)
 return [row for row in csv.DictReader(io.StringIO(tsv),delimiter='\t') if row.get('text','').strip() and float(row.get('conf','-1'))>=35]
def word_center(word):
 return (int(word['left'])+int(word['width'])//2,int(word['top'])+int(word['height'])//2)
def stop_notification(tag):
 # Expanding SystemUI is asynchronous. Observe fresh frames and retry the
 # shade gesture instead of assuming one 400ms delay establishes readiness.
 w,h=map(int,shell('wm','size').strip().split()[-1].split('x'))
 shell('cmd','statusbar','expand-notifications');time.sleep(1)
 for i in range(6):
  words=notification_words(tag+'-notification'+str(i))
  fixture=[w for w in words if 'backup-fixture' in w['text'].lower()]
  stops=[w for w in words if w['text'].strip().lower()=='stop']
  if fixture and stops:
   _,title_y=word_center(fixture[0])
   # Stop must belong to this app notification, not another card above it.
   stops=[x for x in stops if word_center(x)[1]>title_y]
   if stops:
    x,y=word_center(stops[0]);shell('input','tap',str(x),str(y));break
  if fixture:
   _,title_y=word_center(fixture[0])
   shell('input','tap',str(round(w*.86)),str(title_y+12))
  else:
   # Only a shade-opening gesture here; never guess a hidden Stop target.
   shell('cmd','statusbar','expand-notifications')
   shell('input','swipe',str(w//2),'1',str(w//2),str(round(h*.75)),'350')
  time.sleep(.8)
 else:
  (out/(tag+'-notification-state.txt')).write_text(shell('dumpsys','notification'))
  (out/(tag+'-statusbar-state.txt')).write_text(shell('dumpsys','statusbar'))
  raise AssertionError('Fresh fixture notification Stop unavailable')
 stopped=time.monotonic_ns()
 for _ in range(30):
  addr=shell('ip','addr')
  if '172.19.0.1' not in addr:break
  time.sleep(.2)
 else:raise AssertionError('TUN remained after notification Stop')
 (out/(tag+'-post-stop-addresses.txt')).write_text(addr)
 (out/(tag+'-post-stop-connectivity.txt')).write_text(shell('dumpsys','connectivity'))
 shell('cmd','statusbar','collapse');time.sleep(1)
 return stopped


# Start a single long probe while VPN is running and stop during its HEAD.
# Inspect the peer for fallback methods; this does not establish a kill switch.
(out/'webdav-mode').write_text('probe-mid-headers')
prior=read_events();v=proxy_stats();host=urllib.parse.urlsplit(os.environ['WABI_HTTPS_BASE']).hostname
for _ in range(90):
 current=read_events()[len(prior):]
 if any(e['mode']=='probe-mid-headers' and e['method']=='HEAD' and e['code']==0 for e in current):break
 time.sleep(.4)
else:raise AssertionError(('probe HEAD did not begin',current))
new_targets=proxy_stats().get('targets',[])[len(v.get('targets',[])):]
assert any(x.get('host','').lower()==host.lower() and x.get('port')==443 for x in new_targets),new_targets
stopped=stop_notification('probe-midstop')
time.sleep(36)
new=read_events()[len(prior):]
post=[e for e in new if e['mode']=='probe-mid-headers' and e['monotonic_ns']>stopped]
(out/'probe-midstop-summary.json').write_text(json.dumps({'stopped_monotonic_ns':stopped,'events':new,'proxy_targets':new_targets},indent=2))
assert not any(e['method'] in ('OPTIONS','GET') for e in post),post
(out/'webdav-mode').write_text('normal')
backup_screen('after-probe-stop')
print('PROBE FALLBACK INTERRUPTION OBSERVATION PASSED')
click('备份数据到 WebDAV','dav-export');fill_edits([password,password],'dav-export-pw');click('Continue','dav-export-confirm');time.sleep(20)
snap('dav-upload-result');route_after('dav-config',urllib.parse.urlsplit(os.environ['WABI_HTTPS_BASE']).hostname)
sealed=(out/'webdav-upload.bin').read_bytes();assert sealed[:5]==b'ABKE\x01'
events=[json.loads(x) for x in (out/'webdav-events.jsonl').read_text().splitlines()];assert any(x['method']=='PUT' and x['code']==201 for x in events)
# Modify a synthetic profile only; restore must recover the backed up value.
db_snapshot('before-mutation');mutated=out/'before-mutation.db';db=sqlite3.connect(mutated);db.execute("update profiles set name='Changed-after-WebDAV-backup'");db.commit();db.close()
adb('push',str(mutated),'/data/local/tmp/wabi-mutated.db');shell('run-as',P,'cp','/data/local/tmp/wabi-mutated.db','databases/profiles.db');shell('run-as',P,'rm','-f','databases/profiles.db-wal','databases/profiles.db-shm')
changed=db_snapshot('changed');assert changed[0]['name']=='Changed-after-WebDAV-backup'
backup_screen('dav-restore');click('通过 WebDAV 恢复数据','dav-restore-click');time.sleep(15)
fill_edits([password],'dav-restore-pw');click('Restore','dav-restore-submit');time.sleep(20)
route_after('dav-restore',urllib.parse.urlsplit(os.environ['WABI_HTTPS_BASE']).hostname,require_tun=False)
after=db_snapshot('after-webdav-restore');assert after[0]['name']=='Backup-Fixture' and after[0]['id']==before[0]['id'],after
# Invalid/cut responses must not alter data and must leave no restore scratch.
checks={}
for mode in ['invalid','truncated']:
 (out/'webdav-mode').write_text(mode)
 backup_screen(mode);click('通过 WebDAV 恢复数据',mode+'-click');time.sleep(40 if mode=='truncated' else 10);snap(mode+'-result')
 route_after(mode,urllib.parse.urlsplit(os.environ['WABI_HTTPS_BASE']).hostname)
 scratch=shell('run-as',P,'sh','-c',"'if test -e cache/chainbox-restore.zip; then wc -c < cache/chainbox-restore.zip; else echo ABSENT; fi'").strip()
 current=db_snapshot('after-'+mode);assert current==after,(mode,current,after)
 checks[mode]={'data_unchanged':True,'scratch':scratch}
(out/'webdav-baseline-summary.json').write_text(json.dumps({'roundtrip_passed':True,'checks':checks,'product_sha':'aeaef2fcb22d590e9f3f7ab17aae0fdb7b9717a6','before':before,'changed':changed,'after':after,'encrypted_size':len(sealed),'vpn_checks':vpn_checks,'scope':'API34 production UI, synthetic HTTPS WebDAV fixture, actual App VPN/TUN on through controlled SOCKS exit','not_covered':['real provider','physical device','VPN interruption during WebDAV','all backup combinations']},indent=2))
assert all(x['scratch']=='ABSENT' for x in checks.values()),checks
print('WEBDAV ACCEPTANCE PASSED')

# Interrupt an actual production WebDAV GET, without navigating away from the
# backup screen or force-stopping the app: use the observed notification Stop.
def notification_words(tag):
 # A busy Compose progress indicator can prevent UIAutomator's idle wait;
 # its failed dump otherwise leaves a stale app hierarchy on /sdcard. Use
 # the current screenshot for this system-UI action, retaining OCR evidence.
 png=out/(tag+'.png')
 png.write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p'],timeout=15))
 tsv=subprocess.check_output(['tesseract',str(png),'stdout','--psm','11','tsv'],text=True,timeout=10)
 (out/(tag+'.tsv')).write_text(tsv)
 return [row for row in csv.DictReader(io.StringIO(tsv),delimiter='\t') if row.get('text','').strip() and float(row.get('conf','-1'))>=35]
def word_center(word):
 return (int(word['left'])+int(word['width'])//2,int(word['top'])+int(word['height'])//2)
def stop_notification(tag):
 # Expanding SystemUI is asynchronous. Observe fresh frames and retry the
 # shade gesture instead of assuming one 400ms delay establishes readiness.
 w,h=map(int,shell('wm','size').strip().split()[-1].split('x'))
 shell('cmd','statusbar','expand-notifications');time.sleep(1)
 for i in range(6):
  words=notification_words(tag+'-notification'+str(i))
  fixture=[w for w in words if 'backup-fixture' in w['text'].lower()]
  stops=[w for w in words if w['text'].strip().lower()=='stop']
  if fixture and stops:
   _,title_y=word_center(fixture[0])
   # Stop must belong to this app notification, not another card above it.
   stops=[x for x in stops if word_center(x)[1]>title_y]
   if stops:
    x,y=word_center(stops[0]);shell('input','tap',str(x),str(y));break
  if fixture:
   _,title_y=word_center(fixture[0])
   shell('input','tap',str(round(w*.86)),str(title_y+12))
  else:
   # Only a shade-opening gesture here; never guess a hidden Stop target.
   shell('cmd','statusbar','expand-notifications')
   shell('input','swipe',str(w//2),'1',str(w//2),str(round(h*.75)),'350')
  time.sleep(.8)
 else:
  (out/(tag+'-notification-state.txt')).write_text(shell('dumpsys','notification'))
  (out/(tag+'-statusbar-state.txt')).write_text(shell('dumpsys','statusbar'))
  raise AssertionError('Fresh fixture notification Stop unavailable')
 stopped=time.monotonic_ns()
 for _ in range(30):
  addr=shell('ip','addr')
  if '172.19.0.1' not in addr:break
  time.sleep(.2)
 else:raise AssertionError('TUN remained after notification Stop')
 (out/(tag+'-post-stop-addresses.txt')).write_text(addr)
 (out/(tag+'-post-stop-connectivity.txt')).write_text(shell('dumpsys','connectivity'))
 shell('cmd','statusbar','collapse');time.sleep(1)
 return stopped

def events():
 return [json.loads(x) for x in (out/'webdav-events.jsonl').read_text().splitlines()]
def scratch_size():
 return shell('run-as',P,'sh','-c',"'if test -e cache/chainbox-restore.zip; then wc -c < cache/chainbox-restore.zip; else echo ABSENT; fi'").strip()
midchecks={}
for mode in ('mid-headers','mid-body'):
 (out/'webdav-mode').write_text(mode)
 backup_screen(mode)
 prior=events();t0=time.monotonic_ns()
 click('通过 WebDAV 恢复数据',mode+'-request')
 for _ in range(60):
  current=events()[len(prior):]
  received=[e for e in current if e['method']=='GET' and e.get('mode')==mode and e['code']==0]
  if received:break
  time.sleep(.2)
 else:raise AssertionError('WebDAV request not received at fixture')
 partial=scratch_size()
 if mode=='mid-body':
  samples=[]
  for _ in range(80):
   partial=scratch_size()
   samples.append({'monotonic_ns':time.monotonic_ns(),'scratch':partial})
   (out/(mode+'-scratch-samples.json')).write_text(json.dumps(samples,indent=2))
   if partial.isdigit() and int(partial)>0:break
   time.sleep(.2)
  else:raise AssertionError('No device partial download before VPN Stop')
 # Route proof must precede stop: at least one CONNECT to this WebDAV host.
 route_after(mode,urllib.parse.urlsplit(os.environ['WABI_HTTPS_BASE']).hostname)
 started={'mode':mode,'request_started_monotonic_ns':t0,'peer_before_stop':current,'scratch_before_stop':partial,'vpn':vpn_checks[mode]}
 (out/(mode+'-started.json')).write_text(json.dumps(started,indent=2))
 stopped=stop_notification(mode)
 failure_ui=False
 for i in range(25):
  r=snap(mode+'-failure'+str(i),photo=i%4==0)
  texts=[n.get('text','') for n in r.iter('node')]
  if any('WebDAV 恢复失败' in t for t in texts):failure_ui=True
  # Wait through the existing read timeout; don't treat a still-in-flight
  # operation as cancellation or a successful clean-up.
  if time.monotonic_ns()-stopped>35_000_000_000:break
  time.sleep(.5)
 scratch=scratch_size();captured=events()[len(prior):]
 gets=[e for e in captured if e['method']=='GET' and e.get('mode')==mode and e['code']==0]
 # Snapshot only AFTER request settled. This force-stop is not the tested stop.
 state=db_snapshot('after-'+mode)
 check={**started,'vpn_stopped_monotonic_ns':stopped,'completion_monotonic_ns':time.monotonic_ns(),'scratch_after':scratch,'peer_after':captured,'failure_ui_seen':failure_ui,'data_unchanged':state==after,'get_requests':len(gets)}
 midchecks[mode]=check
 (out/'webdav-midstop-summary.json').write_text(json.dumps({'product_sha':'aeaef2fcb22d590e9f3f7ab17aae0fdb7b9717a6','checks':midchecks,'scope':'API34 emulator; production WebDAV UI and VpnService; synthetic encrypted backup and HTTPS WebDAV fixture through controlled SOCKS; notification Stop during GET headers or partial body','not_covered':['physical device','real WebDAV provider','PUT interruption','all backup combinations','OS revocation/crash','system kill switch']},indent=2))
 assert scratch=='ABSENT',(mode,scratch)
 assert state==after,(mode,state,after)
 assert len(gets)==1,(mode,captured)
 assert failure_ui,(mode,'No production failure UI captured')
print('WEBDAV MID-STOP ACCEPTANCE PASSED')
