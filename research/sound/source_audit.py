"""Verify omitted desk training views and quantify the replay domain change."""
import hashlib
import json
from pathlib import Path
import sys
import wave
import numpy as np
HERE=Path(__file__).resolve().parent;IMPROVEMENT=HERE.parent;OLD=IMPROVEMENT/'wednesday_two_models_20261028/tuesday_public_20261027';BASE=OLD/'continuation_2100';CAL=IMPROVEMENT/'window_fix_20261024_0715'


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def read(path):return json.loads(Path(path).read_text())


def waveform(path):
    with wave.open(str(path),'rb') as audio:
        if (audio.getframerate(),audio.getnchannels(),audio.getsampwidth())!=(16000,1,2):raise ValueError('16kHz mono PCM16 required')
        x=np.frombuffer(audio.readframes(audio.getnframes()),'<i2').astype('float64')/32768
    return x


def signal_stats(path):
    x=waveform(path);centered=x-x.mean();n=min(64000,len(x));squares=np.r_[0.,np.cumsum(centered*centered)];energy=squares[n:]-squares[:-n];start=int(np.argmax(energy));part=centered[start:start+n]
    power=np.abs(np.fft.rfft(part*np.hanning(len(part))))**2;freq=np.fft.rfftfreq(len(part),1/16000);total=power[(freq>=40)&(freq<=8000)].sum()
    return {'sample_count':len(x),'seconds':len(x)/16000,'rms':float(np.sqrt(np.mean(centered*centered))),'peak':float(abs(centered).max()),'active_start':start,
      'band_fractions':{str(lo)+'-'+str(hi):float(power[(freq>=lo)&(freq<hi)].sum()/max(total,1e-12)) for lo,hi in ((40,125),(125,500),(500,2000),(2000,8001))}}


def audit():
    lineage=read(CAL/'desk-training-lineage.json');reg=read(BASE/'desk-training-regression.json')['rows'];base=read(OLD/'round19/data/manifest.json')['rows']
    hashes={r['file_hash'] for r in base};reg_hash={r['file_sha256'] for r in reg};sources=[]
    wanted={r['sha256']:r for r in lineage if r.get('event') and r.get('start_sample')==0 and r['role']=='training'}
    for path in (CAL/'evidence/app-recordings').glob('*.wav'):
        digest=sha(path)
        if digest in wanted:
            if digest in reg_hash:raise RuntimeError('Training/regression identity collision')
            row=wanted[digest];sources.append({'path':str(path),'file_sha256':digest,'parent_source_id':'desk_'+row['parent'],'known_truth':row['label'],
              'event_id':row['event'],'role':'restore_old_training_view','omitted_from_R21':digest not in hashes,**signal_stats(path)})
    if len(sources)!=3 or not all(r['omitted_from_R21'] for r in sources):raise RuntimeError('Expected three verified omitted training views')
    representative=next(r for r in base if r['parent'].startswith('desk_'))
    records=[{'path':representative['path'],'file_sha256':representative['file_hash'],'parent_source_id':representative['parent'],'role':'R21_training_representative',**signal_stats(representative['path'])},*sources]
    for r in reg:
        if sha(r['path'])!=r['file_sha256']:raise RuntimeError('Regression WAV changed')
        records.append({'path':r['path'],'file_sha256':r['file_sha256'],'parent_source_id':r['parent_source_id'],'role':'regression_only_never_fit',**signal_stats(r['path'])})
    replay=read(CAL/'live_third/conditions-frozen.json')
    report={'scope':'Read-only exact lineage and signal diagnosis, no new capture or fitting','restored_training':sources,'regression':reg,'records':records,
      'shared_desk_parent':representative['parent'],'past_success_cases_are_speaker_replays':replay['replay_not_manual_tapping'],
      'replay_source':replay['source'],'replay_source_sha256':replay['source_sha256'],'lineage_sha256':sha(CAL/'desk-training-lineage.json'),'regression_manifest_sha256':sha(BASE/'desk-training-regression.json'),
      'conclusion':'Representative sampling omitted recorded replay views used by the old calibration; direct training and replay observations are different domains. Restore views with shared-parent weights; regression is not independent accuracy.'}
    (HERE/'source-diagnostics.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    for r in records:print(r['role'],Path(r['path']).stem[-8:],round(r['seconds'],3),round(r['rms'],4),{k:round(v,3) for k,v in r['band_fractions'].items()},flush=True)


if __name__=='__main__':audit()
