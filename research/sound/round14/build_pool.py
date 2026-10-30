"""Create a provenance-locked development pool and compatible feature cache."""
import os
os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='1',VECLIB_MAXIMUM_THREADS='1',NUMBA_NUM_THREADS='1')
import json
from pathlib import Path
import re
import sys
import time
import wave
import numpy as np
HERE=Path(__file__).resolve().parent;PREV=HERE.parent;PROJECT=PREV.parent;OLD=PREV.parents[1]/'silent_root_cause_20261025'
sys.path[:0]=[str(PROJECT),str(PREV)]
from ast_worker import load_runtime,_window_starts
from prepare_public import sha,ensure_time
from experiment_core import connected_groups,temporal_features,LABELS


def waveform(path):
    with wave.open(str(path),'rb') as w:
        if (w.getframerate(),w.getnchannels(),w.getsampwidth())!=(16000,1,2):raise ValueError('WAV format')
        return np.frombuffer(w.readframes(w.getnframes()),'<i2').astype('float32')/32768


def canonical(parent):
    m=re.fullmatch(r'(?:fsd|esc50|freesound)_(\d+)',str(parent))
    return 'freesound_'+m.group(1) if m else str(parent)


def public_row(r):
    building=r['building'];parent=r.get('parent_source_id') or 'apartment_'+r['track_id']
    position=r.get('position','');source=position.split('-')[0]
    if building in ('B','C'):
        domain='apt_'+building;group='apt_'+building+':'+source
    elif building=='FSD50K':domain='FSD';group='uploader:'+r.get('uploader','')
    else:domain='ESC';group='parent:'+canonical(parent)
    return {'id':building+':'+r['track_id'],'label':r['sound_truth'],'domain':domain,'path':r['wav_path'],
      'parent':canonical(parent),'file_hash':r['file_sha256'],'pcm':r['pcm_sha256'],
      'source_sha':r.get('source_sha256',''),'uploader_key':('FSD:'+r['uploader']) if r.get('uploader') else '',
      'source_group':group,'origin':'new_fit' if r.get('role')=='fit' and building=='FSD50K' and 'round14' in r['wav_path'] else 'exposed_public',
      'track_id':r['track_id'],'source_type':r['source_type'],'position':position,'floor_truth':r.get('floor_truth','')}


def old_rows():
    d=json.loads((OLD/'ast-data-frozen.json').read_text());metadata=json.loads((PREV/'round11/data/FSD-dev-metadata.json').read_text());rows=[]
    metadata.update(json.loads((PREV/'round10/data/FSD-eval-metadata.json').read_text()))
    for r in d['current']+d['prior']:
        parent=canonical(r['parent_source_id']);author=''
        if parent.startswith('freesound_'):author=metadata.get(parent.split('_')[-1],{}).get('uploader','')
        rows.append({'id':'old:'+r['sha256'],'label':r['label'],'domain':'old_mic' if r.get('is_physical') else 'old_source',
          'path':r['path'],'parent':parent,'file_hash':r['sha256'],'pcm':r.get('pcm_sha256',''),
          'source_group':'parent:'+parent,'uploader_key':'FSD:'+author if author else '',
          'origin':'exposed_user_or_replay','track_id':r['sha256'],'source_type':r['label'],'position':'','floor_truth':''})
    return rows


def collect():
    corrected=json.loads((PREV/'source-integrity/upstream-conflict-correction.json').read_text())
    bad={r['track_id'] for v in corrected['conflicting_blobs'].values() for r in v}
    rows=old_rows();seen={r['file_hash'] for r in rows};files=[];cache={}
    folders=[PREV,*(PREV/f'round{i}' for i in (2,3,4,5,6,8,10,11,12,13)),HERE]
    for folder in folders:
        f=folder/'data/manifest.json'
        if not f.exists():continue
        files.append(f);rr=json.loads(f.read_text())['rows'];lookup={r['track_id']:r for r in rr}
        for name in ('ast-development.npz','ast-holdout.npz','wind-development.npz','foot-development.npz'):
            c=folder/'data'/name
            if not c.exists():continue
            with np.load(c,allow_pickle=False) as z:
                if 'track_ids' not in z or 'features' not in z:continue
                for j,tid in enumerate(z['track_ids'].tolist()):
                    r=lookup.get(tid)
                    # R11/R13 development features refer to R10 rows.
                    if r is None:
                        r=next((q for q in json.loads((PREV/'round10/data/manifest.json').read_text())['rows'] if q['track_id']==tid),None)
                    if r is not None and z['file_sha256'][j]==r['file_sha256'] and ('good' not in z or z['good'][j]):cache[r['file_sha256']]=z['features'][j].copy()
        for r in rr:
            if folder==HERE and r['role']!='fit':continue
            if r.get('excluded_reason') or r['track_id'] in bad or r['file_sha256'] in seen:continue
            if r['sound_truth'] not in LABELS:continue
            rows.append(public_row(r));seen.add(r['file_sha256'])
    # Identical PCM with conflicting labels is excluded as a whole connected cluster.
    pcm_labels={}
    for r in rows:
        if r['pcm']:pcm_labels.setdefault(r['pcm'],set()).add(r['label'])
    ambiguous={p for p,l in pcm_labels.items() if len(l)>1};excluded=[r for r in rows if r['pcm'] in ambiguous]
    rows=[r for r in rows if r['pcm'] not in ambiguous]
    groups=connected_groups(rows)
    for r,g in zip(rows,groups):r['group']=str(g)
    return rows,cache,files,excluded


def embed(samples,state):
    np_,torch,extractor,model,head,labels,decide,predict_head,prepare_view,device,manifest=state
    emb=[]
    for start in _window_starts(len(samples)):
        part,_,low=prepare_view(samples[start:start+64000],'activity4')
        if low:continue
        value=extractor(part,sampling_rate=16000,return_tensors='pt').input_values.to(device)
        with torch.inference_mode():emb.append(model.audio_spectrogram_transformer(input_values=value,return_dict=True).pooler_output[0].cpu().numpy().astype('float32'))
    return np.mean(emb,axis=0,dtype='float32') if emb else np.zeros(768,dtype='float32'),bool(emb)


def main():
    ensure_time();dest=HERE/'data/development.npz'
    if dest.exists():raise RuntimeError('Never overwrite completed pool')
    rows,cache,files,excluded=collect();print('POOL',len(rows),'cached',sum(r['file_hash'] in cache for r in rows),'groups',len(set(r['group'] for r in rows)),flush=True)
    (HERE/'data/pool-frozen.json').write_text(json.dumps({'scope':'ADAPTIVE DEVELOPMENT ONLY, old audio and previously scored public clips','rows':rows,
      'excluded_conflicting_pcm':excluded,'input_manifests':{str(p):sha(p) for p in files},'protocol_sha256':sha(HERE/'PROTOCOL.md')},ensure_ascii=False,indent=2))
    state=None;vectors=[];temporal=[];good=[];start=time.monotonic();computed=0
    for j,r in enumerate(rows):
        ensure_time();path=Path(r['path'])
        if sha(path)!=r['file_hash']:raise RuntimeError('Pool file hash changed')
        audio=waveform(path);temporal.append(temporal_features(audio))
        if r['file_hash'] in cache:vector=cache[r['file_hash']];ok=bool(np.any(vector))
        else:
            if state is None:state=load_runtime(OLD);print('POOL_DEVICE',state[9],flush=True)
            vector,ok=embed(audio,state);computed+=1
        vectors.append(vector);good.append(ok)
        if (j+1)%40==0 or j==len(rows)-1:print('POOL_FEATURES',j+1,'/',len(rows),'computed',computed,'seconds',round(time.monotonic()-start),flush=True)
    np.savez_compressed(dest,features=np.asarray(vectors),temporal=np.asarray(temporal),good=np.asarray(good),file_hash=np.array([r['file_hash'] for r in rows]))
    print('POOL_COMPLETE',len(rows),'features',sha(dest),flush=True)


if __name__=='__main__':main()
