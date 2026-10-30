import os
os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='2',VECLIB_MAXIMUM_THREADS='1')
import json
from pathlib import Path
import sys
import time
import numpy as np
HERE=Path(__file__).resolve().parent;PREV=HERE.parent;R14=PREV/'round14';R19=PREV/'round19';OLD=PREV.parents[1]/'silent_root_cause_20261025'
sys.path[:0]=[str(R14),str(PREV.parent),str(PREV)]
from ast_worker import load_runtime,_window_starts
from build_pool import waveform
from prepare_public import sha,ensure_time


def bag(audio,state):
    np_,torch,extractor,model,head,labels,decide,predict_head,prepare_view,device,manifest=state;vectors=[];positions=[]
    for start in _window_starts(len(audio)):
        window,_,low=prepare_view(audio[start:start+64000],'activity4')
        if low:continue
        values=extractor(window,sampling_rate=16000,return_tensors='pt').input_values.to(device)
        with torch.inference_mode():vectors.append(model.audio_spectrogram_transformer(input_values=values,return_dict=True).pooler_output[0].cpu().numpy().astype('float32'))
        positions.append(start)
    return (np.stack(vectors) if vectors else np.zeros((0,768),dtype='float32')),positions


def main():
    ensure_time();dest=HERE/'data/bags.npz';dest.parent.mkdir(exist_ok=True)
    if dest.exists():raise RuntimeError('Preserve bag cache')
    rows=json.loads((R19/'data/manifest.json').read_text())['rows'];state=load_runtime(OLD);cache=HERE/'data/window-cache';cache.mkdir(exist_ok=True);arrays=[];starts=[];offsets=[0];began=time.monotonic()
    fingerprint=sha(OLD/'ast-model/model.safetensors')+sha(Path(__file__))
    for j,r in enumerate(rows):
        ensure_time();path=Path(r['path'])
        if sha(path)!=r['file_hash']:raise RuntimeError('Bag source changed')
        entry=cache/(r['file_hash']+'.npz')
        if entry.exists():
            with np.load(entry,allow_pickle=False) as z:
                if str(z['fingerprint'])!=fingerprint:raise RuntimeError('Bag cache encoder/code mismatch')
                v=z['features'].copy();positions=z['starts'].tolist()
        else:
            v,positions=bag(waveform(path),state);np.savez_compressed(entry,features=v,starts=np.array(positions),fingerprint=np.array(fingerprint))
        arrays.append(v);starts.extend(positions);offsets.append(offsets[-1]+len(v))
        if (j+1)%80==0 or j==len(rows)-1:print('BAG_FEATURES',j+1,'/',len(rows),'windows',offsets[-1],'seconds',round(time.monotonic()-began),flush=True)
    np.savez_compressed(dest,features=np.concatenate(arrays),offsets=np.array(offsets),starts=np.array(starts),file_hash=np.array([r['file_hash'] for r in rows]))
    print('BAGS_COMPLETE',len(rows),offsets[-1],sha(dest),flush=True)


if __name__=='__main__':main()
