"""Start the loopback API and the two model workers with bounded CPU use."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

from analysis_service import AnalysisPipeline, create_server
from jsonl_worker import FloorWorkerClient, JsonLineWorker, SoundWorkerClient


DEFAULT_AST_RUNTIME = Path(
    "/Users/yesung/Documents/Codex/Projects/KNOK_V2_20261023/improvement/"
    "silent_root_cause_20261025/ast-runtime/bin/python"
)
DEFAULT_AST_MODEL = Path(
    "/Users/yesung/Documents/Codex/Projects/KNOK_V2_20261023/improvement/"
    "silent_root_cause_20261025"
)
DEFAULT_FLOOR_RUNTIME = Path(
    "/Users/yesung/Documents/Codex/Projects/KNOK_floor_classifier_20261015/.venv/bin/python"
)
DEFAULT_FLOOR_MODEL = Path("/Users/yesung/Documents/Codex/Projects/KNOK_floor_classifier_20261015")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ast-python", type=Path, default=DEFAULT_AST_RUNTIME)
    parser.add_argument("--ast-model", type=Path, default=DEFAULT_AST_MODEL)
    parser.add_argument("--floor-python", type=Path, default=DEFAULT_FLOOR_RUNTIME)
    parser.add_argument("--floor-model", type=Path, default=DEFAULT_FLOOR_MODEL)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--capture-dir", type=Path,
                        help="optional local folder for unlabeled received WAVs and content hashes")
    args = parser.parse_args()
    for runtime in (args.ast_python, args.floor_python):
        if not runtime.is_file():
            parser.error(f"Python runtime not found: {runtime}")
    for root in (args.ast_model, args.floor_model):
        if not root.is_dir():
            parser.error(f"Model directory not found: {root}")

    sound_worker = JsonLineWorker([
        str(args.ast_python), "-u", str(Path(__file__).with_name("ast_worker.py")), str(args.ast_model)
    ])
    floor_worker = JsonLineWorker([
        str(args.floor_python), "-u", str(Path(__file__).with_name("floor_worker.py")), str(args.floor_model)
    ])
    server = None
    try:
        print("Loading the frozen sound model with MPS when available (CPU threads capped at 2).", flush=True)
        sound_worker.start()
        print(f"Sound model ready: {sound_worker.model_version}", flush=True)
        floor_worker.start()
        print(f"Floor model ready: {floor_worker.model_version}", flush=True)
        pipeline = AnalysisPipeline(SoundWorkerClient(sound_worker), FloorWorkerClient(floor_worker))
        server = create_server(("127.0.0.1", args.port), pipeline, capture_dir=args.capture_dir)
        print(f"Local analysis API ready at http://127.0.0.1:{args.port}", flush=True)
        print(f"Android emulator connects to this Mac service at http://10.0.2.2:{args.port}.", flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        return 0
    finally:
        if server is not None:
            server.shutdown()
            server.server_close()
        floor_worker.close()
        sound_worker.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
