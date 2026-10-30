"""JSON-lines worker for the preserved floor-direction research model."""
import os
os.environ.update(OPENBLAS_NUM_THREADS="1", OMP_NUM_THREADS="2", VECLIB_MAXIMUM_THREADS="1", NUMBA_NUM_THREADS="1")
import hashlib
import json
from pathlib import Path
import sys
import time

FLOOR = Path(__file__).resolve().parent.parent / "floor"
MODEL = FLOOR / "current/floor-svm.joblib"
MODEL_HASH = "657606325fdb0b68073ada1bca6bbad85edf9bc7808176e411c188afd62ae313"
VERSION = "knok-research-floor-clean-r20-20261027"
sys.path.insert(0, str(FLOOR))


def main():
    import joblib
    import improve_floor
    if hashlib.sha256(MODEL.read_bytes()).hexdigest() != MODEL_HASH:
        raise RuntimeError("Floor model checksum mismatch")
    model = joblib.load(MODEL)
    if model["feature_dimensions"] != 226 or model["feature_version"] != improve_floor.FEATURE_VERSION:
        raise RuntimeError("Floor feature contract mismatch")
    print(json.dumps({"status": "ready", "model_version": VERSION}), flush=True)
    for line in sys.stdin:
        try:
            req = json.loads(line)
            receiver = req["receiver_position"]
            if receiver not in ("lower", "upper"):
                raise ValueError("Registered receiver position required")
            started = time.monotonic()
            vector = improve_floor.audio_features(req["wav_path"])["shape"][None, :]
            label = str(model["pipeline"].predict(vector)[0]).split("|")[-1]
            if label not in ("below", "same", "above"):
                raise ValueError("Invalid floor prediction")
            print(json.dumps({"label": label, "model_version": VERSION, "receiver_position": receiver,
                              "inference_ms": round((time.monotonic() - started) * 1000)}), flush=True)
        except Exception as exc:
            print(json.dumps({"error": f"{type(exc).__name__}: {exc}"}), flush=True)


if __name__ == "__main__":
    main()
