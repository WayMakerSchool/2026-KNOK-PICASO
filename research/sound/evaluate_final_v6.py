import os
os.environ.update(TF_CPP_MIN_LOG_LEVEL='3',TF_NUM_INTRAOP_THREADS='2',TF_NUM_INTEROP_THREADS='1',OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='1',VECLIB_MAXIMUM_THREADS='1')
from pathlib import Path
import json
import time
import wave
import numpy as np
from final_helpers import sha,verify,trials,results
HERE=Path(__file__).resolve().parent


def main():
    lock=json.loads((HERE/'final-lock.json').read_text());verify(lock);dest=HERE/'evaluation/v6-results.json'
    if dest.exists():raise RuntimeError('Preserve completed V6 comparison')
    if not (HERE/'fresh_started.json').exists():raise RuntimeError('Run frozen AST stage first')
    import tensorflow as tf
    tf.config.set_visible_devices([],'GPU');m=tf.lite.Interpreter(model_path=lock['baselines']['V6'],num_threads=2);m.allocate_tensors();inp=m.get_input_details()[0]['index'];out=m.get_output_details()[0]['index'];rows=trials();prob=[];good=[];errors=[];ms=[];started=time.monotonic()
    for i,r in enumerate(rows):
        began=time.monotonic();q=np.zeros(6,dtype='float32');okay=False;error=''
        try:
            if sha(r['path'])!=r['file_hash']:raise RuntimeError('V6 comparison WAV changed')
            with wave.open(r['path'],'rb') as w:x=np.frombuffer(w.readframes(w.getnframes()),'<i2').astype('float32')/32768
            starts=[0] if len(x)<=64000 else list(range(0,len(x)-64000+1,32000))
            if len(x)>64000 and starts[-1]+64000<len(x):starts.append(len(x)-64000)
            qs=[]
            for start in starts:
                part=x[start:start+64000];part=np.pad(part,(0,64000-len(part))).astype('float32');m.set_tensor(inp,part);m.invoke();qs.append(m.get_tensor(out)[0].copy())
            q=np.mean(qs,axis=0);okay=True
        except Exception as e:error=type(e).__name__+':'+str(e)
        prob.append(q);good.append(okay);errors.append(error);ms.append((time.monotonic()-began)*1000)
        if (i+1)%20==0:print('FROZEN_V6_TEST',i+1,'/120','seconds',round(time.monotonic()-started),flush=True)
    result=results(rows,prob,good,errors);dest.write_text(json.dumps({'scope':lock['scope'],'final_lock_sha256':sha(HERE/'final-lock.json'),'models':{'V6':result},'inference_ms':ms},ensure_ascii=False,allow_nan=False,indent=2));print('FINAL_PUBLIC_V6',result['correct'],'/120',result['accuracy'],flush=True)


if __name__=='__main__':main()
