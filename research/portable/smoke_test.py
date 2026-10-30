"""Check the local KNOK API with silent generated audio or a supplied WAV file."""
import argparse
import io
import json
from pathlib import Path
from urllib.request import Request, urlopen
import wave


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wav", nargs="?", type=Path, help="optional 16 kHz mono PCM16 WAV")
    parser.add_argument("--url", default="http://127.0.0.1:8765")
    args = parser.parse_args()
    base = args.url.rstrip("/")
    with urlopen(base + "/health", timeout=10) as response:
        health = json.load(response)
    if not health.get("ready"):
        raise RuntimeError("Models are not ready")
    if args.wav:
        data = args.wav.read_bytes()
    else:
        memory = io.BytesIO()
        with wave.open(memory, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            wav.writeframes(b"\0\0" * 64000)
        data = memory.getvalue()
    request = Request(base + "/v1/analyze", data=data, headers={"Content-Type": "audio/wav"})
    with urlopen(request, timeout=120) as response:
        result = json.load(response)
    if result.get("ground_truth_used") is not False or result["sound"].get("score_is_accuracy") is not False:
        raise RuntimeError("Unexpected analysis response")
    print(json.dumps({"ready": health["ready"], "sound_model": health["sound_model"],
                      "floor_model": health["floor_model"], "sound": result["sound"]["label"],
                      "signal_quality": result["sound"]["signal_quality"],
                      "floor": result["floor"]["label"],
                      "note": "모델 점수는 실측 정확도가 아닙니다."}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
