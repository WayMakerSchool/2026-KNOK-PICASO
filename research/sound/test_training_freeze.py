from pathlib import Path
import tempfile
import unittest
from safeguards import ensure_training_open
HERE=Path(__file__).resolve().parent


class TrainingFreeze(unittest.TestCase):
    def test_first_test_inference_permanently_closes_training_phase(self):
        with tempfile.TemporaryDirectory(dir=HERE) as folder:
            root=Path(folder);ensure_training_open(root);(root/'fresh_started.json').write_text('{}')
            with self.assertRaises(RuntimeError):ensure_training_open(root)


if __name__=='__main__':unittest.main()
