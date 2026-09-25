#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
python_bin=${PADNOTE_PYTHON:-python3}
exec "$python_bin" "$script_dir/start.py" "$@"

