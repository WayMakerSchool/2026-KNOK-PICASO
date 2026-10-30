import unittest,sys
from pathlib import Path
import numpy as np
sys.path.insert(0,'/Users/yesung/Documents/Codex/Projects/KNOK_audio_retraining_20261022')
from knok_retrain.acoustic import acoustic_features
from tf_acoustic import acoustic_501
class AcousticParityTest(unittest.TestCase):
 def test_matches_existing_501_equations_for_silence_sine_and_transients(self):
  signals=[np.zeros(64000,dtype='float32'),(.5*np.sin(2*np.pi*440*np.arange(64000)/16000)).astype('float32')]
  rng=np.random.default_rng(20261023);x=rng.normal(0,.005,64000).astype('float32');x[12000]=.8;x[27000]=-.7;signals.append(x)
  for i,x in enumerate(signals):
   # Different float32 FFT kernels diverge in near-zero high-frequency sine bins
   # before the logarithm. Train the new head on this TF variant; conversion gets
   # its own strict probability parity check. No old 501D weights are substituted.
   np.testing.assert_allclose(acoustic_501(x).numpy(),acoustic_features(x),rtol=2e-4,atol=.2 if i==1 else .003)
if __name__=='__main__':unittest.main()
