"""SVM process running in the preserved floor-research virtual environment."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import time


def load_runtime(project_root: Path):
    project_root = project_root.resolve()
    sys.path.insert(0, str(project_root))
    import joblib
    import numpy as np
    import improve_floor
    import stabilize_floor

    artifacts = project_root / "v3" / "artifacts"
    card = json.loads((artifacts / "model-card.json").read_text())
    model_path = artifacts / "floor-model-v3.joblib"
    digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
    if digest != card["model_sha256"]:
        raise RuntimeError("Floor research model hash does not match its model card")
    model = joblib.load(model_path)
    if card["feature_version"] != improve_floor.FEATURE_VERSION:
        raise RuntimeError("Floor research feature version mismatch")
    return improve_floor, stabilize_floor, model, card, np


def predict(path: Path, receiver_position: str, state) -> dict:
    feature_module, stable_module, model, card, np = state
    if receiver_position not in ("lower", "upper"):
        raise ValueError("Registered receiver position required")
    started = time.perf_counter()
    features = feature_module.audio_features(path)
    prediction = stable_module.checked_predict(model, np.asarray([features["shape"]], dtype="float32"))
    label = str(np.asarray(prediction).reshape(-1)[0])
    return {
        "label": label,
        "model_version": card["model_id"],
        "feature_version": card["feature_version"],
        "receiver_position": receiver_position,
        "inference_ms": round((time.perf_counter() - started) * 1000),
    }


def main() -> None:
    state = load_runtime(Path(sys.argv[1]))
    card = state[3]
    print(json.dumps({"status": "ready", "model_version": card["model_id"]}), flush=True)
    for line in sys.stdin:
        try:
            request = json.loads(line)
            result = predict(Path(request["wav_path"]), request["receiver_position"], state)
            print(json.dumps(result, allow_nan=False), flush=True)
        except Exception as error:
            print(json.dumps({"error": f"{type(error).__name__}: {error}"}), flush=True)


if __name__ == "__main__":
    main()
