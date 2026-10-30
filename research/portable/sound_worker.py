"""JSON-lines worker for the preserved desk-repair sound model."""
import os
os.environ.update(OPENBLAS_NUM_THREADS="1", OMP_NUM_THREADS="2", VECLIB_MAXIMUM_THREADS="1")
import hashlib
import json
from pathlib import Path
import sys
import time
import wave

HERE = Path(__file__).resolve().parent
RESEARCH = HERE.parent
SOUND = RESEARCH / "sound"
BASE = SOUND / "base_runtime"
MODEL = SOUND / "models/sound.pt"
MODEL_HASH = "824bde5efbb5df86be7cc1078debc32278439383a38588a2f079a0b1fbbfe764"
VERSION = "knok-sound-desk-repair-20261028"
sys.path[:0] = [str(RESEARCH / "service"), str(SOUND), str(SOUND / "round14"), str(SOUND / "round21")]


def main():
    import numpy as np
    import semantic_head
    from ast_worker import load_runtime, _window_starts
    from experiment_core import temporal_features
    from temporal_head import load, predict

    if hashlib.sha256(MODEL.read_bytes()).hexdigest() != MODEL_HASH:
        raise RuntimeError("Sound classifier checksum mismatch")
    semantic_head.MODEL = BASE / "ast-model"
    state = load_runtime(BASE)
    np, torch, extractor, model, _, labels, _, _, prepare_view, device, _ = state
    head = load(MODEL, device)
    print(json.dumps({"status": "ready", "model_version": VERSION}), flush=True)
    for line in sys.stdin:
        try:
            req = json.loads(line)
            started = time.monotonic()
            with wave.open(req["wav_path"], "rb") as wav:
                if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth(), wav.getcomptype()) != (16000, 1, 2, "NONE"):
                    raise ValueError("Expected 16kHz mono PCM16 WAV")
                audio = np.frombuffer(wav.readframes(wav.getnframes()), "<i2").astype("float32") / 32768
            if not len(audio):
                raise ValueError("Empty WAV")
            starts = _window_starts(len(audio))
            vectors = []
            for start in starts:
                window, _, low = prepare_view(audio[start:start + 64000], "activity4")
                if low:
                    continue
                values = extractor(window, sampling_rate=16000, return_tensors="pt").input_values.to(device)
                with torch.inference_mode():
                    vectors.append(model.audio_spectrogram_transformer(input_values=values, return_dict=True)
                                   .pooler_output[0].cpu().numpy().astype("float32"))
            if vectors:
                features = np.stack(vectors)[None, :, :]
                q = predict(head, features, np.ones(features.shape[:2], dtype=bool),
                            temporal_features(audio)[None, :], device)[0][0]
                score = float(q.max())
                label = labels[int(q.argmax())] if score >= .65 else "normal_or_unknown"
                quality = "normal"
            else:
                q = np.array([0., 0., 0., 0., 1., 0.])
                score, label, quality = 0., "normal_or_unknown", "low_signal"
            result = {"label": label, "top_label": labels[int(q.argmax())], "score": score,
                      "probabilities": {name: float(q[i]) for i, name in enumerate(labels)},
                      "signal_quality": quality, "model_version": VERSION,
                      "window_count": len(starts), "window_starts": starts,
                      "window_policy": "4s-full-record-attention-semantic-prior-stride2s-desk-repair",
                      "inference_ms": round((time.monotonic() - started) * 1000), "device": device,
                      "cpu_threads": torch.get_num_threads()}
            print(json.dumps(result, allow_nan=False), flush=True)
        except Exception as exc:
            print(json.dumps({"error": f"{type(exc).__name__}: {exc}"}), flush=True)


if __name__ == "__main__":
    main()
