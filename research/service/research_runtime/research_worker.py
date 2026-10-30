"""JSON-lines processes for the frozen research-only V6, AST R1 and floor models."""
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import time
import wave

HERE=Path(__file__).resolve().parent;STUDY=HERE.parents[1];PROJECT=HERE.parents[2];IMPROVEMENT=PROJECT.parent
sys.path.insert(0,str(PROJECT));sys.path.insert(0,str(STUDY))
from run_local_service import DEFAULT_AST_MODEL,DEFAULT_FLOOR_MODEL


def sha(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()


def samples(path,np):
    with wave.open(str(path),'rb') as audio:
        if (audio.getframerate(),audio.getnchannels(),audio.getsampwidth(),audio.getcomptype())!=(16000,1,2,'NONE'):
            raise ValueError('16kHz mono PCM16 required')
        data=np.frombuffer(audio.readframes(audio.getnframes()),'<i2').astype('float32')/32768
    if not len(data):raise ValueError('Empty WAV')
    return data


def load_v6():
    os.environ.update(TF_CPP_MIN_LOG_LEVEL='3',TF_NUM_INTRAOP_THREADS='2',TF_NUM_INTEROP_THREADS='1',
      OPENBLAS_NUM_THREADS='1',VECLIB_MAXIMUM_THREADS='1')
    import numpy as np
    import tensorflow as tf
    lock=json.loads((STUDY/'round8/outputs/selection-frozen.json').read_text());path=Path(lock['v6_path'])
    if sha(path)!=lock['artifacts'][str(path)]:raise RuntimeError('V6 model hash mismatch')
    model=tf.lite.Interpreter(model_path=str(path),num_threads=2);model.allocate_tensors()
    inp=model.get_input_details()[0];out=model.get_output_details()[0]
    if inp['shape'].tolist()!=[64000] or out['shape'].tolist()!=[1,6]:raise RuntimeError('Unexpected V6 input/output')
    labels=['dragging_furniture','footstep','hammering','instant_impact','normal_or_unknown','vacuum_cleaner']
    return np,model,inp['index'],out['index'],labels


def predict_v6(path,state):
    from ast_worker import _window_starts
    np,model,inp,out,labels=state;x=samples(path,np);started=time.perf_counter();starts=_window_starts(len(x))
    centered=x.astype('float64')-float(x.mean())
    low=(float(np.max(np.abs(centered)))<=2/32768 or float(np.sqrt(np.mean(centered*centered)))<=1/32768)
    if low:q=np.array([0.,0.,0.,0.,1.,0.],dtype='float32');score=0.;label='normal_or_unknown';quality='quantization_limited'
    else:
        probabilities=[]
        for start in starts:
            window=x[start:start+64000];window=np.pad(window,(0,max(0,64000-len(window)))).astype('float32')
            model.set_tensor(inp,window);model.invoke();probabilities.append(model.get_tensor(out)[0].copy())
        q=np.mean(probabilities,axis=0)
        if q.shape!=(6,) or not np.isfinite(q).all() or abs(float(q.sum())-1)>1e-4:raise RuntimeError('Invalid V6 output')
        top=int(np.argmax(q));score=float(q[top]);label=labels[top] if score>=.65 else 'normal_or_unknown';quality='normal'
    return {'label':label,'score':score,'probabilities':dict(zip(labels,[float(v) for v in q])),
      'signal_quality':quality,'model_version':'knok-v6-preserved','window_count':len(starts),'window_starts':starts,
      'window_policy':'v6-4s-mean-probability-stride2s-v1','inference_ms':round((time.perf_counter()-started)*1000)}


def load_r1():
    from ast_worker import load_runtime
    state=load_runtime(DEFAULT_AST_MODEL);np=state[0]
    lock=json.loads((STUDY/'round8/outputs/selection-frozen.json').read_text());path=Path(lock['r1_selection'])
    if sha(path)!=lock['artifacts'][str(path)]:raise RuntimeError('AST R1 selection hash mismatch')
    selected=json.loads(path.read_text())['sound']
    for field in ('adapted','gate'):
        if sha(selected[field+'_path'])!=selected[field+'_sha256']:raise RuntimeError('AST '+field+' hash mismatch')
    with np.load(selected['adapted_path'],allow_pickle=False) as z:adapted={k:z[k].copy() for k in z.files}
    with np.load(selected['gate_path'],allow_pickle=False) as z:gate={k:z[k].copy() for k in z.files}
    return state,adapted,gate


def predict_r1(path,extra):
    from ast_worker import _window_starts
    state,adapted,gate=extra
    np,torch,extractor,model,head,labels,decide,predict_head,prepare_view,device,manifest=state
    x=samples(path,np);starts=_window_starts(len(x));embeddings=[];started=time.perf_counter()
    for start in starts:
        segment,_,low=prepare_view(x[start:start+64000],'activity4')
        if low:continue
        values=extractor(segment,sampling_rate=16000,return_tensors='pt').input_values.to(device)
        with torch.inference_mode():
            embedding=model.audio_spectrogram_transformer(input_values=values,return_dict=True).pooler_output.cpu().numpy().astype('float32')
        embeddings.append(embedding[0])
    if not embeddings:q=np.array([[0.,0.,0.,0.,1.,0.]],dtype='float32');score=0.;label='normal_or_unknown';quality='low_signal'
    else:
        value=np.mean(np.stack(embeddings),axis=0,keepdims=True,dtype='float32')
        logit=np.einsum('ij,j->i',(value-gate['mean'])/gate['scale'],gate['coef'],optimize=False)+gate['intercept']
        domain=float((1/(1+np.exp(-np.clip(logit,-60,60))))[0]);q=predict_head(adapted if domain>=.8 else head,value)
        if q.shape!=(1,6) or not np.isfinite(q).all():raise RuntimeError('Invalid AST output')
        score=float(q[0].max());label=labels[int(decide(q)[0])];quality='normal'
    return {'label':label,'score':score,'probabilities':dict(zip(labels,[float(v) for v in q[0]])),
      'signal_quality':quality,'model_version':'knok-research-ast-apartment-gate-r1',
      'window_count':len(starts),'window_starts':starts,'window_policy':'4s-mean-embedding-stride2s-v1',
      'inference_ms':round((time.perf_counter()-started)*1000)}


def load_floor():
    os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='1',VECLIB_MAXIMUM_THREADS='1',NUMBA_NUM_THREADS='1')
    import joblib
    import numpy as np
    sys.path.insert(0,str(DEFAULT_FLOOR_MODEL));import improve_floor
    lock=json.loads((STUDY/'round6/outputs/selection-frozen.json').read_text());path=Path(lock['selected_model'])
    if sha(path)!=lock['selected_sha256']:raise RuntimeError('Floor candidate hash mismatch')
    model=joblib.load(path)
    if model['feature_version']!=improve_floor.FEATURE_VERSION or model['feature_dimensions']!=226:raise RuntimeError('Floor features incompatible')
    return improve_floor,model,np,lock['selected']


def predict_floor(path,receiver,state):
    if receiver not in ('lower','upper'):raise ValueError('Registered receiver position required')
    feature,model,np,selected=state;started=time.perf_counter();vector=feature.audio_features(path)['shape']
    if vector.shape!=(226,) or not np.isfinite(vector).all():raise RuntimeError('Invalid floor features')
    label=str(model['pipeline'].predict(vector[None,:])[0])
    if label not in ('above','below','same'):raise RuntimeError('Invalid floor label')
    return {'label':label,'model_version':'knok-research-floor-'+selected+'-20261027',
      'receiver_position':receiver,'inference_ms':round((time.perf_counter()-started)*1000)}


def main():
    if len(sys.argv)!=2 or sys.argv[1] not in ('v6','r1','floor'):raise SystemExit('v6|r1|floor')
    kind=sys.argv[1];state={'v6':load_v6,'r1':load_r1,'floor':load_floor}[kind]()
    version={'v6':'knok-v6-preserved','r1':'knok-research-ast-apartment-gate-r1',
      'floor':'knok-research-floor-'+(state[3] if kind=='floor' else 'unused')+'-20261027'}[kind]
    print(json.dumps({'status':'ready','model_version':version}),flush=True)
    for line in sys.stdin:
        try:
            request=json.loads(line);path=Path(request['wav_path'])
            result=predict_v6(path,state) if kind=='v6' else (predict_r1(path,state) if kind=='r1' else predict_floor(path,request.get('receiver_position',''),state))
            print(json.dumps(result,allow_nan=False),flush=True)
        except Exception as error:print(json.dumps({'error':f'{type(error).__name__}: {error}'}),flush=True)


if __name__=='__main__':main()
