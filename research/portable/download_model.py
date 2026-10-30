"""Download the pinned AST backbone without changing an existing file on failure."""
import argparse
import hashlib
import os
from pathlib import Path
import tempfile
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "sound/base_runtime/ast-model/model.safetensors"
MODEL_SHA256 = "ae0c1e2ad4e1381d851fa9bf298ba13ebc9c5a914cdee2dbe427a6583869924d"
MODEL_URL = ("https://github.com/WayMakerSchool/2026-KNOK-PICASO/releases/download/"
             "v3.2.2-ai-runtime-20261030/knok-ast-audioset-f826b80d.safetensors")


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_verified(url, destination, expected_sha256):
    destination = Path(destination)
    if destination.is_file() and file_sha256(destination) == expected_sha256:
        return "already_verified"
    destination.parent.mkdir(parents=True, exist_ok=True)
    name = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".model-", delete=False) as out:
            name = out.name
            digest = hashlib.sha256()
            with urlopen(url, timeout=90) as source:
                while True:
                    chunk = source.read(1024 * 1024)
                    if not chunk:
                        break
                    out.write(chunk)
                    digest.update(chunk)
            out.flush()
            os.fsync(out.fileno())
        if digest.hexdigest() != expected_sha256:
            raise ValueError("Downloaded model SHA-256 does not match the expected version")
        os.replace(name, destination)
        name = None
        return "downloaded"
    finally:
        if name:
            Path(name).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=MODEL_URL, help="optional URL for an identical verified model")
    args = parser.parse_args()
    print("AST model (~330 MiB):", fetch_verified(args.source, DESTINATION, MODEL_SHA256))


if __name__ == "__main__":
    main()
