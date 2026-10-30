"""Start the KNOK research API on this computer; it is not a public server."""
import argparse
import os
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
RESEARCH = HERE.parent
sys.path[:0] = [str(RESEARCH / "service"), str(RESEARCH / "service/research_runtime")]
from analysis_service import create_server
from jsonl_worker import JsonLineWorker, SoundWorkerClient, FloorWorkerClient
from research_service import ResearchAnalysisPipeline


def build_pipeline(sound, floor):
    return ResearchAnalysisPipeline(sound, floor)


def venv_python(name):
    root = RESEARCH / name
    return root / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--sound-python", type=Path, default=venv_python(".venv-sound"))
    parser.add_argument("--floor-python", type=Path, default=venv_python(".venv-floor"))
    args = parser.parse_args()
    for python in (args.sound_python, args.floor_python):
        if not python.is_file():
            parser.error(f"Python environment not found: {python}; follow research/README.md")
    workers = [JsonLineWorker([str(args.sound_python), "-u", str(HERE / "sound_worker.py")]),
               JsonLineWorker([str(args.floor_python), "-u", str(HERE / "floor_worker.py")])]
    server = None
    try:
        for worker in workers:
            worker.start()
        pipeline = build_pipeline(SoundWorkerClient(workers[0]), FloorWorkerClient(workers[1]))
        server = create_server(("127.0.0.1", args.port), pipeline)
        print(f"KNOK research API: http://127.0.0.1:{server.server_port}/health", flush=True)
        print("Experimental predictions only; real-world floor and accuracy are not established.", flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        if server is not None:
            server.server_close()
        for worker in reversed(workers):
            worker.close()


if __name__ == "__main__":
    main()
