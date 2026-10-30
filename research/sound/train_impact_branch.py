import os
os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='2',VECLIB_MAXIMUM_THREADS='1')
from pathlib import Path
import hashlib
import json
import sys
import time
import joblib
import numpy as np
from impact_branch import fit_calibrated,predict
from safeguards import ensure_training_open
HERE=Path(__file__).resolve().parent


def main():
    ensure_training_open(HERE)
    c=float(sys.argv[1])
    if c not in (1.,10.):raise ValueError('Only two predeclared C values')
    if time.time()>1793128500:raise RuntimeError('04:15training stop')
    out=HERE/('impact_C'+str(int(c)));out.mkdir(exist_ok=True)
    if (out/'binary-oof.npz').exists():raise RuntimeError('Preserve binary experiment')
    lock=HERE/'training.lock';fd=os.open(lock,os.O_WRONLY|os.O_CREAT|os.O_EXCL);os.write(fd,json.dumps({'pid':os.getpid(),'recipe':out.name,'started':time.time()}).encode());os.close(fd)
    try:
        manifest=json.loads((HERE/'data/expanded-manifest.json').read_text());rows=manifest['rows']
        with np.load(HERE/'data/expanded-bags.npz') as z:
            if z['file_hash'].tolist()!=[r['file_hash'] for r in rows]:raise ValueError('Cache identity changed')
            offsets=z['offsets'];x=np.stack([z['features'][offsets[i]:offsets[i+1]].mean(0) for i in range(len(rows))]);x=np.c_[x,z['temporal']];folds=z['folds'].copy();ro=z['reg_offsets'];rx=np.stack([z['reg_features'][ro[i]:ro[i+1]].mean(0) for i in range(3)])
        old=HERE.parent/'wednesday_two_models_20261028/tuesday_public_20261027';sys.path.insert(0,str(old/'round14'));from experiment_core import temporal_features
        import wave
        rt=[]
        for r in manifest['regression_only']:
            with wave.open(r['path'],'rb') as w:audio=np.frombuffer(w.readframes(w.getnframes()),'<i2').astype('float32')/32768
            rt.append(temporal_features(audio))
        rx=np.c_[rx,np.stack(rt)];valid=np.array([r['label'] in ('hammering','instant_impact') for r in rows]);q=np.zeros(len(rows));audit=[];started=time.monotonic()
        for f in range(3):
            ti=np.flatnonzero((folds!=f)&valid);vi=np.flatnonzero(folds==f);model=fit_calibrated([rows[i] for i in ti],x[ti],folds[ti],c);q[vi]=predict(model,x[vi]);joblib.dump(model,out/f'fold-{f}.joblib');audit.append({'fold':f,'train':len(ti),'validation':len(vi),'calibration':model['inner_source_held']});print('IMPACT_FOLD',c,f,round(time.monotonic()-started),flush=True)
        ti=np.flatnonzero(valid);model=fit_calibrated([rows[i] for i in ti],x[ti],folds[ti],c);joblib.dump(model,out/'branch.joblib');reg=predict(model,rx);np.savez_compressed(out/'binary-oof.npz',hammer_probability=q,regression_hammer_probability=reg,file_hash=np.array([r['file_hash'] for r in rows]))
        (out/'training.json').write_text(json.dumps({'scope':'Nested-source-calibrated binary development; no new public test','C':c,'outer_folds':audit,'full_calibration':model['inner_source_held'],'feature_shape':list(x.shape),'model_sha256':hashlib.sha256((out/'branch.joblib').read_bytes()).hexdigest(),'runtime_seconds':time.monotonic()-started,'regression_scope':'One previously exposed desk parent,3replays'},indent=2));print('IMPACT_DONE',c,'seconds',round(time.monotonic()-started),'desk_hammer',reg.tolist(),flush=True)
    finally:lock.unlink(missing_ok=True)


if __name__=='__main__':main()
