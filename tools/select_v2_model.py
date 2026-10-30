"""Freeze the source-grouped development choice; preserve the initial candidate."""
from pathlib import Path
import hashlib, json, shutil
import numpy as np
import soundfile as sf
import tensorflow as tf

ROOT=Path(__file__).resolve().parents[2]
chosen=ROOT/'models/sound_yamnet_mlp_guarded'
if not chosen.exists():chosen=ROOT/'models/sound_yamnet_mlp_expanded_verified_sources'
assets=ROOT/'source/app/src/main/assets'
test_assets=ROOT/'source/app/src/androidTest/assets'
initial=ROOT/'models/initial_candidate';initial.mkdir(exist_ok=True)
for name in ['knok_v2_yamnet.tflite','knok_v2_model_manifest.json']:
    if not (initial/name).exists():shutil.copy2(assets/name,initial/name)
if not (initial/'physical_probe_expected.json').exists():shutil.copy2(test_assets/'physical_probe_expected.json',initial/'physical_probe_expected.json')
manifest=json.loads((chosen/'model-manifest.json').read_text())
model=chosen/'knok_v2_calibrated.tflite'
if hashlib.sha256(model.read_bytes()).hexdigest()!=manifest['model_sha256']:raise ValueError('Selected model hash mismatch')
x,sr=sf.read(test_assets/'physical_probe.wav',dtype='float32')
if sr!=16000 or x.ndim!=1:raise ValueError('Bad parity WAV')
bins=[float(np.sum(x[i:i+1600].astype('float64')**2)) for i in range(0,len(x),1600)]
start=max(0,min(int(np.argmax(bins))*1600+800-32000,max(0,len(x)-64000)))
window=np.pad(x[start:start+64000],(0,max(0,64000-len(x[start:start+64000]))))
lite=tf.lite.Interpreter(model_path=str(model),num_threads=4);lite.allocate_tensors()
if lite.get_input_details()[0]['shape'].tolist()!=[64000]:raise ValueError('Bad raw model contract')
lite.set_tensor(lite.get_input_details()[0]['index'],window);lite.invoke();prob=lite.get_tensor(lite.get_output_details()[0]['index'])[0]
expected={'probabilities':dict(zip(manifest['labels'],map(float,prob))),'window_start_sample':start,'model_sha256':manifest['model_sha256']}
shutil.copy2(model,assets/'knok_v2_yamnet.tflite')
shutil.copy2(chosen/'model-manifest.json',assets/'knok_v2_model_manifest.json')
(test_assets/'physical_probe_expected.json').write_text(json.dumps(expected,indent=2))
selection={'selected_directory':str(chosen),'model_sha256':manifest['model_sha256'],'model_version':manifest['model_version'],'basis':'Highest source-grouped physical replay development score among numerically verified exports; fixed rejection threshold .65','grouped_development':manifest['physical_source_grouped_development'],'initial_candidate_same_captures':manifest['original_candidate_development'],'target_accuracy_met':False,'independent_accuracy_verified':False,'new_room_field_test_required':True}
(ROOT/'models/selection.json').write_text(json.dumps(selection,ensure_ascii=False,indent=2))
print(json.dumps(expected,indent=2));print('Selected prototype; grouped accuracy',manifest['physical_source_grouped_development']['accuracy'])
