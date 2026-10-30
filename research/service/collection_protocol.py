"""Deterministic trial schedules for KNOK's Wednesday two-model evaluation."""

from __future__ import annotations

import csv
import hashlib
import random
from pathlib import Path
from typing import Any
import wave


SOUND_LABELS = (
    "dragging_furniture",
    "footstep",
    "hammering",
    "instant_impact",
    "normal_or_unknown",
    "vacuum_cleaner",
)
FLOOR_SOUND_LABELS = ("dragging_furniture", "hammering", "instant_impact")
FLOOR_LABELS = ("above", "same", "below")
SESSIONS = ("S1", "S2", "S3")

FIELDS = (
    "event_id",
    "device_event_id",
    "session",
    "sound_label",
    "floor_label",
    "receiver_position",
    "source_story",
    "score_sound",
    "score_floor",
    "wav_path",
    "file_sha256",
    "pcm_sha256",
    "sample_rate",
    "sample_count",
    "duration_seconds",
    "capture_status",
    "device_received",
    "sound_prediction",
    "floor_prediction",
    "saved_record_id",
    "notes",
)


def _row(event_id: str, *, session: str, sound: str, floor: str = "",
         receiver: str = "", source: str = "", score_sound: bool = False,
         score_floor: bool = False) -> dict[str, Any]:
    return {
        "event_id": event_id,
        "device_event_id": "",
        "session": session,
        "sound_label": sound,
        "floor_label": floor,
        "receiver_position": receiver,
        "source_story": source,
        "score_sound": score_sound,
        "score_floor": score_floor,
        "wav_path": "",
        "file_sha256": "",
        "pcm_sha256": "",
        "sample_rate": "",
        "sample_count": "",
        "duration_seconds": "",
        "capture_status": "planned",
        "device_received": "",
        "sound_prediction": "",
        "floor_prediction": "",
        "saved_record_id": "",
        "notes": "",
    }


def make_development_schedule(seed: int = 20261028) -> list[dict[str, Any]]:
    """Make 108 balanced learning events, split over three capture sessions."""
    rng = random.Random(seed)
    rows: list[dict[str, Any]] = []
    serial = 0

    for sound in FLOOR_SOUND_LABELS:
        for floor in FLOOR_LABELS:
            for repeat in range(8):
                serial += 1
                if floor == "above":
                    receiver, source = "lower", "upper"
                elif floor == "below":
                    receiver, source = "upper", "lower"
                else:
                    receiver = "lower" if repeat < 4 else "upper"
                    source = receiver
                session = SESSIONS[repeat % len(SESSIONS)]
                rows.append(_row(
                    f"DEV-{serial:03d}", session=session, sound=sound,
                    floor=floor, receiver=receiver, source=source,
                    score_sound=False, score_floor=False,
                ))

    # Three non-floor classes: 12 events each, four per session.
    for sound in ("footstep", "vacuum_cleaner", "normal_or_unknown"):
        for repeat in range(12):
            serial += 1
            rows.append(_row(
                f"DEV-{serial:03d}", session=SESSIONS[repeat % 3],
                sound=sound,
            ))

    # Balance all six sound classes in each session and all floor cases across
    # the sessions without changing the per-class or per-position counts.
    groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for row in rows:
        key = (row["sound_label"], row["floor_label"])
        groups.setdefault(key, []).append(row)
    for group in groups.values():
        rng.shuffle(group)
        for index, row in enumerate(group):
            row["session"] = SESSIONS[index % 3]

    for session in SESSIONS:
        session_rows = [row for row in rows if row["session"] == session]
        rng.shuffle(session_rows)
        rows = [row for row in rows if row["session"] != session] + session_rows
    return rows


def _same_receiver_assignments(
    rows: list[dict[str, Any]], rng: random.Random, overlap_ids: set[str]
) -> None:
    same_rows = [row for row in rows if row["floor_label"] == "same"]
    overlap_same = [row for row in same_rows if row["event_id"] in overlap_ids]
    other_same = [row for row in same_rows if row["event_id"] not in overlap_ids]
    rng.shuffle(overlap_same)
    rng.shuffle(other_same)
    lower_ids = {row["event_id"] for row in overlap_same[:5] + other_same[:5]}
    for row in same_rows:
        receiver = "lower" if row["event_id"] in lower_ids else "upper"
        row["receiver_position"] = receiver
        row["source_story"] = receiver


def make_final_schedule(seed: int = 20261028) -> list[dict[str, Any]]:
    """Make 90 fixed new events: 60 sound decisions and 60 floor decisions."""
    rng = random.Random(seed)
    rows: list[dict[str, Any]] = []
    serial = 0

    # The overlap is 10 events per supported sound, with 10 events in each
    # direction. Rotating the extra repeat keeps direction counts balanced.
    overlap_counts = {
        "dragging_furniture": {"above": 4, "same": 3, "below": 3},
        "hammering": {"above": 3, "same": 4, "below": 3},
        "instant_impact": {"above": 3, "same": 3, "below": 4},
    }
    floor_only_counts = {
        "dragging_furniture": {"above": 3, "same": 3, "below": 4},
        "hammering": {"above": 4, "same": 2, "below": 4},
        "instant_impact": {"above": 3, "same": 5, "below": 2},
    }
    overlap_ids: set[str] = set()

    for sound in FLOOR_SOUND_LABELS:
        for floor in FLOOR_LABELS:
            for is_overlap, count in (
                (True, overlap_counts[sound][floor]),
                (False, floor_only_counts[sound][floor]),
            ):
                for _ in range(count):
                    serial += 1
                    if floor == "above":
                        receiver, source = "lower", "upper"
                    elif floor == "below":
                        receiver, source = "upper", "lower"
                    else:
                        receiver, source = "", ""
                    row = _row(
                        f"FINAL-{serial:03d}", session="FINAL",
                        sound=sound, floor=floor, receiver=receiver,
                        source=source, score_sound=is_overlap,
                        score_floor=True,
                    )
                    rows.append(row)
                    if is_overlap:
                        overlap_ids.add(row["event_id"])

    _same_receiver_assignments(rows, rng, overlap_ids)

    for sound in ("footstep", "vacuum_cleaner", "normal_or_unknown"):
        for _ in range(10):
            serial += 1
            rows.append(_row(
                f"FINAL-{serial:03d}", session="FINAL", sound=sound,
                score_sound=True,
            ))

    lower_mount = [row for row in rows if row["score_floor"] and row["receiver_position"] == "lower"]
    upper_mount = [row for row in rows if row["score_floor"] and row["receiver_position"] == "upper"]
    sound_only = [row for row in rows if not row["score_floor"]]
    rng.shuffle(lower_mount)
    rng.shuffle(upper_mount)
    rng.shuffle(sound_only)
    return lower_mount + upper_mount + sound_only


def write_schedule(rows: list[dict[str, Any]], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS, extrasaction="raise")
        writer.writeheader()
        writer.writerows(rows)


def read_wav_identity(path: Path) -> dict[str, Any]:
    """Validate the device's WAV format and return immutable content hashes."""
    try:
        with wave.open(str(path), "rb") as audio:
            if (audio.getframerate(), audio.getnchannels(), audio.getsampwidth(),
                    audio.getcomptype()) != (16000, 1, 2, "NONE"):
                raise ValueError("16kHz mono PCM16 WAV required")
            sample_count = audio.getnframes()
            pcm = audio.readframes(sample_count)
            if len(pcm) != sample_count * 2:
                raise ValueError("Truncated PCM audio data")
    except (wave.Error, EOFError) as error:
        raise ValueError("Invalid or truncated WAV file") from error
    if sample_count <= 0:
        raise ValueError("WAV must contain at least one sample")
    return {
        "wav_path": str(path.resolve()),
        "file_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "pcm_sha256": hashlib.sha256(pcm).hexdigest(),
        "sample_rate": 16000,
        "sample_count": sample_count,
        "duration_seconds": sample_count / 16000,
    }
