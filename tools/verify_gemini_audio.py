"""Verify a locally stored Gemini key without embedding or logging the secret."""
from __future__ import annotations

import argparse
import base64
import array
import io
import json
import wave
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def call_api(key: str, route: str, payload: dict | None = None) -> tuple[int, dict]:
    request = urllib.request.Request(
        "https://generativelanguage.googleapis.com/v1beta/" + route,
        data=None if payload is None else json.dumps(payload).encode(),
        headers={"x-goog-api-key": key, "Content-Type": "application/json"},
        method="GET" if payload is None else "POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        # Google can echo invalid credentials in error messages. Always redact.
        raw = error.read().decode(errors="replace").replace(key, "[REDACTED]")
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {"error": {"status": "HTTP_ERROR", "message": raw[:300]}}
        return error.code, body
    except urllib.error.URLError:
        return 0, {"error": {"status": "NETWORK_UNAVAILABLE", "message": "Could not connect to the Gemini API."}}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key-file", type=Path, default=ROOT / ".secrets/gemini-api-key")
    parser.add_argument("--audio", type=Path, action="append", default=[])
    parser.add_argument("--model", default="gemini-3.5-flash-lite")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--normalize", action="store_true", help="Peak normalize PCM16 WAV for low-volume sound recognition")
    args = parser.parse_args()
    key = args.key_file.read_text(encoding="utf-8").strip()
    if not key:
        raise SystemExit("Local Gemini key is empty.")
    if args.audio:
        prompt = json.loads((ROOT / "firebase/functions/prompt.json").read_text(encoding="utf-8"))
        schema = {"type": "OBJECT", "properties": {
            "sound": {"type": "STRING"},
            "certainty": {"type": "STRING", "enum": ["clear", "uncertain", "unknown"]},
            "evidence": {"type": "STRING"}, "alternative": {"type": "STRING"}
        }, "required": ["sound", "certainty", "evidence", "alternative"]}
        results = []
        for audio in args.audio:
            size = audio.stat().st_size
            if not 44 < size <= 10 * 1024 * 1024:
                raise SystemExit("Audio must be nonempty and at most 10MB.")
            audio_bytes = audio.read_bytes()
            gain = 1.0
            if args.normalize and audio.suffix.lower() == ".wav":
                with wave.open(io.BytesIO(audio_bytes)) as source:
                    if source.getsampwidth() != 2:
                        raise SystemExit("Normalization requires PCM16 WAV")
                    params = source.getparams()
                    samples = array.array("h", source.readframes(source.getnframes()))
                peak = max((abs(sample) for sample in samples), default=0)
                if peak > 0:
                    gain = min(1000.0, max(1.0, 0.8 * 32767 / peak))
                    samples = array.array("h", (round(sample * gain) for sample in samples))
                buffer = io.BytesIO()
                with wave.open(buffer, "wb") as output:
                    output.setparams(params)
                    output.writeframes(samples.tobytes())
                audio_bytes = buffer.getvalue()
            payload = {"contents": [{"parts": [
                {"inlineData": {"mimeType": "audio/wav" if audio.suffix.lower() == ".wav" else "audio/mp4",
                                "data": base64.b64encode(audio_bytes).decode()}},
                {"text": prompt}
            ]}], "generationConfig": {
                "temperature": 0.1, "maxOutputTokens": 512 if "lite" in args.model else 1024,
                "responseMimeType": "application/json", "responseSchema": schema,
                "thinkingConfig": {"thinkingBudget": 0} if args.model.startswith("gemini-2.5-") else {"thinkingLevel": "MINIMAL" if "lite" in args.model else "LOW"}
            }}
            status, response = call_api(key, f"models/{args.model}:generateContent", payload)
            result = {"audio": str(audio), "model": args.model, "http_status": status}
            result["normalization_gain"] = round(gain, 3)
            if status == 200:
                candidate = response.get("candidates", [{}])[0]
                text = "".join(part.get("text", "") for part in candidate.get("content", {}).get("parts", []) if not part.get("thought"))
                try:
                    result["analysis"] = json.loads(text)
                except json.JSONDecodeError:
                    result["error"] = "Response was not complete JSON"
                result["finish_reason"] = candidate.get("finishReason")
                result["usage"] = response.get("usageMetadata", {})
            else:
                result["error"] = response.get("error", {})
            results.append(result)
            print(json.dumps(result, ensure_ascii=False), flush=True)
            if status in (0, 401, 402, 403, 404, 429):
                break
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        raise SystemExit(0 if all(item.get("analysis") for item in results) else 1)
    status, response = call_api(key, "models")
    if status != 200:
        print(json.dumps({"http_status": status, "error": response.get("error", {})}, ensure_ascii=False))
        raise SystemExit(1)
    models = [item["name"].removeprefix("models/") for item in response.get("models", [])]
    print(json.dumps({"http_status": status, "audio_models_available": [
        model for model in models if "flash-lite" in model and "tts" not in model
    ]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
