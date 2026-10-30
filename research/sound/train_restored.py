import os
os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='2',VECLIB_MAXIMUM_THREADS='1')
from pathlib import Path
import sys
import json
import time
import numpy as np
import torch
HERE=Path(__file__).resolve().parent;OLD=HERE.parent/'wednesday_two_models_20261028/tuesday_public_20261027'
sys.path[:0]=[str(OLD/'round21'),str(OLD/'round14')]
import train_attention as training
from attention_head import predict,load
from experiment_core import LABELS
from safeguards import sha,ensure_training_open


def deadline():
    ensure_training_open(HERE)
    if time.time()>1793128500:raise RuntimeError('Do not start or continue fit after04:15KST')


def padded(flat,offsets):
    count=np.diff(offsets);v=np.zeros((len(count),int(count.max()),768),dtype='float32');m=np.zeros(v.shape[:2],dtype=bool)
    for i,n in enumerate(count):v[i,:n]=flat[offsets[i]:offsets[i+1]];m[i,:n]=True
    return v,m


def metrics(rows,q):
    pred=np.array(LABELS)[q.argmax(axis=1)].copy();pred[q.max(axis=1)<.65]='normal_or_unknown';correct=np.array([r['label'] for r in rows])==pred
    cells={};classes={}
    for l in LABELS:
        subset=[i for i,r in enumerate(rows) if r['label']==l];classes[l]=float(correct[subset].mean()) if subset else None
        for d in sorted({rows[i]['domain'] for i in subset}):
            groups={rows[i]['group'] for i in subset if rows[i]['domain']==d};cells[l+'|'+d]=float(np.mean([correct[[i for i in subset if rows[i]['domain']==d and rows[i]['group']==g]].mean() for g in groups]))
    present=[l for l in LABELS if classes[l] is not None]
    domainclass=float(np.mean([np.mean([v for k,v in cells.items() if k.startswith(l+'|')]) for l in present]))
    return {'row_accuracy':float(correct.mean()),'class_balanced':float(np.mean([classes[l] for l in present])),'evaluated_class_count':len(present),'domain_class_parent_balanced':domainclass,'per_class':classes,'cells':cells}


def main():
    deadline();training.ensure_time=deadline;torch.set_num_threads(2);torch.set_num_interop_threads(1);device='mps' if torch.backends.mps.is_available() else 'cpu';out=HERE/'restored';out.mkdir(exist_ok=True)
    if (out/'report.json').exists():raise RuntimeError('Preserve completed experiment')
    manifest=json.loads((HERE/'data/restored-manifest.json').read_text());rows=manifest['rows'];n=manifest['base_count'];started=time.monotonic()
    with np.load(HERE/'data/restored-bags.npz') as z:
        if z['file_hash'].tolist()!=[r['file_hash'] for r in rows]:raise ValueError('Cached identity mismatch')
        values,mask=padded(z['features'],z['offsets']);rv,rm=padded(z['reg_features'],z['reg_offsets']);folds=z['folds'].copy()
    q=np.zeros((len(rows),6),dtype='float32');groups=np.array([r['group'] for r in rows]);audit=[]
    for f in range(3):
        deadline();ti=np.flatnonzero(folds!=f);vi=np.flatnonzero(folds==f)
        if set(groups[ti])&set(groups[vi]):raise RuntimeError('Parent crosses folds')
        state=training.fit([rows[i] for i in ti],values[ti],mask[ti],device,20261028+f);torch.save(state[0],out/f'fold-{f}.pt')
        for start in range(0,len(vi),128):
            ids=vi[start:start+128];q[ids],_=predict(state,values[ids],mask[ids],device)
        audit.append({'fold':f,'train':len(ti),'validation':len(vi),'group_overlap':0});print('RESTORED_FOLD',f,'seconds',round(time.monotonic()-started),flush=True)
    state=training.fit(rows,values,mask,device,20261028);torch.save(state[0],out/'attention-head.pt');reg,_=predict(state,rv,rm,device);base_reg,_=predict(load(OLD/'round21/outputs/attention-head.pt',device),rv,rm,device)
    with np.load(OLD/'round21/outputs/oof.npz') as z:base=z['probabilities'].copy()
    regress=[]
    for r,a,b in zip(manifest['regression_only'],reg,base_reg):
        label=LABELS[int(a.argmax())] if a.max()>=.65 else 'normal_or_unknown'
        regress.append({'path':r['path'],'parent':r['parent'],'truth':r['label'],'prediction':label,'correct':label==r['label'],'score_not_accuracy':float(a.max()),'hammer_score':float(a[LABELS.index('hammering')]),'baseline_hammer':float(b[LABELS.index('hammering')])})
    a,_=predict(state,values[:8],mask[:8],device);b,_=predict(load(out/'attention-head.pt','cpu'),values[:8],mask[:8],'cpu');err=float(abs(a-b).max())
    if err>1e-4:raise RuntimeError('CPU/MPS numerical mismatch')
    report={'scope':'Adaptive grouped-source development; desk regression is previously seen one-parent compatibility only','baseline':metrics(rows[:n],base),'restored':metrics(rows[:n],q[:n]),'regression':regress,'regression_correct':sum(r['correct'] for r in regress),'folds':audit,'fit_regression_file_overlap':0,'cpu_mps_max_error':err,'model_sha256':sha(out/'attention-head.pt'),'runtime_seconds':time.monotonic()-started}
    np.savez_compressed(out/'oof.npz',probabilities=q,file_hash=np.array([r['file_hash'] for r in rows]),folds=folds);(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print('RESTORED_DONE',json.dumps({k:report[k] for k in ('regression_correct','cpu_mps_max_error','runtime_seconds')}),flush=True)
    print('BALANCED',report['baseline']['domain_class_parent_balanced'],report['restored']['domain_class_parent_balanced'],flush=True)


if __name__=='__main__':main()
