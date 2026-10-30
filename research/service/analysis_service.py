"""Loopback-only KNOK analysis API. The HTTP layer is a local demo service."""

from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import hashlib
import math
from pathlib import Path
import re
import tempfile
import threading
import uuid

from collection_protocol import FLOOR_LABELS, FLOOR_SOUND_LABELS, SOUND_LABELS, read_wav_identity
from capture_store import CaptureStore


API_VERSION = "knok-local-analysis-v1"
SOUND_THRESHOLD = 0.65
MAX_WAV_BYTES = 2 * 1024 * 1024


class AnalysisPipeline:
    """Combine injected sound and floor predictors without sending truth labels."""

    def __init__(self, sound_model, floor_model):
        self.sound_model = sound_model
        self.floor_model = floor_model
        self._inference_lock = threading.Lock()

    def health(self) -> dict:
        sound_ready = self.sound_model is not None and getattr(self.sound_model, "ready", True)
        floor_ready = self.floor_model is not None and getattr(self.floor_model, "ready", True)
        return {
            "api_version": API_VERSION,
            "ready": bool(sound_ready and floor_ready),
            "sound_ready": bool(sound_ready),
            "floor_ready": bool(floor_ready),
            "sound_model": getattr(self.sound_model, "model_version", "unavailable"),
            "floor_model": getattr(self.floor_model, "model_version", "unavailable"),
            "threshold": SOUND_THRESHOLD,
        }

    def analyze(self, wav_path: Path, receiver_position: str = "", event_id: str = "") -> dict:
        if receiver_position not in ("", "lower", "upper"):
            raise ValueError("receiver_position must be lower, upper, or empty")
        if event_id and (len(event_id) > 128 or re.fullmatch(r"[A-Za-z0-9._:-]+", event_id) is None):
            raise ValueError("event_id contains unsupported characters")
        audio = read_wav_identity(wav_path)
        with self._inference_lock:
            raw_sound = self.sound_model.predict(wav_path)
            sound = self._validate_sound(raw_sound)
            floor = self._floor_result(wav_path, receiver_position, sound, audio)
        stable_event_id = event_id or str(uuid.uuid4())
        analysis_key = json.dumps(
            [stable_event_id, audio["file_sha256"], sound["model_version"], floor["model_version"]],
            separators=(",", ":"),
        )
        return {
            "schema_version": 1,
            "event_id": stable_event_id,
            "analysis_id": hashlib.sha256(analysis_key.encode()).hexdigest(),
            "audio": {
                "file_sha256": audio["file_sha256"],
                "pcm_sha256": audio["pcm_sha256"],
                "sample_rate": audio["sample_rate"],
                "sample_count": audio["sample_count"],
                "duration_seconds": audio["duration_seconds"],
            },
            "sound": sound,
            "floor": floor,
            "ground_truth_used": False,
        }

    @staticmethod
    def _validate_sound(raw: dict) -> dict:
        if not isinstance(raw, dict):
            raise RuntimeError("Sound model returned an invalid result")
        label = raw.get("label")
        score = raw.get("score")
        quality = raw.get("signal_quality", "normal")
        if label not in SOUND_LABELS:
            raise RuntimeError("Sound model returned an unknown label")
        if not isinstance(score, (int, float)) or not math.isfinite(score) or not 0 <= score <= 1:
            raise RuntimeError("Sound model returned an invalid score")
        if quality not in ("normal", "low_signal", "quantization_limited"):
            raise RuntimeError("Sound model returned an invalid signal-quality state")
        output = {
            "label": label,
            "score": float(score),
            "score_is_accuracy": False,
            "signal_quality": quality,
            "model_version": str(raw.get("model_version", "unknown")),
        }
        if "inference_ms" in raw:
            output["inference_ms"] = max(0, int(raw["inference_ms"]))
        window_count = int(raw.get("window_count", 1))
        window_starts = raw.get("window_starts", [])
        window_policy = str(raw.get("window_policy", "unknown"))
        if (window_count < 1 or window_count > 8 or not isinstance(window_starts, list)
                or len(window_starts) > 8 or any(not isinstance(start, int) or start < 0 for start in window_starts)
                or not window_policy or len(window_policy) > 128):
            raise RuntimeError("Sound model returned invalid analysis-window metadata")
        output.update({
            "window_count": window_count,
            "window_starts": window_starts,
            "window_policy": window_policy,
        })
        probabilities = raw.get("probabilities")
        if probabilities is not None:
            if (not isinstance(probabilities, dict)
                    or set(probabilities) != set(SOUND_LABELS)
                    or any(not isinstance(value, (int, float)) or not math.isfinite(value)
                           or value < 0 or value > 1 for value in probabilities.values())
                    or abs(sum(probabilities.values()) - 1) > 1e-4):
                raise RuntimeError("Sound model returned invalid class probabilities")
            output["probabilities"] = {key: float(probabilities[key]) for key in SOUND_LABELS}
        return output

    def _floor_result(self, wav_path: Path, receiver_position: str, sound: dict, audio: dict) -> dict:
        result = {
            "label": "uncertain",
            "receiver_position": receiver_position or None,
            "model_version": getattr(self.floor_model, "model_version", "unknown"),
        }
        if sound["signal_quality"] != "normal":
            result["reason"] = "low_signal_quality"
        elif sound["label"] not in FLOOR_SOUND_LABELS:
            result["reason"] = "unsupported_sound"
        elif sound["score"] < SOUND_THRESHOLD:
            result["reason"] = "sound_score_below_threshold"
        elif audio["duration_seconds"] < 1.0:
            result["reason"] = "audio_too_short"
        elif not receiver_position:
            result["reason"] = "receiver_position_unregistered"
        else:
            try:
                raw = self.floor_model.predict(wav_path, receiver_position)
            except Exception:
                result["reason"] = "floor_model_unavailable"
                return result
            if not isinstance(raw, dict) or raw.get("label") not in FLOOR_LABELS:
                result["reason"] = "invalid_floor_result"
                return result
            result["label"] = raw["label"]
            result["model_version"] = str(raw.get("model_version", result["model_version"]))
            if "inference_ms" in raw:
                result["inference_ms"] = max(0, int(raw["inference_ms"]))
        return result


def create_server(address: tuple[str, int], pipeline: AnalysisPipeline,
                  capture_dir: Path | None = None) -> ThreadingHTTPServer:
    capture_store = CaptureStore(capture_dir) if capture_dir is not None else None

    class Server(ThreadingHTTPServer):
        daemon_threads = True
        block_on_close = False

        def handle_error(self, request, client_address) -> None:
            return

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def _send(self, status: int, payload: dict) -> None:
            encoded = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(encoded)

        def do_GET(self) -> None:
            if self.path != "/health":
                self._send(404, {"error": "not_found"})
                return
            self._send(200 if pipeline.health()["ready"] else 503, pipeline.health())

        def do_POST(self) -> None:
            if self.path != "/v1/analyze":
                self._send(404, {"error": "not_found"})
                return
            if self.headers.get_content_type() not in ("audio/wav", "audio/x-wav"):
                self._send(415, {"error": "audio_wav_required"})
                return
            try:
                length = int(self.headers.get("Content-Length", "-1"))
            except ValueError:
                length = -1
            if length <= 0 or length > MAX_WAV_BYTES:
                self._send(413, {"error": "wav_size_out_of_range"})
                return
            receiver_position = self.headers.get("X-KNOK-Receiver-Position", "")
            event_id = self.headers.get("X-KNOK-Event-ID", "")
            if receiver_position not in ("", "lower", "upper"):
                self._send(400, {"error": "invalid_receiver_position"})
                return
            if event_id and (len(event_id) > 128 or re.fullmatch(r"[A-Za-z0-9._:-]+", event_id) is None):
                self._send(400, {"error": "invalid_event_id"})
                return
            body = self.rfile.read(length)
            if len(body) != length:
                self._send(400, {"error": "truncated_request_body"})
                return
            try:
                with tempfile.NamedTemporaryFile(suffix=".wav") as tmp:
                    tmp.write(body)
                    tmp.flush()
                    temp_path = Path(tmp.name)
                    audio_identity = read_wav_identity(temp_path)
                    stable_event_id = event_id or str(uuid.uuid4())
                    model_input = capture_store.save(temp_path, stable_event_id, audio_identity) \
                        if capture_store is not None else temp_path
                    payload = pipeline.analyze(model_input, receiver_position, stable_event_id)
            except ValueError as error:
                self._send(400, {"error": "invalid_wav", "detail": str(error)[:160]})
                return
            except Exception:
                self._send(503, {"error": "analysis_unavailable"})
                return
            self._send(200, payload)

        def log_message(self, fmt: str, *args) -> None:
            # The validation service is loopback-only; avoid logging request bodies or user paths.
            return

    return Server(address, Handler)
