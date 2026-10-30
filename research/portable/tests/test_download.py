import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest


MODULE = Path(__file__).resolve().parents[1] / "download_model.py"
spec = importlib.util.spec_from_file_location("download_model", MODULE)
download = importlib.util.module_from_spec(spec)
spec.loader.exec_module(download)


class DownloadVerificationTest(unittest.TestCase):
    def test_verified_download_and_reuse(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "source"
            destination = root / "weights" / "model.safetensors"
            source.write_bytes(b"known model bytes")
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            self.assertEqual(download.fetch_verified(source.as_uri(), destination, digest), "downloaded")
            self.assertEqual(destination.read_bytes(), source.read_bytes())
            source.unlink()
            self.assertEqual(download.fetch_verified(source.as_uri(), destination, digest), "already_verified")

    def test_wrong_download_does_not_replace_existing_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, destination = root / "source", root / "model"
            source.write_bytes(b"corrupt")
            destination.write_bytes(b"existing")
            digest = hashlib.sha256(b"expected").hexdigest()
            with self.assertRaises(ValueError):
                download.fetch_verified(source.as_uri(), destination, digest)
            self.assertEqual(destination.read_bytes(), b"existing")


if __name__ == "__main__":
    unittest.main()
