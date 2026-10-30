"""Actual emulator LAN outage, flash fallback, board reset and deduplicated import.

Run only with the specifically authorized V2 app and board. Never erases flash.
"""
from pathlib import Path
import hashlib,json,subprocess,time,urllib.request,zlib
from physical_capture import request
from v2_adb_ui import run,tap_text,screenshot,PACKAGE
from v2_app_evidence import snapshot
from run_auto_app_replay import stimulus
ROOT=Path(__file__).resolve().parents[2]
BASE='http://192.168.219.111'

def wait(fn,seconds=50):
    end=time.monotonic()+seconds;last=None
    while time.monotonic()<end:
        try:
            last=fn()
            if last:return last
        except Exception:pass
        time.sleep(.3)
    raise TimeoutError(str(last))

def download(event,path):
    with urllib.request.urlopen(BASE+'/api/events/file?id='+event['id'],timeout=20) as f:data=f.read();storage=f.headers.get('X-KNOK-Storage')
    if len(data)!=event['bytes'] or f'{zlib.crc32(data)&0xffffffff:08x}'!=event['crc32']:raise ValueError('WAV corruption; do not ACK')
    path.write_bytes(data);return {'sha256':hashlib.sha256(data).hexdigest(),'storage':storage,'bytes':len(data)}

def main():
    folder=ROOT/'evidence/r4-outage-reboot';folder.mkdir(exist_ok=True);report={'scope':'actual_emulator_wifi_offline_board_flash_recovery_known_source_replay','independent_accuracy_test':False}
    wifi=run('shell','settings','get','global','wifi_on').strip();mobile=run('shell','settings','get','global','mobile_data').strip()
    report['emulator_before']={'wifi_on':wifi,'mobile_data':mobile}
    if wifi!='1' or mobile!='0':raise RuntimeError('Unexpected network state; do not alter unrelated connectivity')
    if request(BASE,'/api/status')['autoEnabled']:raise RuntimeError('Finish other automatic test first')
    tap_text('상시 녹음 시작');wait(lambda:request(BASE,'/api/status')['autoEnabled'])
    try:
        run('shell','svc','wifi','disable');time.sleep(3)
        ping=subprocess.run(['/Users/yesung/Library/Android/sdk/platform-tools/adb','-s','emulator-5554','shell','ping','-c','1','-W','1','192.168.219.111'],capture_output=True,text=True,timeout=5)
        report['offline_ping']={'returncode':ping.returncode,'output':ping.stdout+ping.stderr}
        if ping.returncode==0:raise RuntimeError('Emulator still reaches LAN; outage was not reproduced')
        before=request(BASE,'/api/status');report['before']=before
        path=folder/'stimulus.wav';stimulus(Path('/Users/yesung/Documents/Codex/Projects/KNOK_device_test_20261022/wav/Vacuum sound 1.wav'),path)
        report['playback_start']=time.time();subprocess.run(['/usr/bin/afplay','-v','1',str(path)],check=True,timeout=10);report['playback_end']=time.time()
        event=wait(lambda:next((e for e in request(BASE,'/api/events')['events'] if e['id'].startswith(before['deviceId']+'-'+before['bootId']+'-') and int(e['id'].split('-')[-1],16)>before['startedEvents']),None))
        report['event']=event;report['first_download']=download(event,folder/'before-reboot.wav')
        persisted=wait(lambda:next((e for e in request(BASE,'/api/events')['events'] if e['id']==event['id'] and e['devicePersistence']=='flash'),None),60)
        report['persisted_event']=persisted;report['flash_download']=download(persisted,folder/'flash-before-reboot.wav')
        if report['first_download']['sha256']!=report['flash_download']['sha256']:raise ValueError('RAM/flash differ')
        report['status_before_reboot']=request(BASE,'/api/status');screenshot(folder/'app-offline.png')
        cmd=['/Users/yesung/Documents/Codex/Projects/KNOK_hardware_tools/.venv/bin/python','-m','esptool','--chip','esp32s3','--port','/dev/cu.usbmodem1101','--after','hard-reset','read-mac']
        reset=subprocess.run(cmd,capture_output=True,text=True,timeout=25);(ROOT/'private/recovery-read-mac.log').write_text(reset.stdout+reset.stderr)
        if reset.returncode:raise RuntimeError('Read-only board reset tool failed')
        recovered=wait(lambda:next((e for e in request(BASE,'/api/events')['events'] if e['id']==event['id'] and e['devicePersistence']=='flash'),None),40)
        report['recovered_event']=recovered;report['after_reboot_download']=download(recovered,folder/'after-reboot.wav')
        if report['after_reboot_download']['sha256']!=report['flash_download']['sha256']:raise ValueError('Reboot WAV differs')
    finally:
        run('shell','svc','wifi','enable')
        (folder/'test-progress.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    wait(lambda:request(BASE,'/api/status')['autoEnabled'],45)
    wait(lambda:not request(BASE,'/api/events')['events'],45);time.sleep(2)
    rows=snapshot('outage-reboot-after-import',True);matches=[r for r in rows if r['deviceEventId']==event['id']]
    if len(matches)!=1 or matches[0]['deviceClassificationPending']:raise ValueError('Event missing/duplicated/not analyzed after reconnection')
    report['imported_record']=matches[0];report['status_after_reconnect']=request(BASE,'/api/status')
    tap_text('상시 녹음 중지');wait(lambda:not request(BASE,'/api/status')['autoEnabled']);time.sleep(1)
    run('shell','am','force-stop',PACKAGE);run('shell','am','start','-n',PACKAGE+'/com.example.MainActivity');time.sleep(2)
    again=snapshot('after-v2-process-restart',True);report['matching_records_after_app_restart']=len([r for r in again if r['deviceEventId']==event['id']])
    if report['matching_records_after_app_restart']!=1:raise ValueError('App restart lost or duplicated imported record')
    report['passed']=True;(folder/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({'passed':True,'event':event['id'],'label':matches[0]['classificationLabel'],'reboot_wav_identical':True,'duplicate_count':len(matches)},ensure_ascii=False))

if __name__=='__main__':main()
