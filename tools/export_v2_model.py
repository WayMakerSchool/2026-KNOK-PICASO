"""Export the existing frozen raw YAMNet pipeline without changing its weights."""
from pathlib import Path
import hashlib
import json
import tensorflow as tf

ROOT = Path(__file__).resolve().parents[2]
PREVIOUS = Path('/Users/yesung/Documents/Codex/Projects/KNOK_audio_retraining_20261022/artifacts')


def main():
    converter = tf.lite.TFLiteConverter.from_saved_model(str(PREVIOUS / 'audio_pipeline_savedmodel'))
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    data = converter.convert()
    out = ROOT / 'models' / 'knok_v2_yamnet.tflite'
    out.write_bytes(data)
    card = json.loads((PREVIOUS / 'model-card.json').read_text())
    manifest = dict(model_version=card['model_version'], model_sha256=hashlib.sha256(data).hexdigest(),
                    model_file=out.name, input_shape=[64000], labels=card['labels'],
                    threshold=card['threshold'], sample_rate=16000,
                    crop='maximum_100ms_rms_centered_4seconds_clamped',
                    normalization='inside_model_subtract_dc_and_peak_0.8',
                    training_counts=card['counts'], independent_accuracy_verified=False,
                    conversion='TFLITE_BUILTINS_only')
    (ROOT / 'models' / 'model-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    print(json.dumps({'bytes':len(data),'sha256':manifest['model_sha256']},indent=2))


if __name__ == '__main__':
    main()
