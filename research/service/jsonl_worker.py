"""Synchronous JSON-lines client for one isolated Python model runtime."""

from __future__ import annotations

import json
import select
import subprocess
import threading
import time
from pathlib import Path


class JsonLineWorker:
    def __init__(self, command: list[str], startup_timeout: float = 240.0,
                 request_timeout: float = 45.0):
        self.command = command
        self.startup_timeout = startup_timeout
        self.request_timeout = request_timeout
        self._process: subprocess.Popen[str] | None = None
        self._lock = threading.Lock()
        self.model_version = "unavailable"

    @property
    def ready(self) -> bool:
        return self._process is not None and self._process.poll() is None

    def start(self) -> None:
        if self.ready:
            return
        self._process = subprocess.Popen(
            self.command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=None,
            text=True,
            encoding="utf-8",
            bufsize=1,
        )
        try:
            message = self._read_message(self.startup_timeout)
            if message.get("status") != "ready":
                raise RuntimeError("Model worker did not become ready")
            self.model_version = str(message.get("model_version", "unknown"))
        except Exception:
            self.close()
            raise

    def request(self, payload: dict) -> dict:
        with self._lock:
            if not self.ready:
                raise RuntimeError("Model worker is not running")
            process = self._process
            assert process is not None and process.stdin is not None
            process.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
            process.stdin.flush()
            message = self._read_message(self.request_timeout)
            if "error" in message:
                raise RuntimeError(str(message["error"]))
            return message

    def _read_message(self, timeout: float) -> dict:
        process = self._process
        if process is None or process.stdout is None:
            raise RuntimeError("Model worker has no output stream")
        ready, _, _ = select.select([process.stdout], [], [], timeout)
        if not ready:
            raise TimeoutError("Model worker response timed out")
        line = process.stdout.readline()
        if not line:
            raise RuntimeError("Model worker exited without a response")
        payload = json.loads(line)
        if not isinstance(payload, dict):
            raise RuntimeError("Model worker returned an invalid response")
        return payload

    def close(self) -> None:
        process, self._process = self._process, None
        if process is None:
            return
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        for stream in (process.stdin, process.stdout):
            if stream is not None:
                stream.close()


class SoundWorkerClient:
    def __init__(self, worker: JsonLineWorker):
        self.worker = worker
        self.model_version = worker.model_version

    @property
    def ready(self) -> bool:
        return self.worker.ready

    def predict(self, wav_path: Path) -> dict:
        result = self.worker.request({"wav_path": str(wav_path.resolve())})
        self.model_version = str(result.get("model_version", self.model_version))
        return result


class FloorWorkerClient:
    def __init__(self, worker: JsonLineWorker):
        self.worker = worker
        self.model_version = worker.model_version

    @property
    def ready(self) -> bool:
        return self.worker.ready

    def predict(self, wav_path: Path, receiver_position: str) -> dict:
        result = self.worker.request({
            "wav_path": str(wav_path.resolve()),
            "receiver_position": receiver_position,
        })
        self.model_version = str(result.get("model_version", self.model_version))
        return result
