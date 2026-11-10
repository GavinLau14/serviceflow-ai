#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Preserve existing configuration; append only a missing internal AI secret.
umask 077
python3 - <<'PY'
import secrets
from pathlib import Path
p=Path('.env')
if p.exists():
    if 'SERVICEFLOW_AI_SERVICE_TOKEN=' not in p.read_text():
        with p.open('a') as f: f.write('\nSERVICEFLOW_AI_SERVICE_TOKEN='+secrets.token_hex(32)+'\n')
    raise SystemExit(0)
p.write_text(''.join(f'{k}={secrets.token_hex(20)}\n' for k in ['SERVICEFLOW_DB_PASSWORD','SERVICEFLOW_ROOT_PASSWORD','SERVICEFLOW_DEMO_PASSWORD','SERVICEFLOW_AI_SERVICE_TOKEN'])+'SERVICEFLOW_AI_ENABLED=false\n')
PY
echo 'Local .env ready; existing values preserved. Demo password is SERVICEFLOW_DEMO_PASSWORD.'
