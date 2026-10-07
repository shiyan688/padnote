#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
cd "$script_dir"
python_bin=${PADNOTE_PYTHON:-python3}
exec "$python_bin" -m unittest discover -s tests -v
