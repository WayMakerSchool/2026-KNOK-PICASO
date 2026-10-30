import os
os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='2',VECLIB_MAXIMUM_THREADS='1')
from pathlib import Path
import hashlib
import json
import sys
import time
HERE=Path(__file__).resolve().parent


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    cfg=json.loads((HERE/'model-manifest.json').read_text());root=Path(cfg['source_root']);study=Path(cfg['study_root']);old=study/'tuesday_public_20261027';sys.path[:0]=[str(root),str(old/'round21'),str(old/'round14'),str(study)]
    for path,digest in cfg['frozen_artifacts'].items():
        if sha(path)!=digest:raise RuntimeError('Frozen dependency changed:'+path)
    if sha(HERE/cfg['model_file'])!=cfg['model_sha256']:raise RuntimeError('Packaged model changed')
    from ast_worker import load_runtime,_window_starts
    from extract_bags import bag
    from temporal_head import load,predict
    from experiment_core import temporal_features
    from restore_views import wav
    state=load_runtime(Path(cfg['backbone_root']));np,torch,*_=state;device=state[9];labels=state[5];head=load(HERE/cfg['model_file'],device)
    print(json.dumps({'status':'ready','model_version':cfg['model_version']}),flush=True)
    for line in sys.stdin:
        try:
            req=json.loads(line);start=time.monotonic();audio,pcm=wav(req['wav_path']);starts=_window_starts(len(audio));vectors,positions=bag(audio,state)
            if len(vectors):
                v=vectors[None,:,:];q=predict(head,v,np.ones(v.shape[:2],dtype=bool),temporal_features(audio)[None,:],device)[0][0];score=float(q.max());label=labels[int(q.argmax())] if score>=.65 else 'normal_or_unknown';quality='normal'
            else:q=np.array([0.,0.,0.,0.,1.,0.]);score=0.;label='normal_or_unknown';quality='low_signal'
            result={'label':label,'top_label':labels[int(q.argmax())],'score':score,'probabilities':{label:float(q[i]) for i,label in enumerate(labels)},'signal_quality':quality,'model_version':cfg['model_version'],'window_count':len(starts),'window_starts':starts,'window_policy':'4s-full-record-attention-semantic-prior-stride2s-desk-repair','inference_ms':round((time.monotonic()-start)*1000),'device':device,'cpu_threads':torch.get_num_threads()};print(json.dumps(result,allow_nan=False),flush=True)
        except Exception as e:print(json.dumps({'error':type(e).__name__+':'+str(e)}),flush=True)


if __name__=='__main__':main()
