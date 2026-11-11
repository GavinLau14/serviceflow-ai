#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/init-local.sh
docker compose --profile stack up -d --build --wait --wait-timeout 240
echo 'ServiceFlow: http://127.0.0.1:18080 (or SERVICEFLOW_WEB_PORT). Model index remains explicit admin action.'
