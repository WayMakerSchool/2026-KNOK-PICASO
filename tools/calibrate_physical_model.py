"""Fit a reversible V2 microphone-domain head; grouped replay development, not independent accuracy.

All augmented descendants and microphone replays of an original are excluded
from a validation fold together. Speaker replays share a room/session and
cannot establish deployment accuracy, even when source grouping is enforced.
"""
from pathlib import Path
import argparse,collections,hashlib,json,sys,time
import numpy as np
import soundfile as sf

ROOT=Path(__file__).resolve().parents[2]
LABELS=['dragging_furniture','footstep','hammering','instant_impact','normal_or_unknown','vacuum_cleaner']

def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def decide(p):
 p=np.asarray(p);ix=np.argmax(p,axis=1);return np.array([LABELS[i] if p[j,i]>=.65 else 'normal_or_unknown' for j,i in enumerate(ix)])
def metrics(truth,p):
 from sklearn.metrics import accuracy_score,f1_score,confusion_matrix
 predicted=decide(p)
 return dict(accuracy=float(accuracy_score(truth,predicted)),macro_f1=float(f1_score(truth,predicted,labels=LABELS,average='macro',zero_division=0)),count=len(truth),confusion_matrix=confusion_matrix(truth,predicted,labels=LABELS).tolist())
def source_weights(rows,mass):
 parents=collections.Counter((r['label'],r['collection'],r['parent_source_id']) for r in rows)
 domains={l:{d for lab,d,_ in parents if lab==l} for l in LABELS}
 source_count=collections.Counter((l,d) for l,d,_ in parents)
 return np.array([mass/(6*len(domains[r['label']])*source_count[(r['label'],r['collection'])]*parents[(r['label'],r['collection'],r['parent_source_id'])]) for r in rows])
def fit(X,rows,mass,held=(),estimator="logistic"):
 from sklearn.preprocessing import StandardScaler
 from sklearn.linear_model import LogisticRegression
 if set(r['parent_source_id'] for r in rows)&set(held):raise ValueError('Source leakage')
 w=source_weights(rows,mass)
 if estimator=='mlp':
  import tensorflow as tf
  tf.keras.backend.clear_session();tf.keras.utils.set_random_seed(20261023)
  sc=StandardScaler().fit(X,sample_weight=w);xt=sc.transform(X).astype('float32');y=np.array([LABELS.index(r['label']) for r in rows],dtype='int32')
  net=tf.keras.Sequential([tf.keras.layers.Input(shape=(X.shape[1],)),tf.keras.layers.Dense(128,activation='relu',kernel_regularizer=tf.keras.regularizers.l2(1e-4)),tf.keras.layers.Dropout(.2),tf.keras.layers.Dense(6,activation='softmax')])
  net.compile(optimizer=tf.keras.optimizers.Adam(learning_rate=.001),loss='sparse_categorical_crossentropy',jit_compile=False)
  options=tf.data.Options();options.threading.private_threadpool_size=2;options.experimental_deterministic=True
  ds=tf.data.Dataset.from_tensor_slices((xt,y,(w*len(w)/w.sum()).astype('float32'))).shuffle(len(y),seed=20261023).batch(128).with_options(options)
  net.fit(ds,epochs=40,verbose=0)
  return {'scaler':sc,'classifier':None,'keras_weights':net.get_weights(),'estimator':'mlp','method':'yamnet','labels':LABELS,'input_dimension':X.shape[1]}
 if estimator=='trees':
  from sklearn.ensemble import ExtraTreesClassifier
  sc=None;clf=ExtraTreesClassifier(n_estimators=160,max_depth=18,min_samples_leaf=2,max_features='sqrt',random_state=20261023,n_jobs=4).fit(np.asarray(X,dtype='float32'),[r['label'] for r in rows],sample_weight=w)
 else:
  sc=StandardScaler().fit(X,sample_weight=w)
  clf=LogisticRegression(C=.1,max_iter=2000,random_state=20261023,solver='lbfgs').fit(sc.transform(X),[r['label'] for r in rows],sample_weight=w)
  if max(clf.n_iter_)>=2000:raise RuntimeError('Head did not converge')
 return {'scaler':sc,'classifier':clf,'estimator':estimator,'method':'yamnet','labels':LABELS,'input_dimension':X.shape[1]}

def predict(h,X):
 if h.get('estimator')=='mlp':
  w1,b1,w2,b2=h['keras_weights'];z=np.maximum(0,h['scaler'].transform(X)@w1+b1)@w2+b2;z=z-z.max(axis=1,keepdims=True);p=np.exp(z);return p/p.sum(axis=1,keepdims=True)
 p=h['classifier'].predict_proba(h['scaler'].transform(X) if h['scaler'] is not None else X);return p[:,[list(h['classifier'].classes_).index(l) for l in LABELS]]

def main():
 p=argparse.ArgumentParser();p.add_argument('--training-root',type=Path,required=True);p.add_argument('--wait-for-replay',action='store_true');p.add_argument('--method',choices=['yamnet','short','multiscale','acoustic','combined'],default='yamnet');p.add_argument('--head',choices=['logistic','trees','mlp'],default='logistic');p.add_argument('--expanded-public',action='store_true');p.add_argument('--omit-legacy',action='store_true');a=p.parse_args()
 if a.wait_for_replay:
  deadline=time.monotonic()+900
  while True:
   file=ROOT/'evidence/r3-controlled-replay/trials.jsonl';trials=[json.loads(x) for x in file.read_text().splitlines()] if file.exists() else []
   if any(not t['capture_succeeded'] for t in trials):raise RuntimeError('Physical reliability run failed; stop calibration')
   if len(trials)>=18:break
   if time.monotonic()>deadline:raise TimeoutError('Physical replay run incomplete')
   time.sleep(2)
 old=a.training_root.resolve();sys.path.insert(0,str(old))
 from knok_retrain.train import Extractor
 from knok_retrain.augmentation import model_audio
 from knok_retrain.export import _parameters
 import tensorflow as tf,joblib
 outname=('sound_'+a.method+'_'+a.head+'_expanded'+('_verified_sources' if a.omit_legacy else '')) if a.expanded_public else ('microphone_calibration' if a.method=='yamnet' else 'microphone_'+a.method)
 out=ROOT/'models'/outname;out.mkdir(parents=True,exist_ok=True)
 config=json.loads((old/'configs/retrain_v1.json').read_text());extractor=Extractor(out,config)
 catalog=json.loads((old/'data/catalog.json').read_text());legacy=json.loads((old/'data/legacy_catalog.json').read_text())
 bypath={str(Path(r['path']).resolve()):r for r in catalog+legacy}
 physical_snapshot=[(meta,json.loads(meta.read_text())) for meta in sorted((ROOT/'evidence/physical-captures').glob('*.json'))]
 (out/'physical_input_snapshot.json').write_text(json.dumps([dict(meta=str(f),data=d) for f,d in physical_snapshot],ensure_ascii=False,indent=2))
 public=json.loads((ROOT/'models/expanded_data/public_catalog.json').read_text()) if a.expanded_public else []
 train_public=[r for r in public if r['split']=='train'];ref_public=[r for r in public if r['split']=='reference_validation']
 (out/'public_input_snapshot.json').write_text(json.dumps(public,ensure_ascii=False,indent=2))
 if {r['parent_source_id'] for r in train_public}&{r['parent_source_id'] for r in ref_public}:raise ValueError('Public source split leak')
 rows=[];X=[]
 def yamnet_features(path,actual):
  key=hashlib.sha256((actual+extractor.fingerprint).encode()).hexdigest()
  for folder in [old/'cache/features',ROOT/'models/sound_yamnet_trees_expanded/cache/features',ROOT/'models/microphone_calibration/cache/features',extractor.cache]:
   cached=folder/(key+'.npz')
   if cached.exists():
    with np.load(cached) as z:
     v=z['yamnet'].copy()
    if v.shape==(2048,) and np.isfinite(v).all():return v
  return extractor.extract(path,actual)[1]
 if a.method in ['acoustic','combined']:
  from tf_acoustic import acoustic_501
  tf_acoustic=tf.function(acoustic_501,input_signature=[tf.TensorSpec([64000],tf.float32)])
  acoustic_fp=digest(Path(__file__).with_name('tf_acoustic.py'))
  acoustic_cache=out/'cache/acoustic_tf';acoustic_cache.mkdir(parents=True,exist_ok=True)
 short_cache=out/'cache/short_yamnet';short_cache.mkdir(parents=True,exist_ok=True)
 def short_features(path,actual):
  key=hashlib.sha256((actual+extractor.fingerprint+'energy_center_1sec_dc_peak_v1').encode()).hexdigest();cached=short_cache/(key+'.npy')
  if cached.exists():return np.load(cached)
  x,rate=sf.read(path,dtype='float32')
  if rate!=16000:raise ValueError('Short input rate')
  x,_=model_audio(x);center=int(np.argmax(np.sum(x.reshape(40,1600)**2,axis=1)))*1600+800;start=max(0,min(center-8000,48000));x=x[start:start+16000];x=x-x.mean();x=x*.8/max(float(np.max(abs(x))),1e-6)
  if extractor.yamnet is None:extractor.yamnet=tf.saved_model.load(config['paths']['yamnet'])
  _,emb,_=extractor.yamnet(x);v=np.r_[emb.numpy().mean(axis=0),emb.numpy().std(axis=0)].astype('float32');np.save(cached,v);return v
 def features(path,expected=None):
  actual=digest(path)
  if expected and actual!=expected:raise ValueError('Feature source hash mismatch')
  if a.method=='yamnet':return yamnet_features(path,actual)
  if a.method=='short':return short_features(path,actual)
  if a.method=='multiscale':return np.r_[yamnet_features(path,actual),short_features(path,actual)].astype('float32')
  key=hashlib.sha256((actual+acoustic_fp+extractor.fingerprint).encode()).hexdigest();cached=acoustic_cache/(key+'.npy')
  if cached.exists():ac=np.load(cached)
  else:
   x,rate=sf.read(path,dtype='float32')
   if rate!=16000:raise ValueError('Acoustic input sample rate')
   x,_=model_audio(x);ac=tf_acoustic(x).numpy();np.save(cached,ac)
  if a.method=='acoustic':return ac
  return np.r_[ac,yamnet_features(path,actual)].astype('float32')
 augmented=json.loads((old/'datasets/final_training_manifest.json').read_text())
 for r in augmented:
  rows.append(dict(parent_source_id=r['parent_source_id'],label=r['label'],collection='user',path=r['output_path'],sha256=r['sha256']))
  X.append(features(r['output_path'],r['sha256']))
 for r in legacy:
  if r['original_split']!='train' or a.omit_legacy:continue
  rows.append(dict(parent_source_id=r['source_id'],label=r['label'],collection='legacy',path=r['path'],sha256=r['source_sha256']))
  X.append(features(r['path'],r['source_sha256']))
 for r in train_public:
  rows.append(r);X.append(features(r['path'],r['sha256']))
 print('Existing and public training rows',len(rows),flush=True)
 physical=[];PX=[]
 for meta,r in physical_snapshot:
  state=r.get('host_audio_before',{})
  if not r.get('ground_truth') or state.get('output_muted',True) or state.get('output_volume',0)==0:continue
  if r.get('host_audio_after',{}).get('output_muted',True):continue
  parent=bypath[str(Path(r['stimulus']).resolve())]
  if parent['label']!=r['ground_truth']:raise ValueError('User/legacy truth conflicts')
  path=meta.with_suffix('.wav');item=dict(parent_source_id=parent['source_id'],label=r['ground_truth'],collection='physical_replay',path=str(path),sha256=digest(path),event_id=r['event']['id'],session='same_room_20261023')
  physical.append(item);PX.append(features(path,item['sha256']))
 if set(r['label'] for r in physical)!=set(LABELS):raise ValueError('All six physical categories required')
 # Modest timing/echo/noise augmentation of each microphone capture; ancestry retained.
 derived=[];DX=[];dpath=out/'physical_augmentation';dpath.mkdir(exist_ok=True)
 for index,r in enumerate(physical):
  x,rate=sf.read(r['path'],dtype='float32');assert rate==16000;x,_=model_audio(x)
  for k in range(5):
   rng=np.random.default_rng(20261023+index*100+k);shift=int(rng.integers(-1600,1601));y=np.zeros_like(x)
   if shift>=0:y[shift:]=x[:len(x)-shift]
   else:y[:shift]=x[-shift:]
   delay=int(rng.integers(400,1200));y[delay:]+=float(rng.uniform(0,.08))*y[:-delay].copy()
   y+=rng.normal(0,max(float(np.sqrt(np.mean(y*y))),1e-6)*.02,len(y)).astype('float32');y=np.clip(y,-1,1)
   path=dpath/f'{r["event_id"]}-{k}.wav';sf.write(path,y,16000,subtype='PCM_16');item=dict(r,path=str(path),sha256=digest(path),augmentation_seed=20261023+index*100+k)
   derived.append(item);DX.append(features(path,item['sha256']))
 print('Physical sources/captures/augmented',len({r['parent_source_id'] for r in physical}),len(physical),len(derived),flush=True)
 all_rows=rows+physical+derived;all_X=np.asarray(X+PX+DX,dtype='float32');PX=np.asarray(PX,dtype='float32')
 mass=len(catalog)+(0 if a.omit_legacy else sum(r['original_split']=='train' for r in legacy))+len({r['parent_source_id'] for r in physical})+len({r['parent_source_id'] for r in train_public})
 groups={}
 for l in LABELS:
  ids=sorted({r['parent_source_id'] for r in physical if r['label']==l})
  if len(ids)<2:raise ValueError('At least two original sources per physical label required')
  for i,g in enumerate(ids):groups[g]=i%2
 oof=np.zeros((len(physical),6),dtype='float32');folds=[]
 for fold in [0,1]:
  held={g for g,f in groups.items() if f==fold};train=[i for i,r in enumerate(all_rows) if r['parent_source_id'] not in held];val=[i for i,r in enumerate(physical) if r['parent_source_id'] in held]
  h=fit(all_X[train],[all_rows[i] for i in train],mass,held,estimator=a.head);oof[val]=predict(h,PX[val]);folds.append({'fold':fold,'held_source_ids':sorted(held),'training_count':len(train),'physical_validation_count':len(val)})
  print('Grouped fold',fold,metrics([physical[i]['label'] for i in val],oof[val]),flush=True)
 h=fit(all_X,all_rows,mass,estimator=a.head);h.update(model_version='knok-sound-development-20261023-'+a.method+'-'+a.head+('-expanded' if a.expanded_public else '')+'-v5-signal-guard',method=a.method,fit_source_ids=sorted({r['parent_source_id'] for r in all_rows}))
 joblib.dump(h,out/'calibrated_head.joblib')
 # Frozen old candidate versus grouped new development predictions; never resubstitution accuracy.
 previous=joblib.load(old/'artifacts/selected_final_head.joblib');old_probs=predict(previous,np.stack([yamnet_features(r['path'],r['sha256']) for r in physical]))
 physical_ids={r['parent_source_id'] for r in physical}
 validation=[r for r in legacy if r['original_split']=='validation' and r['source_id'] not in physical_ids];VX=np.stack([features(r['path'],r['source_sha256']) for r in validation]);new_val=predict(h,VX);old_val=predict(previous,np.stack([yamnet_features(r['path'],r['source_sha256']) for r in validation]))
 report={'scope':'same_room_speaker_replay_source_grouped_development_not_independent','independent_accuracy_verified':False,'labels':LABELS,'fixed_threshold':.65,'method':a.method,'estimator':a.head,'expanded_public':a.expanded_public,'folds':folds,'physical_capture_count':len(physical),'physical_source_count':len(groups),'augmented_user_rows':len(augmented),'augmented_physical_rows':len(derived),'reference_weight_mass':mass,'old_candidate_physical':metrics([r['label'] for r in physical],old_probs),'calibrated_grouped_physical':metrics([r['label'] for r in physical],oof),'old_legacy_validation_reference':metrics([r['label'] for r in validation],old_val),'calibrated_legacy_validation_reference':metrics([r['label'] for r in validation],new_val),'physical_rows':physical,'grouped_probabilities':oof.tolist(),'original_candidate_probabilities':old_probs.tolist(),'training_sources':h['fit_source_ids'],'limitations':['Existing source audio replayed on one speaker in one room; not independent real noise','All descendants of a validation original are excluded from fold fitting','Do not claim 85 percent deployment accuracy from these metrics','Physical digital sensor counts are metadata, not model inputs']}
 if ref_public:
  RX=np.stack([features(r['path'],r['sha256']) for r in ref_public]);report['public_reference']=metrics([r['label'] for r in ref_public],predict(h,RX));report['public_reference_scope']='SNU held 12m positions plus ESC official fold5; no six-class independent KNOK field test'
 report['public_training_rows']=len(train_public);report['public_reference_rows']=len(ref_public)
 report['fit_physical_diagnostic_not_validation']=metrics([r['label'] for r in physical],predict(h,PX))
 (out/'development_report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
 if a.head=='logistic':mean,scale,coef,intercept=_parameters(h)
 elif a.head=='trees':
  from tf_tree_head import TreeTensorHead
  tree_head=TreeTensorHead(h['classifier'],LABELS)
 else:
  mean=h['scaler'].mean_.astype('float32');scale=h['scaler'].scale_.astype('float32');w1,b1,w2,b2=h['keras_weights']
 class AudioPipeline(tf.Module):
  def __init__(self):
   super().__init__();self.backbone=tf.saved_model.load(config['paths']['yamnet'])
   if a.head=='trees':self.tree_head=tree_head
  @tf.function(input_signature=[tf.TensorSpec([64000],tf.float32,name='waveform')])
  def serve(self,waveform):
   centered=waveform-tf.reduce_mean(waveform);peak=tf.reduce_max(tf.abs(centered));wave=centered*.8/tf.maximum(peak,1e-6)
   if a.method=='acoustic':features=acoustic_501(wave)
   else:
    if a.method!='short':
     _,emb,_=self.backbone(wave);features=tf.concat([tf.reduce_mean(emb,axis=0),tf.math.reduce_std(emb,axis=0)],axis=0)
    if a.method in ['short','multiscale']:
     energy=tf.reduce_sum(tf.reshape(wave,[40,1600])**2,axis=1);center=tf.argmax(energy,output_type=tf.int32)*1600+800;start=tf.clip_by_value(center-8000,0,48000)
     short=tf.slice(wave,[start],[16000]);short=short-tf.reduce_mean(short);short=short*.8/tf.maximum(tf.reduce_max(tf.abs(short)),1e-6)
     _,semb,_=self.backbone(short);sv=tf.concat([tf.reduce_mean(semb,axis=0),tf.math.reduce_std(semb,axis=0)],axis=0)
     features=sv if a.method=='short' else tf.concat([features,sv],axis=0)
    if a.method=='combined':features=tf.concat([acoustic_501(wave),features],axis=0)
   z=features[None,:]
   if a.head=='trees':prob=self.tree_head(z)
   elif a.head=='mlp':prob=tf.nn.softmax(tf.linalg.matmul(tf.nn.relu(tf.linalg.matmul((z-mean)/scale,w1)+b1),w2)+b2)
   else:prob=tf.nn.softmax(tf.linalg.matmul((z-mean)/scale,coef)+intercept)
   # PCM16 resolution, not a learned ambient threshold or legal sound level.
   unresolved=(peak<=2/32768)|(tf.sqrt(tf.reduce_mean(centered*centered))<=1/32768)
   return {'probabilities':tf.where(unresolved,tf.one_hot([4],6),prob)}
 pipeline=AudioPipeline();saved=out/'pipeline_savedmodel';tf.saved_model.save(pipeline,str(saved),signatures={'serving_default':pipeline.serve})
 converter=tf.lite.TFLiteConverter.from_saved_model(str(saved));converter.target_spec.supported_ops=[tf.lite.OpsSet.TFLITE_BUILTINS];data=converter.convert();model=out/'knok_v2_calibrated.tflite';model.write_bytes(data)
 lite=tf.lite.Interpreter(model_content=data,num_threads=4);lite.allocate_tensors();errors=[]
 final_probs=predict(h,PX)
 for i,r in enumerate(physical):
  x,_=sf.read(r['path'],dtype='float32');x,window=model_audio(x);lite.set_tensor(lite.get_input_details()[0]['index'],x);lite.invoke();pred=lite.get_tensor(lite.get_output_details()[0]['index'])[0];errors.append(float(np.max(abs(pred-final_probs[i]))))
 if max(errors)>1e-4:raise ValueError('Export parity failed')
 manifest=json.loads((ROOT/'models/model-manifest.json').read_text());manifest.update(model_version=h['model_version'],model_sha256=hashlib.sha256(data).hexdigest(),model_file=model.name,independent_accuracy_verified=False,feature_method=a.method,head_estimator=a.head,public_training_rows=len(train_public),public_reference_rows=len(ref_public),training_counts={'augmented_user':len(augmented),'legacy_training':0 if a.omit_legacy else sum(r['original_split']=='train' for r in legacy),'public_train':len(train_public),'physical_original':len(physical),'physical_augmented':len(derived),'unique_parent_sources':len(h['fit_source_ids'])},acoustic_equations='tf_float32_501_v1' if a.method!='yamnet' else None,physical_source_grouped_development=report['calibrated_grouped_physical'],original_candidate_development=report['old_candidate_physical'],physical_export_parity_max_error=max(errors))
 manifest['quantization_floor']={'peak_pcm16_lsb':2,'rms_pcm16_lsb':1,'scope':'PCM16 resolution guard only; not calibrated ambient noise gating'}
 (out/'model-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
 print(json.dumps({k:report[k] for k in ['old_candidate_physical','calibrated_grouped_physical','old_legacy_validation_reference','calibrated_legacy_validation_reference']},indent=2),flush=True)
 print('Exported',len(data),manifest['model_sha256'],'parity',max(errors),flush=True)

if __name__=='__main__':main()
