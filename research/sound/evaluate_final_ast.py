import os
os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='2',VECLIB_MAXIMUM_THREADS='1')
from datetime import datetime
from pathlib import Path
import json
import sys
import time
from zoneinfo import ZoneInfo
import numpy as np
HERE=Path(__file__).resolve().parent;OLD=HERE.parent/'wednesday_two_models_20261028/tuesday_public_20261027';LEGACY=HERE.parent/'silent_root_cause_20261025'
sys.path[:0]=[str(OLD/'round21'),str(OLD/'round14'),str(OLD.parent),str(LEGACY)]
from ast_worker import load_runtime
from extract_bags import bag
from temporal_head import load,predict
from portable_head import predict_head
from experiment_core import temporal_features
from restore_views import wav
from final_helpers import sha,verify,trials,results


def main():
    lock=json.loads((HERE/'final-lock.json').read_text());verify(lock);dest=HERE/'evaluation/ast-results.json';dest.parent.mkdir(exist_ok=True)
    if dest.exists():raise RuntimeError('Final AST evaluation already complete; preserve result')
    marker=HERE/'fresh_started.json';identity=sha(HERE/'final-lock.json')
    if marker.exists():
        if json.loads(marker.read_text())['final_lock_sha256']!=identity:raise RuntimeError('Cannot resume with a changed model or test')
    else:marker.write_text(json.dumps({'started_at':datetime.now(ZoneInfo('Asia/Seoul')).isoformat(),'final_lock_sha256':identity,'no_more_training_or_selection':True},indent=2))
    s=json.loads((HERE/'STATE.json').read_text());s.update(phase='frozen_one_shot_final_evaluation',fresh_test_consumed=True,active_sessions=['evaluate_final_ast.py']);(HERE/'STATE.json').write_text(json.dumps(s,indent=2))
    rows=trials();state=load_runtime(LEGACY);device=state[9];heads={'selected':load(lock['model_path'],device),'R21':load(lock['baselines']['R21'],device)}
    with np.load(lock['baselines']['AST_legacy']) as z:legacy={k:z[k].copy() for k in z.files}
    cache=HERE/'evaluation/per-trial';cache.mkdir(exist_ok=True);prob={k:[] for k in ['selected','R21','AST_legacy']};good=[];errors=[];elapsed=[];started=time.monotonic()
    for i,r in enumerate(rows):
        path=cache/(str(i).zfill(3)+'.npz')
        if path.exists():
            with np.load(path) as z:
                if str(z['lock_sha256'])!=identity or str(z['file_hash'])!=r['file_hash']:raise RuntimeError('Partial evaluation cache changed')
                for n in prob:prob[n].append(z[n].copy())
                good.append(bool(z['good']));errors.append(str(z['error']));elapsed.append(float(z['elapsed_ms']))
            continue
        began=time.monotonic();error='';okay=False;qs={n:np.zeros(6,dtype='float32') for n in prob}
        try:
            if sha(r['path'])!=r['file_hash']:raise RuntimeError('Final WAV changed')
            audio,pcm=wav(r['path'])
            if pcm!=r['pcm']:raise RuntimeError('Final PCM changed')
            v,positions=bag(audio,state)
            if not len(v):raise ValueError('No usable signal; failure counts wrong, including normal truth')
            values=v[None,:,:];mask=np.ones(values.shape[:2],dtype=bool);temporal=temporal_features(audio)[None,:]
            for n,head in heads.items():qs[n]=predict(head,values,mask,temporal,device)[0][0]
            qs['AST_legacy']=predict_head(legacy,v.mean(0)[None,:])[0];okay=True
        except Exception as e:error=type(e).__name__+':'+str(e)
        ms=(time.monotonic()-began)*1000;np.savez_compressed(path,**qs,good=np.array(okay),error=np.array(error),elapsed_ms=np.array(ms),lock_sha256=np.array(identity),file_hash=np.array(r['file_hash']))
        for n in prob:prob[n].append(qs[n])
        good.append(okay);errors.append(error);elapsed.append(ms)
        if (i+1)%20==0:print('FROZEN_AST_TEST',i+1,'/120','seconds',round(time.monotonic()-started),flush=True)
    report={'scope':lock['scope'],'final_lock_sha256':identity,'models':{n:results(rows,prob[n],good,errors) for n in prob},'inference_ms':elapsed,'device':device,'cpu_threads':state[1].get_num_threads(),'no_post_test_fitting':True};dest.write_text(json.dumps(report,ensure_ascii=False,allow_nan=False,indent=2))
    for n,r in report['models'].items():print('FINAL_PUBLIC',n,r['correct'],'/120',r['accuracy'],'processed',r['processing_success'],flush=True)


if __name__=='__main__':main()
