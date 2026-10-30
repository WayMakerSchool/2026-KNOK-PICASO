"""Replay a WAV through Mac speakers and preserve a real ESP32 microphone WAV.

This is replay/development evidence, never an independent accuracy result.
The stimulus identity is stored separately and is not passed to a classifier.
"""
from pathlib import Path
import argparse, array, hashlib, io, json, os, re, subprocess, sys, time, urllib.request, wave, zlib

ROOT = Path(__file__).resolve().parents[2]


def request(base, path, post=False):
    r = urllib.request.Request(base + path, data=b'' if post else None)
    with urllib.request.urlopen(r, timeout=15) as f:
        return json.load(f)


def mac_audio_state():
    text=subprocess.check_output(['osascript','-e','get volume settings'],text=True).strip()
    level=int(re.search(r'output volume:(\d+)',text).group(1))
    muted='output muted:true' in text
    return {'output_volume':level,'output_muted':muted}


def capture(base, source, label, volume=.5):
    audio_state=mac_audio_state()
    if audio_state['output_muted'] or audio_state['output_volume']==0:
        raise RuntimeError('Playback not presented: Mac output is muted or zero; no device capture started')
    with wave.open(str(source), 'rb') as w:
        assert (w.getframerate(),w.getnchannels(),w.getsampwidth())==(16000,1,2)
        raw=w.readframes(w.getnframes())
    values=array.array('h');values.frombytes(raw)
    if sys.byteorder!='little':values.byteswap()
    energies=[sum(v*v for v in values[i:i+1600]) for i in range(0,len(values),1600)]
    center=max(range(len(energies)),key=energies.__getitem__)*1600+800
    start=max(0,min(center-32000,len(values)-64000))
    crop=values[start:start+64000]
    dc=sum(crop)/len(crop);peak=max(abs(v-dc) for v in crop)
    if peak<1:raise RuntimeError('Stimulus is silent; no controlled presentation possible')
    stimulus_gain=19660.0/peak
    normalized=array.array('h',[int(max(-32768,min(32767,(v-dc)*stimulus_gain))) for v in crop])
    if sys.byteorder!='little':normalized.byteswap()
    pcm=normalized.tobytes()
    folder=ROOT/'evidence'/'physical-captures';folder.mkdir(exist_ok=True)
    stimulus=folder/'playback-stimulus.wav'
    with wave.open(str(stimulus),'wb') as w:
        w.setparams((1,2,16000,0,'NONE','not compressed'));w.writeframes(pcm)
    before=request(base,'/api/events')['events']
    if before:raise RuntimeError('Pending device events must be preserved/imported first')
    initial=request(base,'/api/status')
    if initial.get('autoEnabled'):raise RuntimeError('Stop automatic mode before manual microphone probe')
    if initial.get('quarantinedSlots',0):raise RuntimeError('Repair quarantined device slot before formal replay probe')
    request(base,'/api/record/start?durationMs=8000',True)
    deadline=time.monotonic()+5
    while time.monotonic()<deadline:
        if request(base,'/api/status')['recording']:break
        time.sleep(.1)
    else:raise RuntimeError('Device never started capture')
    started=time.time()
    subprocess.run(['/usr/bin/afplay','-v',str(volume),str(stimulus)],check=True,timeout=10)
    deadline=time.monotonic()+25
    while time.monotonic()<deadline:
        status=request(base,'/api/status')
        events=request(base,'/api/events')['events']
        if events:break
        time.sleep(.2)
    else:raise RuntimeError('No committed event WAV')
    expected=f"{initial['deviceId']}-{initial['bootId']}-{initial['startedEvents']+1:08x}"
    e=next((x for x in events if x['id']==expected),None)
    if e is None:raise RuntimeError('Unrelated event returned; preserved for manual inspection')
    with urllib.request.urlopen(base+'/api/events/file?id='+e['id'],timeout=15) as f:
        data=f.read();storage_at_download=f.headers.get('X-KNOK-Storage','legacy_flash')
    if len(data)!=e['bytes'] or format(zlib.crc32(data)&0xffffffff,'08x')!=e['crc32']:
        raise RuntimeError('Physical WAV length/CRC verification failed; event not ACKed')
    with wave.open(io.BytesIO(data),'rb') as w:
        if (w.getframerate(),w.getnchannels(),w.getsampwidth())!=(16000,1,2):raise RuntimeError('Wrong physical WAV format')
    out=folder/(e['id']+'.wav')
    with out.open('wb') as saved:saved.write(data);saved.flush();os.fsync(saved.fileno())
    out.chmod(0o600)
    record={'scope':'real_microphone_speaker_replay_development','independent_test':False,
        'stimulus':str(source),'stimulus_crop_start_sample':start,'playback_volume':volume,
        'stimulus_gain':stimulus_gain,'stimulus_original_peak_pcm':peak,'stimulus_normalized_peak_pcm':19660,
        'ground_truth':label,'started_epoch':started,'event':e,
        'storage_at_download':storage_at_download,'host_audio_before':audio_state,'host_audio_after':mac_audio_state(),
        'wav_path':str(out),'wav_sha256':hashlib.sha256(data).hexdigest(),'initial_status':initial,
        'final_status':status}
    (folder/(e['id']+'.json')).write_text(json.dumps(record,indent=2))
    request(base,'/api/events/ack?id='+e['id'],True)
    print(json.dumps({'captured':str(out),'label_for_evaluation_only':label,
        'max_relative_db':e['maxLevelDb'],'bytes':len(data),'wav_verified':True}))
    return record


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--base',required=True);p.add_argument('--source',required=True,type=Path)
    p.add_argument('--label',required=True);p.add_argument('--volume',type=float,default=.5)
    a=p.parse_args();capture(a.base,a.source,a.label,a.volume)
