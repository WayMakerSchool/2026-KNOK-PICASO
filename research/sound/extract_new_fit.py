import os
os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='2',VECLIB_MAXIMUM_THREADS='1')
from pathlib import Path
import hashlib
import json
import sys
import time
import numpy as np
HERE=Path(__file__).resolve().parent;OLD=HERE.parent/'wednesday_two_models_20261028/tuesday_public_20261027'
sys.path[:0]=[str(OLD/'round21'),str(OLD/'round14'),str(OLD.parent)]
from extract_bags import bag
from experiment_core import temporal_features
from ast_worker import load_runtime
from safeguards import sha,assert_disjoint
from restore_views import wav


def main():
    out=HERE/'data/expanded-bags.npz'
    if out.exists():raise RuntimeError('Preserve expanded cache')
    meta=json.loads((HERE/'data/new_public/manifest.json').read_text());new=[r for r in meta['rows'] if r['role']=='fit'];held=[r for r in meta['rows'] if r['role']=='sound_test'];manifest=json.loads((HERE/'data/restored-manifest.json').read_text());rows=manifest['rows'];base_n=len(rows)
    # No heldout audio is passed to the encoder or any fitting routine here.
    assert_disjoint([{k:r[k] for k in ('parent','file_hash','pcm','source_sha','uploader_key') if r.get(k)} for r in rows+new],[{k:r[k] for k in ('parent','file_hash','pcm','source_sha','uploader_key') if r.get(k)} for r in held])
    with np.load(HERE/'data/restored-bags.npz') as z:
        offsets=z['offsets'];features=[z['features'][offsets[i]:offsets[i+1]].copy() for i in range(len(rows))];folds=z['folds'].tolist();regfeat=z['reg_features'].copy();regoff=z['reg_offsets'].copy()
    group_lookup={r['source_group']:(r['group'],int(f)) for r,f in zip(rows,folds) if r.get('source_group')}
    with np.load(OLD/'round19/data/development.npz') as z:temporal=list(z['temporal'].copy())
    for r in rows[len(temporal):]:temporal.append(temporal_features(wav(r['path'])[0]))
    state=load_runtime(HERE.parent/'silent_root_cause_20261025');began=time.monotonic();cache=HERE/'data/new-fit-window-cache';cache.mkdir(exist_ok=True)
    for j,r in enumerate(new):
        r=dict(r,id='new:'+r['track_id'],origin='new_public_training',source_type=r['family'])
        if r['kind']=='APT':r['domain']='apt_'+r['building'];r['source_group']=r['domain']+':'+r['position'].split('-')[0]
        else:r['domain']='FSD'
        if r['source_group'] in group_lookup:r['group'],f=group_lookup[r['source_group']]
        else:
            r['group']='new:'+r['source_group'];f=int(hashlib.sha256((r['group']+'20261028').encode()).hexdigest(),16)%3
        group_lookup[r['source_group']]=(r['group'],f)
        if sha(r['path'])!=r['file_hash']:raise RuntimeError('New source changed')
        audio,pcm=wav(r['path'])
        if pcm!=r['pcm']:raise RuntimeError('New PCM mismatch')
        v,positions=bag(audio,state)
        if not len(v):raise ValueError('No valid window; do not silently discard training case')
        np.savez_compressed(cache/(r['file_hash']+'.npz'),features=v,starts=np.array(positions));features.append(v);folds.append(f);rows.append(r);temporal.append(temporal_features(audio))
        if (j+1)%20==0 or j==len(new)-1:print('NEW_FIT_FEATURES',j+1,'/',len(new),'seconds',round(time.monotonic()-began),flush=True)
    groups={}
    for r,f in zip(rows,folds):groups.setdefault(r['group'],set()).add(f)
    if any(len(v)!=1 for v in groups.values()):raise ValueError('Source group crosses validation folds')
    np.savez_compressed(out,features=np.concatenate(features),offsets=np.r_[0,np.cumsum([len(v) for v in features])],folds=np.array(folds),temporal=np.stack(temporal),file_hash=np.array([r['file_hash'] for r in rows]),reg_features=regfeat,reg_offsets=regoff)
    (HERE/'data/expanded-manifest.json').write_text(json.dumps({'rows':rows,'base_count':1779,'restored_count':3,'new_count':len(new),'regression_only':manifest['regression_only'],'new_public_manifest_sha256':sha(HERE/'data/new_public/manifest.json'),'test_audio_read_for_fitting':False},ensure_ascii=False,indent=2));print('EXPANDED_READY',len(rows),sha(out),flush=True)


if __name__=='__main__':main()
