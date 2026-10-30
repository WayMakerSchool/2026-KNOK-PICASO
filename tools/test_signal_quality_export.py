from pathlib import Path
import os
import numpy as np
import tensorflow as tf

ROOT=Path(__file__).resolve().parents[2]
MODEL=Path(os.environ.get('KNOK_GUARD_TEST_MODEL',ROOT/'models/sound_yamnet_mlp_guarded/knok_v2_calibrated.tflite'))

def test_export_rejects_quantization_level_inputs_before_gain():
    lite=tf.lite.Interpreter(model_path=str(MODEL),num_threads=2);lite.allocate_tensors()
    impulse=np.zeros(64000,dtype='float32');impulse[100]=1/32768
    alternating=np.tile(np.array([1,-1],dtype='float32')/32768,32000)
    for x in [impulse,alternating,np.zeros(64000,dtype='float32')]:
        lite.set_tensor(lite.get_input_details()[0]['index'],x);lite.invoke()
        probabilities=lite.get_tensor(lite.get_output_details()[0]['index'])[0]
        assert int(np.argmax(probabilities))==4
        assert probabilities[4]>.99999
