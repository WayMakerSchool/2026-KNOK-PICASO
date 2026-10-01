"""Train a compact raw-waveform classifier and export it to TensorFlow Lite.

The model accepts exactly four seconds of 16 kHz mono float audio (64,000
samples).  This keeps Android inference simple: the app only needs to parse
the ESP32 PCM16 WAV and convert samples to float32.  The model is a first
deployment candidate, not a production-quality acoustic model.
"""

from __future__ import annotations

import argparse
import csv
import json
import tempfile
from pathlib import Path

import numpy as np
import soundfile as sf
import tensorflow as tf
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score


SAMPLE_RATE = 16_000
CLIP_SECONDS = 4
CLIP_SAMPLES = SAMPLE_RATE * CLIP_SECONDS
RANDOM_SEED = 42
BATCH_SIZE = 16


def load_waveform(path: Path) -> np.ndarray:
    samples, sample_rate = sf.read(path, dtype="float32", always_2d=False)
    if samples.ndim == 2:
        samples = samples.mean(axis=1)
    if sample_rate != SAMPLE_RATE:
        raise ValueError(f"{path}: expected {SAMPLE_RATE} Hz, got {sample_rate} Hz")
    samples = np.asarray(samples, dtype=np.float32)
    if samples.size < CLIP_SAMPLES:
        samples = np.pad(samples, (0, CLIP_SAMPLES - samples.size))
    elif samples.size > CLIP_SAMPLES:
        samples = samples[:CLIP_SAMPLES]
    return np.nan_to_num(samples, nan=0.0, posinf=0.0, neginf=0.0)


def read_manifest(dataset_dir: Path) -> list[dict[str, str]]:
    with (dataset_dir / "manifest.csv").open("r", encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        raise ValueError("Dataset manifest is empty")
    return rows


def load_split(rows: list[dict[str, str]], dataset_dir: Path, split: str, label_to_index: dict[str, int]) -> tuple[np.ndarray, np.ndarray, list[str]]:
    selected = [row for row in rows if row["split"] == split]
    selected.sort(key=lambda row: (row["label"], row["source_file"]))
    waveforms = []
    labels = []
    files = []
    for row in selected:
        path = dataset_dir / row["processed_file"]
        waveforms.append(load_waveform(path))
        labels.append(label_to_index[row["label"]])
        files.append(row["processed_file"])
    return np.stack(waveforms).astype(np.float32)[..., None], np.asarray(labels, dtype=np.int32), files


def build_model(class_count: int) -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(CLIP_SAMPLES, 1), name="audio")
    x = tf.keras.layers.Conv1D(8, 80, strides=4, padding="same", activation="relu")(inputs)
    x = tf.keras.layers.MaxPooling1D(pool_size=4)(x)
    x = tf.keras.layers.Conv1D(16, 9, padding="same", activation="relu")(x)
    x = tf.keras.layers.MaxPooling1D(pool_size=4)(x)
    x = tf.keras.layers.Conv1D(32, 9, padding="same", activation="relu")(x)
    x = tf.keras.layers.GlobalAveragePooling1D()(x)
    x = tf.keras.layers.Dense(32, activation="relu")(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    outputs = tf.keras.layers.Dense(class_count, activation="softmax", name="probabilities")(x)
    model = tf.keras.Model(inputs=inputs, outputs=outputs)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def evaluate_model(model: tf.keras.Model, audio: np.ndarray, labels: np.ndarray, classes: list[str]) -> dict[str, object]:
    probabilities = model.predict(audio, batch_size=BATCH_SIZE, verbose=0)
    predicted = np.argmax(probabilities, axis=1)
    return {
        "sample_count": int(labels.size),
        "accuracy": float(accuracy_score(labels, predicted)),
        "macro_f1": float(f1_score(labels, predicted, labels=np.arange(len(classes)), average="macro", zero_division=0)),
        "classification_report": classification_report(
            labels,
            predicted,
            labels=np.arange(len(classes)),
            target_names=classes,
            output_dict=True,
            zero_division=0,
        ),
        "classes": classes,
        "confusion_matrix": confusion_matrix(labels, predicted, labels=np.arange(len(classes))).tolist(),
        "predicted": predicted,
        "probabilities": probabilities,
    }


def export_tflite(model: tf.keras.Model, output_path: Path) -> None:
    # Keras 3 no longer exposes the private save-spec method expected by some
    # TensorFlow Lite versions.  Exporting a SavedModel first keeps conversion
    # compatible with both Keras 3 and the installed TensorFlow runtime.
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="_saved_model_", dir=output_path.parent) as saved_model_dir:
        model.export(saved_model_dir, verbose=False)
        converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
        output_path.write_bytes(converter.convert())


def verify_tflite(model_path: Path, sample: np.ndarray) -> dict[str, object]:
    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]
    interpreter.set_tensor(input_details["index"], sample[:1].astype(np.float32))
    interpreter.invoke()
    output = interpreter.get_tensor(output_details["index"])
    return {
        "input_shape": input_details["shape"].tolist(),
        "input_dtype": str(input_details["dtype"]),
        "output_shape": output_details["shape"].tolist(),
        "output_dtype": str(output_details["dtype"]),
        "output_sum": float(output[0].sum()),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", type=Path, default=Path("dataset/with_unknown"))
    parser.add_argument("--output-dir", type=Path, default=Path("artifacts/raw_tflite"))
    parser.add_argument("--epochs", type=int, default=60)
    args = parser.parse_args()

    tf.keras.utils.set_random_seed(RANDOM_SEED)
    tf.get_logger().setLevel("ERROR")
    dataset_dir = args.dataset_dir.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    rows = read_manifest(dataset_dir)
    classes = sorted({row["label"] for row in rows})
    label_to_index = {label: index for index, label in enumerate(classes)}
    train_x, train_y, _ = load_split(rows, dataset_dir, "train", label_to_index)
    validation_x, validation_y, _ = load_split(rows, dataset_dir, "validation", label_to_index)
    test_x, test_y, test_files = load_split(rows, dataset_dir, "test", label_to_index)

    model = build_model(len(classes))
    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_accuracy",
            patience=10,
            mode="max",
            restore_best_weights=True,
        )
    ]
    history = model.fit(
        train_x,
        train_y,
        validation_data=(validation_x, validation_y),
        epochs=args.epochs,
        batch_size=BATCH_SIZE,
        callbacks=callbacks,
        verbose=2,
    )

    validation_result = evaluate_model(model, validation_x, validation_y, classes)
    test_result = evaluate_model(model, test_x, test_y, classes)
    model_path = output_dir / "noise_classifier.tflite"
    export_tflite(model, model_path)
    tflite_check = verify_tflite(model_path, test_x)

    (output_dir / "labels.txt").write_text("\n".join(classes) + "\n", encoding="utf-8")
    metrics = {
        "model": "compact raw waveform Conv1D",
        "input": {"sample_rate": SAMPLE_RATE, "channels": 1, "samples": CLIP_SAMPLES, "dtype": "float32"},
        "classes": classes,
        "train_samples": int(train_y.size),
        "validation": {key: value for key, value in validation_result.items() if key not in {"predicted", "probabilities"}},
        "test": {key: value for key, value in test_result.items() if key not in {"predicted", "probabilities"}},
        "tflite": tflite_check,
        "epochs_ran": len(history.history.get("loss", [])),
    }
    with (output_dir / "metrics.json").open("w", encoding="utf-8") as handle:
        json.dump(metrics, handle, ensure_ascii=False, indent=2)

    with (output_dir / "test_predictions.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["processed_file", "actual_label", "predicted_label", "confidence"])
        writer.writeheader()
        for file_name, actual_index, predicted_index, probabilities in zip(
            test_files, test_y, test_result["predicted"], test_result["probabilities"]
        ):
            writer.writerow(
                {
                    "processed_file": file_name,
                    "actual_label": classes[int(actual_index)],
                    "predicted_label": classes[int(predicted_index)],
                    "confidence": f"{float(np.max(probabilities)):.6f}",
                }
            )

    print(f"Train samples: {train_y.size}")
    print(f"Validation accuracy: {validation_result['accuracy']:.4f}")
    print(f"Validation macro F1: {validation_result['macro_f1']:.4f}")
    print(f"Test samples: {test_y.size}")
    print(f"Test accuracy: {test_result['accuracy']:.4f}")
    print(f"Test macro F1: {test_result['macro_f1']:.4f}")
    print(f"TFLite: {model_path}")
    print(f"TFLite check: {tflite_check}")


if __name__ == "__main__":
    main()
