"""Read stored WAVs only. No playback, capture, network, or Android access."""
from pathlib import Path
import os
from silent_runtime import mps_memory_environment
os.environ.update(OMP_NUM_THREADS='2', OPENBLAS_NUM_THREADS='1', VECLIB_MAXIMUM_THREADS='1',
                  HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', **mps_memory_environment())
import argparse
import hashlib
import json
import time
import wave
import numpy as np
from portable_head import predict_head, decide, LABELS
from silent_preprocess import prepare_view

P = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('wav', type=Path, nargs='+', help='Stored 16kHz mono PCM16 WAV')
    args = parser.parse_args()
    if len(args.wav) > 8:
        parser.error('At most eight stored files per bounded research run')
    manifest = json.loads((P / 'research-runtime.json').read_text())
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    for name, expected in manifest['code_sha256'].items():
        if sha(P / name) != expected:
            raise RuntimeError('Research code integrity check failed: ' + name)
    if sha(P / 'research-head.npz') != manifest['head_sha256']:
        raise RuntimeError('Research head integrity check failed')
    for name, item in manifest['encoder_source']['files'].items():
        if sha(P / 'ast-model' / name) != item['sha256']:
            raise RuntimeError('Local encoder integrity check failed: ' + name)
    parts = []
    for path in args.wav:
        with wave.open(str(path)) as audio:
            if (audio.getframerate(), audio.getnchannels(), audio.getsampwidth(), audio.getcomptype()) != (16000, 1, 2, 'NONE'):
                raise ValueError('16kHz mono PCM16 WAV required')
            samples = np.frombuffer(audio.readframes(audio.getnframes()), '<i2').astype('float32') / 32768
        part, start, floor = prepare_view(samples, 'activity4')
        parts.append((path, part, start, floor))
    device = 'not_run'
    if any(not floor for _, _, _, floor in parts):
        import torch
        from transformers import ASTFeatureExtractor, ASTForAudioClassification
        torch.set_num_threads(2)
        torch.set_num_interop_threads(1)
        device = 'mps' if torch.backends.mps.is_available() else 'cpu'
        extractor = ASTFeatureExtractor.from_pretrained(str(P / 'ast-model'), local_files_only=True)
        model = ASTForAudioClassification.from_pretrained(str(P / 'ast-model'), local_files_only=True,
            use_safetensors=True, attn_implementation='eager', dtype=torch.float32).eval().to(device)
        with np.load(P / 'research-head.npz', allow_pickle=False) as saved:
            head = {name: saved[name].copy() for name in saved.files}
    results = []
    for path, part, start, floor in parts:
        if floor:
            results.append({'file': path.name, 'audio_sha256': sha(path),
                'label': 'normal_or_unknown', 'top_label': None, 'model_score': None,
                'model_score_is_accuracy': False, 'probabilities': None,
                'reason': 'quantization_limited', 'window_start_sample': start,
                'quantization_limited': True, 'inference_ms': 0})
            continue
        began = time.monotonic()
        values = extractor(part, sampling_rate=16000, return_tensors='pt').input_values.to(device)
        with torch.inference_mode():
            embedding = model.audio_spectrogram_transformer(input_values=values, return_dict=True).pooler_output.cpu().numpy().astype('float32')
        probability = predict_head(head, embedding)
        index = int(decide(probability)[0])
        results.append({'file': path.name, 'audio_sha256': sha(path), 'label': LABELS[index],
            'top_label': LABELS[int(probability[0].argmax())], 'model_score': float(probability.max()),
            'model_score_is_accuracy': False, 'probabilities': probability[0].tolist(),
            'window_start_sample': start, 'quantization_limited': floor,
            'inference_ms': (time.monotonic() - began) * 1000})
    print(json.dumps({'model_id': manifest['model_id'], 'status': manifest['status'],
        'device': device, 'cpu_threads': 0 if device == 'not_run' else torch.get_num_threads(), 'threshold': .65,
        'results': results, 'new_accuracy_estimate': False}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
