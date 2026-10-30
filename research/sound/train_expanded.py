import os
os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='2',VECLIB_MAXIMUM_THREADS='1')
from pathlib import Path
import sys
import json
import time
import numpy as np
import torch
from torch import nn
HERE=Path(__file__).resolve().parent;OLD=HERE.parent/'wednesday_two_models_20261028/tuesday_public_20261027'
sys.path[:0]=[str(OLD/'round21'),str(OLD/'round14')]
from attention_head import AttentionHead
from temporal_head import TemporalAttentionHead,load,predict
from train_attention import weights
from train_restored import padded,metrics,deadline
from experiment_core import LABELS,temporal_features
from restore_views import wav
from safeguards import sha


def fit(rows,values,mask,temporal,device,seed,recipe):
    deadline();torch.manual_seed(seed)
    if device=='mps':torch.mps.manual_seed(seed)
    w=weights(rows);mean=np.zeros(768);second=np.zeros(768)
    for i in range(len(rows)):
        v=values[i,mask[i]].astype('float64');mean+=w[i]*v.mean(axis=0);second+=w[i]*(v*v).mean(axis=0)
    mean/=w.sum();scale=np.sqrt(np.maximum(second/w.sum()-mean*mean,1e-10));tm=(temporal*w[:,None]).sum(axis=0)/w.sum();ts=np.sqrt(np.maximum(((temporal-tm)**2*w[:,None]).sum(axis=0)/w.sum(),1e-6))
    x=torch.from_numpy(((values-mean)/scale).astype('float32')).to(device);m=torch.from_numpy(mask).to(device);t=torch.from_numpy(((temporal-tm)/ts).astype('float32')).to(device);y=torch.tensor([LABELS.index(r['label']) for r in rows],device=device);wt=torch.tensor(w,dtype=torch.float32,device=device)
    if recipe=='semantic':
        from semantic_head import SemanticAttentionHead
        model=SemanticAttentionHead(mean,scale).to(device)
    else:model=(TemporalAttentionHead() if recipe=='temporal' else AttentionHead()).to(device)
    opt=torch.optim.AdamW(model.parameters(),lr=.001,weight_decay=.01);schedule=torch.optim.lr_scheduler.CosineAnnealingLR(opt,T_max=40,eta_min=.00001);lossfn=nn.CrossEntropyLoss(reduction='none');rng=torch.Generator().manual_seed(seed)
    for epoch in range(40):
        deadline();model.train();order=torch.randperm(len(rows),generator=rng).to(device)
        for start in range(0,len(rows),64):
            ids=order[start:start+64];opt.zero_grad(set_to_none=True);logits,_=model(x[ids],m[ids],t[ids]) if recipe=='temporal' else model(x[ids],m[ids]);loss=(lossfn(logits,y[ids])*wt[ids]).sum()/wt[ids].sum();loss.backward();opt.step()
        schedule.step()
    architecture={'temporal':'attention-plus-temporal64','semantic':'attention-plus-frozen-audioset-prior'}.get(recipe,'window768-hidden128-tanh-attention128-6')
    model.eval();payload={'state_dict':{k:v.detach().cpu() for k,v in model.state_dict().items()},'mean':torch.tensor(mean,dtype=torch.float32),'scale':torch.tensor(scale,dtype=torch.float32),'temporal_mean':torch.tensor(tm,dtype=torch.float32),'temporal_scale':torch.tensor(ts,dtype=torch.float32),'labels':LABELS,'threshold':.65,'architecture':architecture,'epochs':40,'seed':seed,'microphone_mass':.5,'research_only':True,'desk_restore':True,'new_public_fit':True}
    return payload,model


def main():
    recipe=sys.argv[1]
    if recipe not in ('attention','temporal'):raise ValueError('Only two predeclared candidate recipes')
    deadline();lock=HERE/'training.lock'
    try:fd=os.open(lock,os.O_WRONLY|os.O_CREAT|os.O_EXCL)
    except FileExistsError:raise RuntimeError('Existing training lock; inspect process rather than duplicate')
    os.write(fd,json.dumps({'pid':os.getpid(),'recipe':recipe,'started':time.time()}).encode());os.close(fd)
    try:run(recipe)
    finally:lock.unlink(missing_ok=True)


def run(recipe):
    torch.set_num_threads(2);torch.set_num_interop_threads(1);device='mps' if torch.backends.mps.is_available() else 'cpu';out=HERE/('expanded_'+recipe);out.mkdir(exist_ok=True)
    if (out/'report.json').exists():raise RuntimeError('Preserve completed candidate')
    manifest=json.loads((HERE/'data/expanded-manifest.json').read_text());rows=manifest['rows'];n=manifest['base_count'];started=time.monotonic()
    with np.load(HERE/'data/expanded-bags.npz') as z:
        if z['file_hash'].tolist()!=[r['file_hash'] for r in rows]:raise ValueError('Cache identity mismatch')
        values,mask=padded(z['features'],z['offsets']);rv,rm=padded(z['reg_features'],z['reg_offsets']);folds=z['folds'].copy();temp=z['temporal'].copy()
    rt=np.stack([temporal_features(wav(r['path'])[0]) for r in manifest['regression_only']]);q=np.zeros((len(rows),6),dtype='float32');groups=np.array([r['group'] for r in rows]);audit=[]
    for f in range(3):
        deadline();ti=np.flatnonzero(folds!=f);vi=np.flatnonzero(folds==f)
        if set(groups[ti])&set(groups[vi]):raise RuntimeError('Parent crosses folds')
        state=fit([rows[i] for i in ti],values[ti],mask[ti],temp[ti],device,20261028+f,recipe);torch.save(state[0],out/f'fold-{f}.pt')
        for start in range(0,len(vi),128):
            ids=vi[start:start+128];q[ids],_=predict(state,values[ids],mask[ids],temp[ids],device)
        audit.append({'fold':f,'train':len(ti),'validation':len(vi),'group_overlap':0});print('EXPANDED_FOLD',recipe,f,'seconds',round(time.monotonic()-started),flush=True)
    state=fit(rows,values,mask,temp,device,20261028,recipe);torch.save(state[0],out/'head.pt');reg,_=predict(state,rv,rm,rt,device)
    with np.load(OLD/'round21/outputs/oof.npz') as z:base=z['probabilities'].copy()
    regress=[]
    for r,a in zip(manifest['regression_only'],reg):
        label=LABELS[int(a.argmax())] if a.max()>=.65 else 'normal_or_unknown';regress.append({'path':r['path'],'parent':r['parent'],'truth':r['label'],'prediction':label,'correct':label==r['label'],'score_not_accuracy':float(a.max()),'probabilities':a.tolist()})
    a,_=predict(state,values[:8],mask[:8],temp[:8],device);b,_=predict(load(out/'head.pt','cpu'),values[:8],mask[:8],temp[:8],'cpu');err=float(abs(a-b).max())
    if err>1e-4:raise RuntimeError('CPU/MPS numerical mismatch')
    before=metrics(rows[:n],base);after=metrics(rows[:n],q[:n]);reasons=[]
    if sum(r['correct'] for r in regress)!=3:reasons.append('desk_regression_not3of3')
    if after['domain_class_parent_balanced']<before['domain_class_parent_balanced']-.03:reasons.append('common_balanced_drop_over3points')
    if any(after['per_class'][l]<before['per_class'][l]-.05 for l in LABELS):reasons.append('common_class_drop_over5points')
    report={'scope':'Adaptive source-held3fold development; same-parent desk regression is not independent','recipe':recipe,'baseline_common1779':before,'candidate_common1779':after,'expanded_development':metrics(rows,q),'new_training_source_held':metrics(rows[1782:],q[1782:]),'regression':regress,'regression_correct':sum(r['correct'] for r in regress),'eligible_development':not reasons,'rejection_reasons':reasons,'folds':audit,'model_sha256':sha(out/'head.pt'),'cpu_mps_max_error':err,'runtime_seconds':time.monotonic()-started,'heldout_public_audio_read':False}
    np.savez_compressed(out/'oof.npz',probabilities=q,file_hash=np.array([r['file_hash'] for r in rows]),folds=folds);(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print('EXPANDED_DONE',recipe,'eligible',not reasons,'desk',report['regression_correct'],'balanced',after['domain_class_parent_balanced'],'classbalanced',after['class_balanced'],'seconds',round(time.monotonic()-started),flush=True)


if __name__=='__main__':main()
