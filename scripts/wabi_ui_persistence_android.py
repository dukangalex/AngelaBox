#!/usr/bin/env python3
"""Nonpublishing API34 UI import/update/persistence acceptance.
Public GitHub raw hosting, controlled project configs; no private provider/account.
"""
import os, json, hashlib, sqlite3, struct, tarfile, io, time, subprocess
from pathlib import Path
# Reuse only the established UI helpers, without executing prior acceptance.
source=Path('scripts/wabi_subscription_android.py').read_text()
exec(source.split("shell('am','start','-W','-n',P+'/io.nekohasekai.sfa.inspection.WabiTransportActivity','--es','mode','controlled-real-dns')")[0])

def launch(tag):
 shell('monkey','-p',P,'-c','android.intent.category.LAUNCHER','1');time.sleep(6)
 root=snap(tag)
 assert any(n.attrib.get('text')=='Controlled-allow' for n in root.iter('node')), 'selected profile not restored'
 return root

def snapshot(tag):
 # Read only an isolated test installation, after stopping it for a consistent DB/WAL copy.
 shell('am','force-stop',P)
 blob=subprocess.check_output(['adb','exec-out','run-as',P,'tar','-cf','-','databases','files/configs'],timeout=40)
 folder=out/tag;folder.mkdir(exist_ok=True)
 with tarfile.open(fileobj=io.BytesIO(blob)) as archive:archive.extractall(folder,filter='data')
 db=sqlite3.connect(folder/'databases/profiles.db')
 rows=db.execute('select id,userOrder,name,typed from profiles order by userOrder').fetchall();db.close()
 profiles=[]
 for ident,order,name,typed in rows:
  pos=0
  # Parcel is little endian; UTF16 string payloads are padded to 4 bytes.
  version=struct.unpack_from('<i',typed,pos)[0];pos+=4
  def string_at(pos):
   n=struct.unpack_from('<i',typed,pos)[0];pos+=4
   if n<0:return None,pos
   text=typed[pos:pos+n*2].decode('utf-16le');pos+=2*(n+1);pos=(pos+3)//4*4
   return text,pos
  path,pos=string_at(pos);kind=struct.unpack_from('<i',typed,pos)[0];pos+=4
  url,pos=string_at(pos);auto=struct.unpack_from('<i',typed,pos)[0];pos+=4
  updated=struct.unpack_from('<q',typed,pos)[0]
  config=folder/'files/configs'/Path(path).name
  body=config.read_bytes();json.loads(body)
  profiles.append({'id':ident,'order':order,'name':name,'path':path,'type':kind,'url':url,'auto':auto,'last_updated':updated,'typed_sha256':hashlib.sha256(typed).hexdigest(),'config_sha256':hashlib.sha256(body).hexdigest(),'config_bytes':len(body)})
 assert len(profiles)==2 and {p['name'] for p in profiles}=={'Controlled-allow','Controlled-reject'},profiles
 assert all(p['type']==1 and p['config_bytes']>0 for p in profiles),profiles
 settings=sqlite3.connect(folder/'databases/settings.db')
 selection=settings.execute('select valueType,value from KeyValueEntity where key=?',('selected_profile',)).fetchone();settings.close()
 # Require the actual persisted selection, not merely a visible profile label.
 assert selection is not None, 'selected_profile missing'
 chosen=next(p for p in profiles if p['name']=='Controlled-allow')
 assert struct.unpack('>q',selection[1])[0]==chosen['id'], (selection,chosen)
 result={'profiles':profiles,'selection':None if selection is None else {'type':selection[0],'hex':selection[1].hex()}}
 (out/(tag+'.json')).write_text(json.dumps(result,indent=2))
 return result

def select_allow():
 root=snap('select-allow-dashboard');labels=[n for n in root.iter('node') if n.attrib.get('text','').startswith('Controlled-')]
 labels.sort(key=lambda n:int(re.findall(r'\d+',n.attrib['bounds'])[1]));tap(labels[0])
 root=snap('select-allow-picker');choices=[n for n in root.iter('node') if n.attrib.get('text')=='Controlled-allow'];assert len(choices)==1;tap(choices[0])
 shell('input','keyevent','4');time.sleep(2)

# Use the production import UI to fetch two public, pinned configurations.
shell('am','start','-W','-n',P+'/io.nekohasekai.sfa.inspection.WabiTransportActivity','--es','mode','controlled-real-dns')
start_vpn('allow');stop_vpn()
start_vpn('reject');stop_vpn()
select_allow();before=snapshot('ui-import-before-restart')
root=launch('ui-process-restored')
after=snapshot('ui-after-process-restart');assert after==before,(before,after)
# Restart the VPN by the ordinary visible Start control, preserving UI state.
launch('ui-before-update');button('Start','ui-update-start')
for attempt in range(10):
 root=snap('ui-update-running'+str(attempt))
 texts=[n.attrib.get('text','') for n in root.iter('node')]
 if 'Deprecated Warning' in texts or 'Connection request' in texts:button('OK','ui-update-warning');continue
 if any(n.attrib.get('content-desc')=='Stop' for n in root.iter('node')):break
 time.sleep(2)
else:raise AssertionError('VPN did not become ready for UI update')
assert '172.19.0.1' in shell('ip','addr')
# Open the profile picker/list and use its production Update profile toolbar action.
root=snap('ui-update-dashboard');labels=[n for n in root.iter('node') if n.attrib.get('text')=='Controlled-allow'];assert labels;tap(labels[0])
root=snap('ui-update-profiles');buttons=[n for n in root.iter('node') if n.attrib.get('content-desc')=='Update profile'];assert buttons,'update action missing'
buttons.sort(key=lambda n:int(re.findall(r'\d+',n.attrib['bounds'])[1]));update_started_at=utc_now();proxy_before=proxy_counts();tap(buttons[0]);time.sleep(25)
snap('ui-update-complete');proxy_after=proxy_counts()
shell('input','keyevent','4');time.sleep(2);stop_vpn()
updated=snapshot('ui-after-public-host-update')
assert [p['id'] for p in updated['profiles']]==[p['id'] for p in before['profiles']]
for old,new in zip(before['profiles'],updated['profiles']):
 assert new['last_updated']>old['last_updated'],(old,new)
 assert new['config_sha256']==old['config_sha256'] and new['url']==old['url'],(old,new)
 assert new['url']==f"https://raw.githubusercontent.com/dukangalex/AngelaBox/{sha}/scripts/wabi_egress_{new['name'].split('-')[-1]}.json",new
assert proxy_after['connect_successes']>proxy_before['connect_successes'],(proxy_before,proxy_after)
(out/'ui-public-host-update-network.json').write_text(json.dumps({'started_at':update_started_at,'proxy_before':proxy_before,'proxy_after':proxy_after,'scope':'Public GitHub raw host and controlled project configs; not a commercial/private subscription provider.'},indent=2))
launch('ui-update-restored');persisted=snapshot('ui-updated-process-restored');assert persisted==updated
# Full Android reboot, not just an Activity reconstruction.
adb('reboot');adb('wait-for-device')
for _ in range(120):
 if shell('getprop','sys.boot_completed').strip()=='1':break
 time.sleep(2)
else:raise AssertionError('reboot did not finish')
shell('input','keyevent','82');time.sleep(4)
launch('ui-device-reboot-restored');rebooted=snapshot('ui-after-device-reboot');assert rebooted==updated
(out/'ui-persistence-summary.json').write_text(json.dumps({'passed':True,'product_sha':'b994dfc02f3dfee97a256167bd3dbfcaaac2b727','test_sha':sha,'checks':['production remote import UI (2 configs)','selected profile and config persistence after process death','foreground Update profile via public HTTPS GitHub raw while VPN active','both profile timestamps advanced; content hashes unchanged','updated state survives process death','updated state survives full emulator reboot'],'not_covered':['physical device','private/commercial subscription provider','all app functions','system kill switch'],'profiles':rebooted['profiles']},indent=2))
print('UI import, public-host update, process and reboot persistence passed')
