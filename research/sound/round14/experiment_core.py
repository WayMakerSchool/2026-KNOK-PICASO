"""Deterministic signal features and provenance-aware research grouping."""
from collections import Counter
import numpy as np
from scipy.signal import find_peaks

LABELS=['dragging_furniture','footstep','hammering','instant_impact','normal_or_unknown','vacuum_cleaner']


def connected_groups(rows):
    parents=list(range(len(rows)))
    def root(i):
        while parents[i]!=i:
            parents[i]=parents[parents[i]];i=parents[i]
        return i
    seen={}
    for i,row in enumerate(rows):
        for key in ('parent','pcm','file_hash','source_sha','uploader_key','source_group'):
            val=row.get(key)
            if not val:continue
            token=(key,str(val))
            if token in seen:parents[root(i)]=root(seen[token])
            else:seen[token]=i
    return np.array(['component_'+str(root(i)) for i in range(len(rows))])


def source_weights(rows):
    labels=sorted({r['label'] for r in rows});domains={l:{r['domain'] for r in rows if r['label']==l} for l in labels}
    groups={(l,d):{r['group'] for r in rows if r['label']==l and r['domain']==d} for l in labels for d in domains[l]}
    count=Counter((r['label'],r['domain'],r['group']) for r in rows)
    w=np.array([1/len(labels)/len(domains[r['label']])/len(groups[(r['label'],r['domain'])])/count[(r['label'],r['domain'],r['group'])] for r in rows])
    return w*len(rows)/w.sum()


def temporal_features(samples,rate=16000):
    x=np.asarray(samples,dtype=np.float64)
    if rate!=16000 or x.ndim!=1 or len(x)==0 or not np.isfinite(x).all():raise ValueError('finite 16kHz mono waveform required')
    x=x-float(np.mean(x));peak=float(np.max(np.abs(x)))
    if peak<=1/32768:return np.zeros(64,dtype='float32')
    x=x/peak;frame=400;hop=160
    if len(x)<frame:x=np.pad(x,(0,frame-len(x)))
    framed=np.lib.stride_tricks.sliding_window_view(x,frame)[::hop]
    energy=np.mean(framed*framed,axis=1);env=np.sqrt(np.maximum(energy,0));env/=max(float(env.max()),1e-12)
    positive=np.maximum(np.diff(env,prepend=env[0]),0)
    peaks,_=find_peaks(env,height=.15,prominence=.10,distance=8)
    duration=max(len(x)/rate,.01);gaps=np.diff(peaks)/100
    out=[len(peaks)/duration]
    out.extend(np.quantile(env,[.1,.25,.5,.75,.9,.99]))
    out.extend([np.mean(env>.1),np.mean(env>.3),np.mean(env>.6),np.mean(positive),np.std(positive),np.max(positive),
      np.mean(x*x),np.mean(x**4)/max(np.mean(x*x)**2,1e-12),np.mean(np.diff(np.signbit(x))),
      float(np.mean(gaps)) if len(gaps) else 0,float(np.std(gaps)) if len(gaps) else 0])
    out.extend([float(np.mean((gaps>=lo)&(gaps<hi))) if len(gaps) else 0 for lo,hi in ((0,.15),(.15,.3),(.3,.5),(.5,.8),(.8,1.2),(1.2,3.))])
    centered=env-env.mean();n=len(centered);size=1<<int((2*n-1).bit_length())
    spectrum=np.fft.rfft(centered,n=size);ac=np.fft.irfft(spectrum*np.conjugate(spectrum),n=size)[:n]
    ac/=max(float(ac[0]),1e-12)
    for lo,hi in ((.08,.15),(.15,.25),(.25,.4),(.4,.6),(.6,.9),(.9,1.2),(1.2,1.6),(1.6,2.)):
        a=int(lo*100);b=min(n,int(hi*100));out.append(float(np.max(ac[a:b])) if b>a else 0)
    window=np.hanning(frame);power=np.abs(np.fft.rfft(framed*window,axis=1))**2
    f=np.fft.rfftfreq(frame,1/rate);total=power.sum(axis=1)+1e-12
    for lo,hi in ((0,80),(80,160),(160,320),(320,640),(640,1280),(1280,2560),(2560,4000),(4000,8001)):
        frac=power[:,(f>=lo)&(f<hi)].sum(axis=1)/total
        out.extend([np.mean(frac),np.std(frac),np.quantile(frac,.9)])
    centroid=(power*f).sum(axis=1)/total/8000
    flat=np.exp(np.mean(np.log(np.maximum(power,1e-12)),axis=1))/(power.mean(axis=1)+1e-12)
    out.extend([np.mean(centroid),np.std(centroid),np.mean(flat),np.std(flat)])
    out.extend([float(np.quantile(positive,q)) for q in (.5,.9,.99)])
    normalized_power=power/total[:,None]
    out.append(float(np.mean(np.maximum(np.diff(normalized_power,axis=0),0))) if len(power)>1 else 0)
    if len(out)!=64:raise RuntimeError('Feature schema drift: '+str(len(out)))
    return np.asarray(out,dtype='float32')
