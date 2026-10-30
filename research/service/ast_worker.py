"""AST process running in the preserved research runtime, one JSON request per line."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import sys
import time
import wave


def load_runtime(model_root: Path):
    model_root = model_root.resolve()
    sys.path.insert(0, str(model_root))
    from silent_runtime import mps_memory_environment
    os.environ.update(
        OMP_NUM_THREADS="2",
        OPENBLAS_NUM_THREADS="1",
        VECLIB_MAXIMUM_THREADS="1",
        HF_HUB_OFFLINE="1",
        TRANSFORMERS_OFFLINE="1",
        **mps_memory_environment(),
    )
    import numpy as np
    import torch
    from transformers import ASTFeatureExtractor, ASTForAudioClassification
    from portable_head import LABELS, decide, predict_head
    from silent_preprocess import prepare_view

    manifest = json.loads((model_root / "research-runtime.json").read_text())

    def sha(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    for name, expected in manifest["code_sha256"].items():
        if sha(model_root / name) != expected:
            raise RuntimeError("AST research runtime code hash does not match its manifest")
    if sha(model_root / "research-head.npz") != manifest["head_sha256"]:
        raise RuntimeError("AST research head hash does not match its manifest")
    for name, entry in manifest["encoder_source"]["files"].items():
        if sha(model_root / "ast-model" / name) != entry["sha256"]:
            raise RuntimeError("AST encoder file hash does not match its manifest")

    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    extractor = ASTFeatureExtractor.from_pretrained(
        str(model_root / "ast-model"), local_files_only=True
    )
    model = ASTForAudioClassification.from_pretrained(
        str(model_root / "ast-model"),
        local_files_only=True,
        use_safetensors=True,
        attn_implementation="eager",
        dtype=torch.float32,
    ).eval().to(device)
    with np.load(model_root / "research-head.npz", allow_pickle=False) as saved:
        head = {name: saved[name].copy() for name in saved.files}
    return np, torch, extractor, model, head, LABELS, decide, predict_head, prepare_view, device, manifest


def _read_samples(path: Path, np):
    with wave.open(str(path), "rb") as audio:
        if (audio.getframerate(), audio.getnchannels(), audio.getsampwidth(), audio.getcomptype()) != (
            16000, 1, 2, "NONE"
        ):
            raise ValueError("16kHz mono PCM16 WAV required")
        samples = np.frombuffer(audio.readframes(audio.getnframes()), "<i2")
    if not len(samples):
        raise ValueError("WAV contains no samples")
    return samples.astype("float32") / 32768.0


def _window_starts(sample_count: int, size: int = 64000, stride: int = 32000) -> list[int]:
    if sample_count <= size:
        return [0]
    starts = list(range(0, sample_count - size + 1, stride))
    final_start = sample_count - size
    if starts[-1] != final_start:
        starts.append(final_start)
    return starts


def predict(path: Path, state) -> dict:
    np, torch, extractor, model, head, labels, decide, predict_head, prepare_view, device, manifest = state
    samples = _read_samples(path, np)
    starts = _window_starts(len(samples))
    embeddings = []
    started = time.perf_counter()
    for start in starts:
        segment = samples[start:start + 64000]
        prepared, _, low_signal = prepare_view(segment, "activity4")
        if low_signal:
            continue
        values = extractor(prepared, sampling_rate=16000, return_tensors="pt").input_values.to(device)
        with torch.inference_mode():
            embedding = model.audio_spectrogram_transformer(
                input_values=values, return_dict=True
            ).pooler_output.cpu().numpy().astype("float32")
        embeddings.append(embedding[0])

    if not embeddings:
        probabilities = np.array([[0., 0., 0., 0., 1., 0.]], dtype="float32")
        quality = "low_signal"
    else:
        averaged_embedding = np.mean(np.stack(embeddings), axis=0, keepdims=True, dtype="float32")
        probabilities = predict_head(head, averaged_embedding)
        quality = "normal"
    index = int(decide(probabilities)[0])
    return {
        "label": labels[index],
        "top_label": labels[int(probabilities[0].argmax())],
        "score": float(probabilities[0].max()),
        "probabilities": {label: float(probabilities[0][i]) for i, label in enumerate(labels)},
        "signal_quality": quality,
        "model_version": manifest["model_id"],
        "window_count": len(starts),
        "window_starts": starts,
        "window_policy": "4s-mean-embedding-stride2s-v1",
        "device": device,
        "cpu_threads": torch.get_num_threads(),
        "inference_ms": round((time.perf_counter() - started) * 1000),
    }


def main() -> None:
    root = Path(sys.argv[1])
    state = load_runtime(root)
    manifest = state[-1]
    print(json.dumps({"status": "ready", "model_version": manifest["model_id"],
                      "device": state[-2], "cpu_threads": state[1].get_num_threads()}), flush=True)
    for line in sys.stdin:
        try:
            request = json.loads(line)
            result = predict(Path(request["wav_path"]), state)
            print(json.dumps(result, allow_nan=False), flush=True)
        except Exception as error:
            print(json.dumps({"error": f"{type(error).__name__}: {error}"}), flush=True)


if __name__ == "__main__":
    main()
