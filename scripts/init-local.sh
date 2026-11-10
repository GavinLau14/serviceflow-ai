#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -e .env ]]; then echo '.env already exists; preserved.'; exit 0; fi
umask 077
python3 - <<'PY'
import secrets
from pathlib import Path
Path('.env').write_text(''.join(f'{k}={secrets.token_hex(20)}\n' for k in ['SERVICEFLOW_DB_PASSWORD','SERVICEFLOW_ROOT_PASSWORD','SERVICEFLOW_DEMO_PASSWORD'])+'AI_MODE=disabled\n')
PY
echo 'Generated local-only .env. Demo password is SERVICEFLOW_DEMO_PASSWORD in this file.'
