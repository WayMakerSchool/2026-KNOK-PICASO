"""Read existing SNU data and fetch only official ESC-50 footsteps for local research."""
from pathlib import Path
import csv,hashlib,io,json,re,urllib.request
from concurrent.futures import ThreadPoolExecutor
import numpy as np,soundfile as sf
from scipy.signal import resample_poly
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'models/expanded_data';SNU=Path('/Users/yesung/Documents/Codex/Projects/KNOK_floor_classifier_20261015')
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def main():
 OUT.mkdir(exist_ok=True);commit=json.loads((OUT/'esc50-commit.json').read_text())['commit'];base=f'https://raw.githubusercontent.com/karoldvl/ESC-50/{commit}/'
 for name in ['meta/esc50.csv','LICENSE','README.md']:
  target=OUT/('esc50-'+Path(name).name)
  if not target.exists():target.write_bytes(urllib.request.urlopen(base+name,timeout=30).read())
 expected={r['wav_path']:r['sha256'] for r in csv.DictReader((SNU/'data/wav-sha256.csv').open())}
 mapping={'MB':'instant_impact','HD':'instant_impact','HH':'hammering','CD':'dragging_furniture','VC':'vacuum_cleaner'}
 rows=[]
 for r in csv.DictReader((SNU/'data/metadata.csv').open()):
  if r['type'] not in mapping:continue
  name=f"{int(r['track_id']):06d}";rel=f'data/wav/{name[:3]}/{name}.wav';path=SNU/rel;actual=sha(path)
  if expected.get(rel)!=actual:raise ValueError('SNU source hash mismatch: '+name)
  info=sf.info(path)
  if info.samplerate!=16000 or info.channels!=1:raise ValueError('SNU audio format')
  dist=int(re.fullmatch(r'[123]F(\d+)m',r['position']).group(1))
  rows.append(dict(parent_source_id='snu_'+name,label=mapping[r['type']],collection='public_snu',path=str(path),sha256=actual,split='reference_validation' if dist==12 else 'train',source_type=r['type'],position=r['position'],source_url='https://github.com/yodacatmeow/indoor-noise/tree/master/indoor-noise-set/SNU-B36-50E',provenance='Provided ZIP metadata, verified existing conversion hashes; no floor label used as a feature'))
 raw=OUT/'esc50_raw';raw.mkdir(exist_ok=True);wav=OUT/'esc50_16k';wav.mkdir(exist_ok=True)
 meta=list(csv.DictReader((OUT/'esc50-esc50.csv').open()))
 esc=[r for r in meta if r['category']=='footsteps']
 for category in ['pouring_water','toilet_flush','water_drops','rain','wind','coughing','laughing','keyboard_typing','breathing']:
  for folds,limit in [({'1','2','3'},8),({'4','5'},4)]:
   candidates=sorted([r for r in meta if r['category']==category and r['fold'] in folds],key=lambda r:r['filename']);seen=set();chosen=[]
   for r in candidates:
    if r['src_file'] in seen:continue
    seen.add(r['src_file']);chosen.append(r)
    if len(chosen)==limit:break
   esc+=chosen
 def one(r):
  source=raw/r['filename'];url=base+'audio/'+r['filename']
  if not source.exists():
   b=urllib.request.urlopen(url,timeout=45).read();tmp=source.with_suffix('.part');tmp.write_bytes(b);tmp.replace(source)
  x,rate=sf.read(source,dtype='float32',always_2d=True);x=x.mean(axis=1)
  if not np.isfinite(x).all():raise ValueError('Non-finite ESC audio')
  if rate!=16000:
   import math;g=math.gcd(rate,16000);x=resample_poly(x,16000//g,rate//g).astype('float32')
  path=wav/r['filename'];sf.write(path,x,16000,subtype='PCM_16')
  return dict(parent_source_id='esc50_'+r['src_file'],label='footstep' if r['category']=='footsteps' else 'normal_or_unknown',collection='public_esc50',path=str(path),sha256=sha(path),split='reference_validation' if r['fold'] in ['4','5'] else 'train',source_url=url,source_sha256=sha(source),source_fold=int(r['fold']),provenance='Official ESC-50 footsteps / non-target household sounds; original src_file groups retained; CC BY-NC research data')
 with ThreadPoolExecutor(max_workers=4) as pool:rows+=list(pool.map(one,esc))
 (OUT/'public_catalog.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
 from collections import Counter
 print('Expanded public rows',len(rows),Counter((r['collection'],r['split']) for r in rows),flush=True)
 print('ESC original sources',len({r['parent_source_id'] for r in rows if r['collection']=='public_esc50'}),flush=True)
if __name__=='__main__':main()
