"""Run the local baseline classifier on a WAV file.

The confidence threshold is only a provisional open-set gate until real
normal/background recordings are added to the training set.  It prevents the
CLI from presenting a low-confidence five-class guess as a certain result.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import joblib
import numpy as np

from train_audio_baseline import extract_features


INTERFLOOR_LABELS = {
    "footstep",
    "instant_impact",
    "dragging_furniture",
    "hammering",
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audio", type=Path)
    parser.add_argument(
        "--model",
        type=Path,
        default=Path("artifacts/audio_baseline/model.joblib"),
    )
    parser.add_argument(
        "--unknown-threshold",
        type=float,
        default=0.65,
        help="Provisional minimum SVM probability for accepting a known class.",
    )
    args = parser.parse_args()

    bundle = joblib.load(args.model)
    model = bundle["model"]
    classes = np.asarray(bundle["classes"])
    features = extract_features(args.audio).reshape(1, -1)
    probabilities = model.predict_proba(features)[0]
    best_index = int(np.argmax(probabilities))
    confidence = float(probabilities[best_index])
    predicted_label = str(classes[best_index])
    is_unknown = confidence < args.unknown_threshold
    is_interfloor_candidate = (predicted_label in INTERFLOOR_LABELS) and not is_unknown

    result = {
        "audio": str(args.audio.resolve()),
        "label": "normal_or_unknown" if is_unknown else predicted_label,
        "raw_predicted_label": predicted_label,
        "confidence": round(confidence, 6),
        "unknown_threshold": args.unknown_threshold,
        "interfloor_candidate": is_interfloor_candidate,
        "probabilities": {
            str(label): round(float(probability), 6)
            for label, probability in zip(classes, probabilities)
        },
        "warning": "Threshold is provisional; add real normal/background recordings before production use.",
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
