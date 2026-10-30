import importlib.util
import io
import json
from pathlib import Path
import threading
import unittest
import urllib.request
import wave


MODULE = Path(__file__).resolve().parents[1] / "run_service.py"
spec = importlib.util.spec_from_file_location("portable_run_service", MODULE)
portable = importlib.util.module_from_spec(spec)
spec.loader.exec_module(portable)


class Model:
    ready = True

    def __init__(self, version, result):
        self.model_version = version
        self.result = result
        self.calls = 0

    def predict(self, *args):
        self.calls += 1
        return dict(self.result)


class ServiceContractTest(unittest.TestCase):
    def test_valid_wav_is_analyzed_without_ground_truth(self):
        sound = Model("sound-test", {"label": "hammering", "score": .9,
                                      "signal_quality": "normal", "model_version": "sound-test",
                                      "window_count": 1, "window_starts": [0], "window_policy": "test"})
        floor = Model("floor-test", {"label": "above", "model_version": "floor-test"})
        server = portable.create_server(("127.0.0.1", 0), portable.build_pipeline(sound, floor))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}"
            with urllib.request.urlopen(url + "/health") as reply:
                self.assertTrue(json.load(reply)["ready"])
            memory = io.BytesIO()
            with wave.open(memory, "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(16000)
                wav.writeframes(b"\x01\x00" * 64000)
            request = urllib.request.Request(url + "/v1/analyze", data=memory.getvalue(),
                headers={"Content-Type": "audio/wav", "X-KNOK-Receiver-Position": "lower"})
            with urllib.request.urlopen(request) as reply:
                result = json.load(reply)
            self.assertEqual(result["sound"]["label"], "hammering")
            self.assertEqual(result["floor"]["label"], "above")
            self.assertFalse(result["ground_truth_used"])
            self.assertFalse(result["sound"]["score_is_accuracy"])
            self.assertEqual(floor.calls, 1)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
