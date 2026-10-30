#!/bin/sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RESEARCH=$(dirname -- "$HERE")
PYTHON=${PYTHON:-python3.12}
"$PYTHON" -c 'import sys; assert sys.version_info[:2] == (3, 12), "Python 3.12 is required"'
"$PYTHON" -m venv "$RESEARCH/.venv-sound"
"$PYTHON" -m venv "$RESEARCH/.venv-floor"
"$RESEARCH/.venv-sound/bin/python" -m pip install -r "$HERE/requirements-sound.txt"
"$RESEARCH/.venv-floor/bin/python" -m pip install -r "$HERE/requirements-floor.txt"
if [ -n "${KNOK_MODEL_SOURCE:-}" ]; then
  "$RESEARCH/.venv-sound/bin/python" "$HERE/download_model.py" --source "$KNOK_MODEL_SOURCE"
else
  "$RESEARCH/.venv-sound/bin/python" "$HERE/download_model.py"
fi
echo 'Ready. Start: research/.venv-sound/bin/python research/portable/run_service.py'
