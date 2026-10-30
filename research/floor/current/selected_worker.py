"""One JSON-lines worker for the separately packaged research sound/floor model."""
import os
os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='2',VECLIB_MAXIMUM_THREADS='1',NUMBA_NUM_THREADS='1')
import hashlib
import json
from pathlib import Path
import sys
import time
HERE=Path(__file__).resolve().parent;PROJECT=HERE.parents[2]
sys.path.insert(0,str(PROJECT))


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def manifest():
    data=json.loads((HERE/'model-manifest.json').read_text())
    for name,digest in data['files'].items():
        if sha(HERE/name)!=digest:raise RuntimeError('Research delivery hash mismatch')
    return data


def load_sound(data):
    from ast_worker import load_runtime
    from attention_head import load
    runtime=load_runtime(Path(data['backbone_root']));head=load(HERE/'models/sound-attention.pt',runtime[9]);return runtime,head


def sound(path,data,state):
    from ast_worker import _read_samples,_window_starts
    from attention_head import predict
    runtime,head=state;np,torch,extractor,model,_,labels,_,_,prepare_view,device,_=runtime
    started=time.monotonic();audio=_read_samples(path,np);starts=_window_starts(len(audio));vectors=[]
    for start in starts:
        part,_,low=prepare_view(audio[start:start+64000],'activity4')
        if low:continue
        values=extractor(part,sampling_rate=16000,return_tensors='pt').input_values.to(device)
        with torch.inference_mode():vectors.append(model.audio_spectrogram_transformer(input_values=values,return_dict=True).pooler_output[0].cpu().numpy().astype('float32'))
    if not vectors:q=np.array([0.,0.,0.,0.,1.,0.]);label='normal_or_unknown';score=0.;quality='low_signal'
    else:
        embeddings=np.stack(vectors)[None,:,:];q=predict(head,embeddings,np.ones(embeddings.shape[:2],dtype=bool),device)[0][0]
        index=int(np.argmax(q));score=float(q[index]);label=labels[index] if score>=.65 else 'normal_or_unknown';quality='normal'
    return {'label':label,'top_label':labels[int(np.argmax(q))],'score':score,'probabilities':{k:float(q[i]) for i,k in enumerate(labels)},
      'signal_quality':quality,'model_version':data['sound_model_version'],'window_count':len(starts),'window_starts':starts,
      'window_policy':'4s-full-record-attention-stride2s-r21','inference_ms':round((time.monotonic()-started)*1000),'device':device,'cpu_threads':torch.get_num_threads()}


def load_floor(data):
    sys.path.insert(0,data['floor_feature_root']);import joblib;import improve_floor
    model=joblib.load(HERE/'models/floor-svm.joblib')
    if model['feature_dimensions']!=226 or model['feature_version']!=improve_floor.FEATURE_VERSION:raise RuntimeError('Floor feature contract mismatch')
    return improve_floor,model


def floor(path,receiver,data,state):
    if receiver not in ('lower','upper'):raise ValueError('Registered receiver position required')
    started=time.monotonic();feature,model=state;vector=feature.audio_features(path)['shape'][None,:];label=str(model['pipeline'].predict(vector)[0]).split('|')[-1]
    if label not in ('below','same','above'):raise ValueError('Invalid floor prediction')
    return {'label':label,'model_version':data['floor_model_version'],'receiver_position':receiver,'inference_ms':round((time.monotonic()-started)*1000)}


def main():
    kind=sys.argv[1];data=manifest();state=load_sound(data) if kind=='sound' else load_floor(data);version=data['sound_model_version'] if kind=='sound' else data['floor_model_version']
    print(json.dumps({'status':'ready','model_version':version}),flush=True)
    for line in sys.stdin:
        try:
            req=json.loads(line);path=Path(req['wav_path']);out=sound(path,data,state) if kind=='sound' else floor(path,req['receiver_position'],data,state);print(json.dumps(out,allow_nan=False),flush=True)
        except Exception as exc:print(json.dumps({'error':type(exc).__name__+': '+str(exc)}),flush=True)


if __name__=='__main__':main()
