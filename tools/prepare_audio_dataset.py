"""Prepare the inter-floor-noise WAV dataset for the KNOK prototype.

The source dataset contains 22.05 kHz mono IEEE-float WAV files.  This script
keeps the source files untouched, resamples them to the ESP32 format
(16 kHz, mono, signed 16-bit PCM), and writes a deterministic stratified
train/validation/test manifest.

Example:
    python tools/prepare_audio_dataset.py \
        --input-dir "C:\\Users\\User\\Downloads\\13094441" \
        --output-dir dataset
"""

from __future__ import annotations

import argparse
import csv
import re
import struct
import wave
from collections import Counter
from pathlib import Path
from random import Random

import numpy as np
from scipy.signal import resample_poly


TARGET_SAMPLE_RATE = 16_000
TARGET_CHANNELS = 1
TARGET_SAMPLE_WIDTH_BYTES = 2
DEFAULT_SEED = 42
LABEL_PATTERN = re.compile(r"^(?P<label>.*?)(?P<index>\d+)$")


def read_wav(path: Path) -> tuple[np.ndarray, dict[str, int | float]]:
    """Read a RIFF/WAVE file and return normalized mono float samples."""

    with path.open("rb") as stream:
        if stream.read(4) != b"RIFF":
            raise ValueError(f"{path.name}: not a RIFF WAV file")
        stream.read(4)
        if stream.read(4) != b"WAVE":
            raise ValueError(f"{path.name}: not a WAVE file")

        fmt: tuple[int, int, int, int, int, int] | None = None
        data: bytes | None = None

        while True:
            chunk_header = stream.read(8)
            if len(chunk_header) != 8:
                break
            chunk_id, chunk_size = struct.unpack("<4sI", chunk_header)
            chunk = stream.read(chunk_size)
            if len(chunk) != chunk_size:
                raise ValueError(f"{path.name}: truncated {chunk_id!r} chunk")
            if chunk_size % 2:
                stream.seek(1, 1)

            if chunk_id == b"fmt ":
                if len(chunk) < 16:
                    raise ValueError(f"{path.name}: invalid fmt chunk")
                fmt = struct.unpack("<HHIIHH", chunk[:16])
            elif chunk_id == b"data":
                data = chunk

    if fmt is None or data is None:
        raise ValueError(f"{path.name}: missing fmt or data chunk")

    audio_format, channels, sample_rate, byte_rate, block_align, bits = fmt
    if channels < 1 or sample_rate < 1:
        raise ValueError(f"{path.name}: invalid channel/sample-rate metadata")

    if audio_format == 3:  # IEEE float
        if bits == 32:
            samples = np.frombuffer(data, dtype="<f4").astype(np.float32, copy=True)
        elif bits == 64:
            samples = np.frombuffer(data, dtype="<f8").astype(np.float32, copy=True)
        else:
            raise ValueError(f"{path.name}: unsupported float depth: {bits}")
    elif audio_format == 1:  # PCM integer
        if bits == 8:
            samples = (np.frombuffer(data, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
        elif bits == 16:
            samples = np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0
        elif bits == 24:
            raw = np.frombuffer(data, dtype=np.uint8).reshape(-1, 3)
            values = (
                raw[:, 0].astype(np.int32)
                | (raw[:, 1].astype(np.int32) << 8)
                | (raw[:, 2].astype(np.int32) << 16)
            )
            values = np.where(values & 0x800000, values - 0x1000000, values)
            samples = values.astype(np.float32) / 8_388_608.0
        elif bits == 32:
            samples = np.frombuffer(data, dtype="<i4").astype(np.float32) / 2_147_483_648.0
        else:
            raise ValueError(f"{path.name}: unsupported PCM depth: {bits}")
    else:
        raise ValueError(f"{path.name}: unsupported WAV format code: {audio_format}")

    if samples.size % channels != 0:
        raise ValueError(f"{path.name}: data does not align to channel count")
    if channels > 1:
        samples = samples.reshape(-1, channels).mean(axis=1)

    finite = np.isfinite(samples)
    non_finite_count = int((~finite).sum())
    if non_finite_count:
        samples[~finite] = 0.0

    peak_before_clip = float(np.max(np.abs(samples))) if samples.size else 0.0
    clipped_count = int(np.count_nonzero(np.abs(samples) > 1.0))
    samples = np.clip(samples, -1.0, 1.0)

    return samples, {
        "audio_format": audio_format,
        "channels": channels,
        "sample_rate": sample_rate,
        "byte_rate": byte_rate,
        "block_align": block_align,
        "bits_per_sample": bits,
        "source_samples": int(samples.size),
        "peak_before_clip": peak_before_clip,
        "clipped_samples": clipped_count,
        "non_finite_samples": non_finite_count,
    }


def resample_to_target(samples: np.ndarray, source_rate: int) -> np.ndarray:
    """Resample and force the exact duration implied by the source samples."""

    expected_length = int(round(samples.size * TARGET_SAMPLE_RATE / source_rate))
    if source_rate != TARGET_SAMPLE_RATE:
        samples = resample_poly(samples, TARGET_SAMPLE_RATE, source_rate)

    if samples.size < expected_length:
        samples = np.pad(samples, (0, expected_length - samples.size))
    elif samples.size > expected_length:
        samples = samples[:expected_length]
    return np.asarray(samples, dtype=np.float32)


def extract_label(path: Path) -> str:
    match = LABEL_PATTERN.match(path.stem)
    if not match:
        raise ValueError(f"Cannot infer label from filename: {path.name}")
    return match.group("label").rstrip("_") or path.stem


def split_files(paths: list[Path], seed: int) -> dict[str, str]:
    """Create a deterministic per-label 70/15/15 split."""

    by_label: dict[str, list[Path]] = {}
    for path in paths:
        by_label.setdefault(extract_label(path), []).append(path)

    assignments: dict[str, str] = {}
    rng = Random(seed)
    for label, label_paths in sorted(by_label.items()):
        shuffled = sorted(label_paths, key=lambda item: item.name)
        rng.shuffle(shuffled)
        train_count = int(len(shuffled) * 0.70)
        validation_count = int(len(shuffled) * 0.15)
        for index, path in enumerate(shuffled):
            if index < train_count:
                split = "train"
            elif index < train_count + validation_count:
                split = "validation"
            else:
                split = "test"
            assignments[path.name] = split
    return assignments


def write_pcm16_wav(path: Path, samples: np.ndarray) -> None:
    pcm = np.clip(np.rint(samples * 32767.0), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as output:
        output.setnchannels(TARGET_CHANNELS)
        output.setsampwidth(TARGET_SAMPLE_WIDTH_BYTES)
        output.setframerate(TARGET_SAMPLE_RATE)
        output.writeframes(pcm.tobytes())


def prepare_dataset(input_dir: Path, output_dir: Path, seed: int) -> None:
    source_files = sorted(input_dir.glob("*.wav"))
    if not source_files:
        raise FileNotFoundError(f"No .wav files found in {input_dir}")

    processed_dir = output_dir / "processed_16k"
    processed_dir.mkdir(parents=True, exist_ok=True)

    assignments = split_files(source_files, seed)
    rows: list[dict[str, str | int | float]] = []
    failures: list[str] = []

    for source_path in source_files:
        try:
            samples, source_info = read_wav(source_path)
            output_samples = resample_to_target(samples, int(source_info["sample_rate"]))
            output_path = processed_dir / source_path.name
            write_pcm16_wav(output_path, output_samples)
            label = extract_label(source_path)
            rows.append(
                {
                    "split": assignments[source_path.name],
                    "label": label,
                    "source_file": source_path.name,
                    "processed_file": output_path.relative_to(output_dir).as_posix(),
                    "source_format": int(source_info["audio_format"]),
                    "source_sample_rate": int(source_info["sample_rate"]),
                    "source_channels": int(source_info["channels"]),
                    "source_bits_per_sample": int(source_info["bits_per_sample"]),
                    "target_sample_rate": TARGET_SAMPLE_RATE,
                    "target_channels": TARGET_CHANNELS,
                    "target_bits_per_sample": TARGET_SAMPLE_WIDTH_BYTES * 8,
                    "duration_sec": round(output_samples.size / TARGET_SAMPLE_RATE, 3),
                    "peak_before_clip": round(float(source_info["peak_before_clip"]), 6),
                    "clipped_samples": int(source_info["clipped_samples"]),
                    "non_finite_samples": int(source_info["non_finite_samples"]),
                }
            )
        except (OSError, ValueError, struct.error) as error:
            failures.append(str(error))

    if failures:
        for failure in failures:
            print(f"ERROR: {failure}")
        raise RuntimeError(f"Failed to process {len(failures)} file(s)")

    rows.sort(key=lambda row: (str(row["label"]), str(row["source_file"])))
    manifest_path = output_dir / "manifest.csv"
    output_dir.mkdir(parents=True, exist_ok=True)
    fieldnames = list(rows[0].keys())
    with manifest_path.open("w", encoding="utf-8", newline="") as manifest:
        writer = csv.DictWriter(manifest, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    class_counts = Counter(str(row["label"]) for row in rows)
    split_counts = Counter(str(row["split"]) for row in rows)
    print(f"Processed {len(rows)} WAV files")
    print(f"Output: {processed_dir}")
    print(f"Manifest: {manifest_path}")
    print(f"Classes: {dict(sorted(class_counts.items()))}")
    print(f"Splits: {dict(sorted(split_counts.items()))}")
    print(f"Target format: {TARGET_SAMPLE_RATE} Hz, mono, signed 16-bit PCM")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("dataset"))
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    args = parser.parse_args()

    prepare_dataset(args.input_dir.resolve(), args.output_dir.resolve(), args.seed)


if __name__ == "__main__":
    main()
