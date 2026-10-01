"""Train and evaluate a small local audio classifier.

This is an intentionally lightweight baseline for the prepared KNOK dataset.
It extracts fixed-size log-mel/MFCC statistics with NumPy and trains an SVM.
The model is useful for measuring whether the dataset contains a learnable
signal; it is not yet the Android deployment model.  The feature definition
can later be ported to a TensorFlow Lite/YAMNet pipeline.

Example:
    python tools/train_audio_baseline.py \
        --dataset-dir dataset \
        --output-dir artifacts/audio_baseline
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

import joblib
import numpy as np
import soundfile as sf
from scipy.signal import resample_poly
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
)
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.svm import SVC


SAMPLE_RATE = 16_000
CLIP_SECONDS = 4
CLIP_SAMPLES = SAMPLE_RATE * CLIP_SECONDS
RANDOM_STATE = 42
FEATURE_CONFIG = {
    "sample_rate": SAMPLE_RATE,
    "clip_seconds": CLIP_SECONDS,
    "n_fft": 1024,
    "win_length": 640,
    "hop_length": 320,
    "n_mels": 64,
    "n_mfcc": 20,
    "fmin": 20,
    "fmax": 8000,
    "description": "mean/std/percentile statistics of NumPy log-mel, MFCC, delta, delta2 plus RMS/peak",
}


def hz_to_mel(frequency: np.ndarray) -> np.ndarray:
    return 2595.0 * np.log10(1.0 + frequency / 700.0)


def mel_to_hz(mel: np.ndarray) -> np.ndarray:
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


def build_mel_filter_bank() -> np.ndarray:
    n_fft = int(FEATURE_CONFIG["n_fft"])
    n_mels = int(FEATURE_CONFIG["n_mels"])
    frequencies = np.linspace(
        float(FEATURE_CONFIG["fmin"]),
        float(FEATURE_CONFIG["fmax"]),
        n_mels + 2,
    )
    # The linear spacing above is only used to size the array.  Mel spacing is
    # applied to the actual filter corner frequencies below.
    mel_points = np.linspace(
        hz_to_mel(np.array([FEATURE_CONFIG["fmin"]]))[0],
        hz_to_mel(np.array([FEATURE_CONFIG["fmax"]]))[0],
        n_mels + 2,
    )
    mel_frequencies = mel_to_hz(mel_points)
    bin_numbers = np.floor((n_fft + 1) * mel_frequencies / SAMPLE_RATE).astype(int)
    bank = np.zeros((n_mels, n_fft // 2 + 1), dtype=np.float32)
    for index in range(n_mels):
        left, center, right = bin_numbers[index : index + 3]
        if center > left:
            bank[index, left:center] = np.linspace(0.0, 1.0, center - left, endpoint=False)
        if right > center:
            bank[index, center:right] = np.linspace(1.0, 0.0, right - center, endpoint=False)
    # Keep the variable above visible in a debugger and silence lint warnings
    # without adding another dependency.
    del frequencies
    return bank


def build_dct_basis() -> np.ndarray:
    n_mels = int(FEATURE_CONFIG["n_mels"])
    n_mfcc = int(FEATURE_CONFIG["n_mfcc"])
    positions = np.arange(n_mels, dtype=np.float32)[None, :]
    coefficients = np.arange(n_mfcc, dtype=np.float32)[:, None]
    basis = np.cos(np.pi / n_mels * (positions + 0.5) * coefficients)
    basis[0] *= 1.0 / np.sqrt(n_mels)
    basis[1:] *= np.sqrt(2.0 / n_mels)
    return basis.astype(np.float32)


MEL_FILTER_BANK = build_mel_filter_bank()
DCT_BASIS = build_dct_basis()
WINDOW = np.hanning(int(FEATURE_CONFIG["n_fft"])).astype(np.float32)


def load_mono_audio(path: Path) -> np.ndarray:
    samples, source_rate = sf.read(path, dtype="float32", always_2d=False)
    if samples.ndim == 2:
        samples = samples.mean(axis=1)
    if source_rate != SAMPLE_RATE:
        samples = resample_poly(samples, SAMPLE_RATE, source_rate)
    samples = np.asarray(samples, dtype=np.float32)
    if samples.size < CLIP_SAMPLES:
        samples = np.pad(samples, (0, CLIP_SAMPLES - samples.size))
    elif samples.size > CLIP_SAMPLES:
        samples = samples[:CLIP_SAMPLES]
    return np.nan_to_num(samples, nan=0.0, posinf=0.0, neginf=0.0)


def extract_features(path: Path) -> np.ndarray:
    samples = load_mono_audio(path)
    n_fft = int(FEATURE_CONFIG["n_fft"])
    hop_length = int(FEATURE_CONFIG["hop_length"])
    padded = np.pad(samples, (n_fft // 2, n_fft // 2), mode="reflect")
    frames = np.lib.stride_tricks.sliding_window_view(padded, n_fft)[::hop_length]
    frames = np.asarray(frames, dtype=np.float32)
    windowed = frames * WINDOW
    spectrum = np.abs(np.fft.rfft(windowed, n=n_fft, axis=1)) ** 2
    spectrum /= max(float(np.sum(WINDOW**2)), 1e-12)
    mel_power = spectrum @ MEL_FILTER_BANK.T
    log_mel = 10.0 * np.log10(np.maximum(mel_power, 1e-12)).T.astype(np.float32)
    mfcc = DCT_BASIS @ log_mel
    delta = np.gradient(mfcc, axis=1)
    delta2 = np.gradient(delta, axis=1)

    def statistics(matrix: np.ndarray) -> np.ndarray:
        return np.concatenate(
            [
                matrix.mean(axis=1),
                matrix.std(axis=1),
                np.percentile(matrix, 10, axis=1),
                np.percentile(matrix, 90, axis=1),
            ]
        )

    rms = np.sqrt(np.mean(windowed**2, axis=1) + 1e-12)
    rms_db = 20.0 * np.log10(np.maximum(rms, 1e-6))
    scalar_features = np.array(
        [
            float(rms_db.mean()),
            float(rms_db.std()),
            float(np.max(np.abs(samples))),
            float(np.mean(np.abs(samples))),
            float(np.mean(samples[:-1] * samples[1:] < 0.0)),
        ],
        dtype=np.float32,
    )

    features = np.concatenate(
        [statistics(log_mel), statistics(mfcc), statistics(delta), statistics(delta2), scalar_features]
    )
    return np.nan_to_num(features, nan=0.0, posinf=0.0, neginf=0.0).astype(np.float32)


def read_manifest(dataset_dir: Path) -> list[dict[str, str]]:
    manifest_path = dataset_dir / "manifest.csv"
    with manifest_path.open("r", encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        raise ValueError(f"Manifest is empty: {manifest_path}")
    return rows


def build_split(rows: list[dict[str, str]], dataset_dir: Path, split: str) -> tuple[np.ndarray, np.ndarray, list[str]]:
    split_rows = [row for row in rows if row["split"] == split]
    if not split_rows:
        raise ValueError(f"No rows found for split: {split}")
    split_rows.sort(key=lambda row: (row["label"], row["source_file"]))

    features: list[np.ndarray] = []
    labels: list[str] = []
    files: list[str] = []
    for row in split_rows:
        path = dataset_dir / row["processed_file"]
        if not path.exists():
            raise FileNotFoundError(path)
        features.append(extract_features(path))
        labels.append(row["label"])
        files.append(row["processed_file"])

    return np.vstack(features), np.asarray(labels), files


def save_predictions(path: Path, files: list[str], actual: np.ndarray, predicted: np.ndarray, probabilities: np.ndarray, classes: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        fieldnames = ["processed_file", "actual_label", "predicted_label", "confidence"]
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for file_name, actual_label, predicted_label, row in zip(files, actual, predicted, probabilities):
            predicted_index = int(np.where(classes == predicted_label)[0][0])
            writer.writerow(
                {
                    "processed_file": file_name,
                    "actual_label": str(actual_label),
                    "predicted_label": str(predicted_label),
                    "confidence": f"{float(row[predicted_index]):.6f}",
                }
            )


def evaluate(model: Pipeline, features: np.ndarray, labels: np.ndarray, classes: np.ndarray) -> dict[str, object]:
    predicted = model.predict(features)
    probabilities = model.predict_proba(features)
    matrix = confusion_matrix(labels, predicted, labels=classes).tolist()
    return {
        "sample_count": int(labels.size),
        "accuracy": float(accuracy_score(labels, predicted)),
        "macro_f1": float(f1_score(labels, predicted, labels=classes, average="macro", zero_division=0)),
        "classification_report": classification_report(
            labels,
            predicted,
            labels=classes,
            target_names=classes,
            output_dict=True,
            zero_division=0,
        ),
        "classes": classes.tolist(),
        "confusion_matrix": matrix,
        "predicted": predicted,
        "probabilities": probabilities,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", type=Path, default=Path("dataset"))
    parser.add_argument("--output-dir", type=Path, default=Path("artifacts/audio_baseline"))
    args = parser.parse_args()

    dataset_dir = args.dataset_dir.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    rows = read_manifest(dataset_dir)
    train_x, train_y, _ = build_split(rows, dataset_dir, "train")
    validation_x, validation_y, _ = build_split(rows, dataset_dir, "validation")
    test_x, test_y, test_files = build_split(rows, dataset_dir, "test")
    classes = np.asarray(sorted(set(train_y) | set(validation_y) | set(test_y)))

    model = Pipeline(
        [
            ("scaler", StandardScaler()),
            (
                "classifier",
                SVC(
                    C=10.0,
                    kernel="rbf",
                    class_weight="balanced",
                    probability=True,
                    random_state=RANDOM_STATE,
                ),
            ),
        ]
    )
    model.fit(train_x, train_y)

    validation_result = evaluate(model, validation_x, validation_y, classes)
    test_result = evaluate(model, test_x, test_y, classes)
    save_predictions(
        output_dir / "test_predictions.csv",
        test_files,
        test_y,
        test_result["predicted"],
        test_result["probabilities"],
        classes,
    )

    metrics = {
        "model": "StandardScaler + RBF SVM",
        "feature_config": FEATURE_CONFIG,
        "dataset_dir": str(dataset_dir),
        "train_samples": int(train_y.size),
        "validation": {key: value for key, value in validation_result.items() if key not in {"predicted", "probabilities"}},
        "test": {key: value for key, value in test_result.items() if key not in {"predicted", "probabilities"}},
    }
    with (output_dir / "metrics.json").open("w", encoding="utf-8") as handle:
        json.dump(metrics, handle, ensure_ascii=False, indent=2)

    joblib.dump(
        {
            "model": model,
            "feature_config": FEATURE_CONFIG,
            "classes": classes.tolist(),
        },
        output_dir / "model.joblib",
    )

    print(f"Train samples: {train_y.size}")
    print(f"Validation accuracy: {validation_result['accuracy']:.4f}")
    print(f"Validation macro F1: {validation_result['macro_f1']:.4f}")
    print(f"Test samples: {test_y.size}")
    print(f"Test accuracy: {test_result['accuracy']:.4f}")
    print(f"Test macro F1: {test_result['macro_f1']:.4f}")
    print(f"Artifacts: {output_dir}")


if __name__ == "__main__":
    main()
