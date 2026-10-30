"""Guard a frozen head at PCM16 resolution without retraining or using test labels."""
from pathlib import Path
import hashlib,json,shutil
import numpy as np
import soundfile as sf
import tensorflow as tf
ROOT=Path(__file__).resolve().parents[2]
ORIGINAL=ROOT/'models/sound_yamnet_mlp_expanded_verified_sources'
OUT=ROOT/'models/sound_yamnet_mlp_guarded'

def raw_window(path):
    x,rate=sf.read(path,dtype='float32')
    if rate!=16000 or x.ndim!=1:raise ValueError('WAV contract')
    energies=[float(np.sum(x[i:i+1600].astype('float64')**2)) for i in range(0,len(x),1600)]
    center=int(np.argmax(energies))*1600+800
    start=max(0,min(center-32000,max(0,len(x)-64000)))
    x=x[start:start+64000];return np.pad(x,(0,64000-len(x)))

def main():
    OUT.mkdir(exist_ok=True)
    before=json.loads((ORIGINAL/'model-manifest.json').read_text())
    if hashlib.sha256((ORIGINAL/'knok_v2_calibrated.tflite').read_bytes()).hexdigest()!=before['model_sha256']:raise ValueError('Frozen source changed')
    class Guarded(tf.Module):
        def __init__(self):super().__init__();self.frozen=tf.saved_model.load(str(ORIGINAL/'pipeline_savedmodel'))
        @tf.function(input_signature=[tf.TensorSpec([64000],tf.float32,name='waveform')])
        def serve(self,waveform):
            centered=waveform-tf.reduce_mean(waveform)
            peak=tf.reduce_max(tf.abs(centered));rms=tf.sqrt(tf.reduce_mean(centered*centered))
            limited=(peak<=tf.constant(2/32768,tf.float32))|(rms<=tf.constant(1/32768,tf.float32))
            probabilities=self.frozen.signatures['serving_default'](waveform=waveform)['probabilities']
            return {'probabilities':tf.where(limited,tf.one_hot([4],6),probabilities)}
    module=Guarded();saved=OUT/'pipeline_savedmodel'
    tf.saved_model.save(module,str(saved),signatures={'serving_default':module.serve})
    convert=tf.lite.TFLiteConverter.from_saved_model(str(saved));convert.target_spec.supported_ops=[tf.lite.OpsSet.TFLITE_BUILTINS]
    blob=convert.convert();model=OUT/'knok_v2_calibrated.tflite';model.write_bytes(blob)
    new=tf.lite.Interpreter(model_content=blob,num_threads=4);old=tf.lite.Interpreter(model_path=str(ORIGINAL/'knok_v2_calibrated.tflite'),num_threads=4)
    for interpreter in [old,new]:interpreter.allocate_tensors()
    report=json.loads((ORIGINAL/'development_report.json').read_text());errors=[];levels=[]
    for row in report['physical_rows']:
        x=raw_window(row['path']);centered=x-x.mean();rms=float(np.sqrt(np.mean(centered**2))*32768);peak=float(np.max(abs(centered))*32768)
        levels.append({'event_id':row['event_id'],'rms_pcm16_lsb':rms,'peak_pcm16_lsb':peak})
        if rms<=1 or peak<=2:raise ValueError('Previously validated capture is quantization limited; cannot preserve metrics silently')
        results=[]
        for interpreter in [old,new]:
            interpreter.set_tensor(interpreter.get_input_details()[0]['index'],x);interpreter.invoke();results.append(interpreter.get_tensor(interpreter.get_output_details()[0]['index']))
        errors.append(float(np.max(abs(results[0]-results[1]))))
    if max(errors)>1e-4:raise ValueError('Normal captured-input probability parity changed')
    guard={'peak_pcm16_lsb':2,'rms_pcm16_lsb':1,'rule':'centered_peak<=2_LSB_OR_centered_RMS<=1_LSB','scope':'PCM16 quantization-resolution safeguard only; not an ambient sound classifier or calibrated physical-noise floor'}
    manifest=dict(before,model_version=before['model_version'].replace('-v4','-v5-signal-guard'),head_model_version=before['model_version'],model_sha256=hashlib.sha256(blob).hexdigest(),guard_source_model_sha256=before['model_sha256'],quantization_floor=guard,normal_input_guard_parity_max_error=max(errors))
    (OUT/'model-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
    shutil.copy2(ORIGINAL/'calibrated_head.joblib',OUT/'calibrated_head.joblib')
    shutil.copy2(ORIGINAL/'development_report.json',OUT/'development_report.json')
    verification={'head_weights_retrained':False,'source_splits_changed':False,'guard_chosen_from_test_outcomes':False,'guard_basis':guard,'normal_captured_inputs':len(errors),'max_probability_error':max(errors),'levels':levels,'source_model_sha256':before['model_sha256'],'guarded_model_sha256':manifest['model_sha256']}
    (OUT/'guard-verification.json').write_text(json.dumps(verification,ensure_ascii=False,indent=2))
    print(json.dumps({k:verification[k] for k in verification if k!='levels'},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
