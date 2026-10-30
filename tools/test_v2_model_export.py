"""Real raw-waveform model parity, including silence; not an accuracy evaluation."""
from pathlib import Path
import unittest
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / 'models' / 'knok_v2_yamnet.tflite'
REFERENCE = Path('/Users/yesung/Documents/Codex/Projects/KNOK_audio_retraining_20261022/artifacts/audio_pipeline_savedmodel')


class ModelExportTest(unittest.TestCase):
    def test_raw_model_matches_reference_and_rejects_silence(self):
        self.assertTrue(MODEL.is_file(), 'V2 raw-waveform model has not been exported')
        import tensorflow as tf
        lite = tf.lite.Interpreter(model_path=str(MODEL), num_threads=2)
        lite.allocate_tensors()
        inp = lite.get_input_details()[0]
        out = lite.get_output_details()[0]
        self.assertEqual(inp['shape'].tolist(), [64000])
        self.assertEqual(out['shape'].tolist(), [1, 6])
        reference = tf.saved_model.load(str(REFERENCE)).signatures['serving_default']
        t = np.arange(64000, dtype=np.float32) / 16000
        noise = np.random.default_rng(7).normal(0, .03, 64000).astype('float32')
        waves = [np.zeros(64000, np.float32), (.2*np.sin(2*np.pi*180*t)).astype('float32'), noise]
        for wave in waves:
            lite.set_tensor(inp['index'], wave)
            lite.invoke()
            prediction = lite.get_tensor(out['index'])
            expected = reference(waveform=tf.constant(wave))['probabilities'].numpy()
            self.assertTrue(np.isfinite(prediction).all())
            self.assertLess(float(np.max(np.abs(prediction-expected))), 1e-4)
            self.assertAlmostEqual(float(prediction.sum()), 1.0, places=5)
        lite.set_tensor(inp['index'], waves[0]); lite.invoke()
        self.assertEqual(int(lite.get_tensor(out['index'])[0].argmax()), 4)


if __name__ == '__main__':
    unittest.main()
