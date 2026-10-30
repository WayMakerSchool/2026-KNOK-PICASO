"""Fixed, label-free input policy shared by CPU and MPS feature extraction."""
import hashlib,json
import numpy as np

def prepare_view(samples,view):
    sizes={'activity4':64000,'context10':160000}
    if view not in sizes:raise ValueError('Unknown fixed view')
    x=np.asarray(samples,dtype='float32')
    if x.ndim!=1 or not len(x) or not np.isfinite(x).all():raise ValueError('Finite nonempty mono input required')
    size=sizes[view];last=max(0,len(x)-size);start=0
    if last:
        centered=x.astype('float64')-x.astype('float64').mean();n=len(x)//1600
        envelope=np.sqrt(np.mean(centered[:n*1600].reshape(n,1600)**2,axis=1))
        c=np.r_[0.,np.cumsum(envelope)];width=size//1600;scores=c[width:]-c[:-width]
        index=int(np.argmax(scores));start=index*1600
        if last%1600:
            tail=np.sqrt(np.mean(centered[last:last+size].reshape(width,1600)**2,axis=1)).sum()
            if tail>scores[index]+1e-12:start=last
    part=x[start:start+size].copy();part=np.pad(part,(0,max(0,size-len(part))));part-=part.mean()
    peak=float(np.max(abs(part)));rms=float(np.sqrt(np.mean(part.astype('float64')**2)))
    floor=peak<=2/32768 or rms<=1/32768
    return (np.zeros(size,dtype='float32')if floor else (part*.8/max(peak,1e-6)).astype('float32')),start,floor

def cache_key(audio_sha,weights_sha,code_sha,view):
    if not all([audio_sha,weights_sha,code_sha,view]):raise ValueError('Complete cache identity required')
    return hashlib.sha256(json.dumps([audio_sha,weights_sha,code_sha,view],separators=(',',':')).encode()).hexdigest()
