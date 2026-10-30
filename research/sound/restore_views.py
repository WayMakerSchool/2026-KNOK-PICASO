import os
os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='2',VECLIB_MAXIMUM_THREADS='1')
from pathlib import Path
import json
import sys
import time
import wave
import numpy as np
HERE=Path(__file__).resolve().parent;IMP=HERE.parent;OLD=IMP/'wednesday_two_models_20261028/tuesday_public_20261027';STUDY=OLD.parent
sys.path[:0]=[str(OLD/'round21'),str(STUDY)]
from extract_bags import bag
from ast_worker import load_runtime
from safeguards import sha,assert_disjoint,transfer_folds


def wav(path):
    with wave.open(str(path),'rb') as w:
        if (w.getframerate(),w.getnchannels(),w.getsampwidth())!=(16000,1,2):raise ValueError('Expected 16k mono PCM16')
        raw=w.readframes(w.getnframes())
    import hashlib
    return np.frombuffer(raw,'<i2').astype('float32')/32768,hashlib.sha256(raw).hexdigest()


def main():
    data=HERE/'data';out=data/'restored-bags.npz'
    if out.exists():raise RuntimeError('Preserve completed restored cache')
    diag=json.loads((HERE/'source-diagnostics.json').read_text());rows=json.loads((OLD/'round19/data/manifest.json').read_text())['rows'];representative=next(r for r in rows if r['parent']==diag['shared_desk_parent'])
    with np.load(OLD/'round19/data/development.npz') as z:folds=z['folds'].copy()
    with np.load(OLD/'round21/data/bags.npz') as z:
        if z['file_hash'].tolist()!=[r['file_hash'] for r in rows]:raise ValueError('Cached row identity changed')
        features=[z['features'][z['offsets'][i]:z['offsets'][i+1]].copy() for i in range(len(rows))]
    extras=[];reg=[];arrays=[];began=time.monotonic();state=load_runtime(IMP/'silent_root_cause_20261025')
    for item in diag['restored_training']+diag['regression']:
        path=Path(item['path']);digest=sha(path);expected=item.get('file_sha256')
        if digest!=expected:raise RuntimeError('Source hash mismatch')
        audio,pcm=wav(path);r=dict(representative,id='restore:'+digest,path=str(path),file_hash=digest,pcm=pcm,role='restore_old_training_view' if item in diag['restored_training'] else 'regression_only_never_fit')
        v,positions=bag(audio,state)
        if len(v)==0:raise ValueError('Restored capture has no usable window')
        if r['role']=='restore_old_training_view':extras.append(r);features.append(v)
        else:reg.append(r);arrays.append(v)
        print('RESTORE_BAG',r['role'],path.stem[-8:],len(v),round(time.monotonic()-began),flush=True)
    # Parent intentionally shared; fitting and regression must differ in actual WAV/PCM.
    assert_disjoint([{k:r[k] for k in ('file_hash','pcm')} for r in rows+extras],[{k:r[k] for k in ('file_hash','pcm')} for r in reg])
    extra_folds=transfer_folds(rows,folds,extras);rows+=extras;folds=np.r_[folds,extra_folds]
    offsets=np.r_[0,np.cumsum([len(v) for v in features])];roff=np.r_[0,np.cumsum([len(v) for v in arrays])]
    np.savez_compressed(out,features=np.concatenate(features),offsets=offsets,folds=folds,file_hash=np.array([r['file_hash'] for r in rows]),reg_features=np.concatenate(arrays),reg_offsets=roff)
    (data/'restored-manifest.json').write_text(json.dumps({'rows':rows,'regression_only':reg,'base_count':len(rows)-len(extras),'restored_count':3,'shared_parent':diag['shared_desk_parent'],'regression_scope':'Three same-parent speaker replays, prior development; not independent accuracy'},ensure_ascii=False,indent=2))
    print('RESTORED_READY',len(rows),'windows',len(np.concatenate(features)),'cache_sha',sha(out),flush=True)


if __name__=='__main__':main()
