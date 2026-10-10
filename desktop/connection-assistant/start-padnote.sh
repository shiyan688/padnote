#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
python_bin=${PADNOTE_PYTHON:-python3}
if ! command -v "$python_bin" >/dev/null 2>&1; then
    printf '没有找到 Python 3。请在 WSL/macOS/Linux 安装 Python 3.10 或更新版本后重试。\n' >&2
    exit 127
fi
if ! "$python_bin" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)'; then
    printf '当前 Python 版本过旧；PadNote 助手需要 Python 3.10 或更新版本。\n' >&2
    exit 2
fi
if ! command -v openssl >/dev/null 2>&1; then
    printf '没有找到 OpenSSL。首次启动需要它为同 Wi-Fi 配对生成本机 TLS 证书。\n' >&2
    exit 127
fi
exec "$python_bin" "$script_dir/start.py" --lan --no-browser "$@"
