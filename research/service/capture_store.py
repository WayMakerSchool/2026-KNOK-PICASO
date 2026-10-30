"""Local content-addressed store for unlabeled study WAVs received by the API."""

from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import threading


class CaptureStore:
    """Persist WAV bytes and hashes, without attaching ground-truth labels."""

    def __init__(self, directory: Path):
        self.directory = directory.expanduser().resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.manifest_path = self.directory / "captures.jsonl"
        self._lock = threading.Lock()
        self._recorded_keys = self._read_keys()

    def save(self, source: Path, event_id: str, audio_identity: dict) -> Path:
        if re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", event_id) is None:
            raise ValueError("Study capture requires a valid persisted event ID")
        digest = audio_identity["file_sha256"]
        if re.fullmatch(r"[0-9a-f]{64}", digest) is None:
            raise ValueError("Study capture requires a valid WAV hash")
        safe_id = re.sub(r"[^A-Za-z0-9._-]", "_", event_id)
        destination = self.directory / f"{safe_id}_{digest[:16]}.wav"
        key = (event_id, digest)

        with self._lock:
            if destination.exists():
                if self._sha256(destination) != digest:
                    raise RuntimeError("Stored study WAV does not match its content-addressed name")
            else:
                temp_path = None
                try:
                    with tempfile.NamedTemporaryFile(prefix=".capture-", suffix=".tmp",
                                                     dir=self.directory, delete=False) as temp:
                        temp_path = Path(temp.name)
                        with source.open("rb") as input_file:
                            shutil.copyfileobj(input_file, temp)
                        temp.flush()
                        os.fsync(temp.fileno())
                    os.replace(temp_path, destination)
                finally:
                    if temp_path is not None:
                        temp_path.unlink(missing_ok=True)

            if key not in self._recorded_keys:
                entry = {
                    "capture_id": hashlib.sha256(f"{event_id}\0{digest}".encode()).hexdigest(),
                    "received_at_utc": datetime.now(timezone.utc).isoformat(),
                    "device_event_id": event_id,
                    "wav_file": destination.name,
                    "file_sha256": digest,
                    "pcm_sha256": audio_identity["pcm_sha256"],
                    "sample_rate": audio_identity["sample_rate"],
                    "sample_count": audio_identity["sample_count"],
                    "duration_seconds": audio_identity["duration_seconds"],
                }
                with self.manifest_path.open("a", encoding="utf-8") as handle:
                    handle.write(json.dumps(entry, ensure_ascii=False, allow_nan=False) + "\n")
                    handle.flush()
                    os.fsync(handle.fileno())
                self._recorded_keys.add(key)
        return destination

    def _read_keys(self) -> set[tuple[str, str]]:
        if not self.manifest_path.exists():
            return set()
        keys: set[tuple[str, str]] = set()
        for line_number, line in enumerate(self.manifest_path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
                keys.add((entry["device_event_id"], entry["file_sha256"]))
            except (ValueError, KeyError, TypeError) as error:
                raise RuntimeError(f"Capture manifest is invalid on line {line_number}") from error
        return keys

    @staticmethod
    def _sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
