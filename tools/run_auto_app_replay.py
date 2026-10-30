"""Actual acoustic replay -> ESP32 auto detection -> V2 APK database.

The frozen model has seen related sources: this is a rehearsal, not independent accuracy.
Misses and extra events are retained, never removed from the denominator.
"""
from pathlib import Path
import argparse,array,json,subprocess,time,wave,sys
from physical_capture import request,mac_audio_state
from run_physical_development import TRIALS
from v2_app_evidence import snapshot
from v2_adb_ui import tap_text,run,PACKAGE
from replay_evidence import stage_outcome,TrialJournal
ROOT=Path(__file__).resolve().parents[2]

def stimulus(source,destination):
    with wave.open(str(source),'rb') as w:
        if (w.getframerate(),w.getnchannels(),w.getsampwidth())!=(16000,1,2):raise ValueError('Stimulus format')
        x=array.array('h');x.frombytes(w.readframes(w.getnframes()))
    if sys.byteorder!='little':x.byteswap()
    powers=[sum(v*v for v in x[i:i+1600]) for i in range(0,len(x),1600)]
    center=max(range(len(powers)),key=powers.__getitem__)*1600+800
    start=max(0,min(center-32000,len(x)-64000));x=x[start:start+64000]
    dc=sum(x)/len(x);peak=max(abs(v-dc) for v in x)
    if peak<1:raise ValueError('Silent stimulus')
    y=array.array('h',[int((v-dc)*19660/peak) for v in x])
    if sys.byteorder!='little':y.byteswap()
    with wave.open(str(destination),'wb') as w:w.setparams((1,2,16000,0,'NONE','not compressed'));w.writeframes(y.tobytes())
    return {'start_sample':start,'original_peak':peak,'normalized_peak':19660}

def main():
    p=argparse.ArgumentParser();p.add_argument('--base',required=True);p.add_argument('--name',required=True);p.add_argument('--first',type=int,default=1);p.add_argument('--last',type=int,default=18);p.add_argument('--manifest',type=Path);a=p.parse_args()
    trials=TRIALS;scope='known_source_same_room_rehearsal'
    if a.manifest:
        definition=json.loads(a.manifest.read_text());trials=[(r['label'],Path(r['path'])) for r in definition['trials']];scope=definition['scope']
    folder=ROOT/'evidence'/a.name;folder.mkdir(exist_ok=True)
    state=mac_audio_state()
    if state['output_muted'] or state['output_volume']==0:raise RuntimeError('Muted speaker; no trials started')
    initial=request(a.base,'/api/status')
    if not initial.get('autoEnabled'):tap_text('상시 녹음 시작')
    time.sleep(1)
    try:
        for number,(truth,path) in enumerate(trials,1):
            if not a.first<=number<=a.last:continue
            time.sleep(2)
            before=request(a.base,'/api/status');idle_deadline=time.monotonic()+25;pretrial=[]
            while before['recording'] or before['pendingEvents'] or before['finalizing']:
                pretrial.append({'at':time.time(),**before})
                if time.monotonic()>idle_deadline:
                    (folder/'background-preflight-failure.json').write_text(json.dumps(pretrial,indent=2))
                    raise RuntimeError('Cannot isolate stimulus from background events')
                time.sleep(.3);before=request(a.base,'/api/status')
            if not before['autoEnabled']:raise RuntimeError('App has not armed device automatic mode')
            if before['recording'] or before['pendingEvents']:raise RuntimeError('Preceding event still active; do not misattribute it')
            stim=folder/f'stimulus-{number:02}.wav';metadata=stimulus(path,stim)
            row={'trial':number,'truth':truth,'source':str(path),'source_scope':scope,'independent_test':False,'stimulus':metadata,'audio_state':mac_audio_state(),'initial_status':before,'pretrial_background_states':pretrial}
            with TrialJournal(folder,row):
                row['playback_start_epoch']=time.time();subprocess.run(['/usr/bin/afplay','-v','1',str(stim)],check=True,timeout=10);row['playback_end_epoch']=time.time()
                row['states']=[];deadline=time.monotonic()+25
                while time.monotonic()<deadline:
                    status=request(a.base,'/api/status');row['states'].append({'at':time.time(),**status})
                    if status['startedEvents']>before['startedEvents'] and not status['recording'] and not status['finalizing'] and not status['pendingEvents']:break
                    time.sleep(.2)
                records=[];analysis_deadline=time.monotonic()+8
                while status['startedEvents']>before['startedEvents']:
                    records=snapshot('auto-app-live-snapshot');outcome=stage_outcome(before,status,records,truth)
                    if outcome['classification_completed'] or outcome['failure_stage']=='analysis_failed' or time.monotonic()>analysis_deadline:break
                    time.sleep(.3)
                prefix=f"{before['deviceId']}-{before['bootId']}-"
                row['records']=[r for r in records if (r.get('deviceEventId') or '').startswith(prefix) and before['startedEvents']<int(r['deviceEventId'].rsplit('-',1)[1],16)<=status['startedEvents']]
                row.update(stage_outcome(before,status,row['records'],truth))
                row['detected']=row['device_detected'];row['correct']=row['end_to_end_success']
                if row.get('analysis_generated_epoch_ms'):row['end_to_result_seconds']=row['analysis_generated_epoch_ms']/1000-row['playback_end_epoch']
                print(json.dumps({k:row.get(k) for k in ['trial','truth','device_detected','import_succeeded','classification_completed','classification_correct','failure_stage','end_to_result_seconds']},ensure_ascii=False),flush=True)
                if status.get('quarantinedSlots') or status.get('audioOverflows'):raise RuntimeError('Device health degraded; stop rehearsal')
    finally:
        tap_text('상시 녹음 중지');deadline=time.monotonic()+25
        while time.monotonic()<deadline:
            final=request(a.base,'/api/status')
            if not final['recording'] and not final['finalizing'] and not final['pendingEvents']:break
            time.sleep(.3)
        time.sleep(1)
        snapshot(a.name+'-final',copy_audio=True)
        (folder/'final-status.json').write_text(json.dumps(request(a.base,'/api/status'),indent=2))

if __name__=='__main__':main()
