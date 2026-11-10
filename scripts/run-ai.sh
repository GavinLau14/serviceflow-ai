#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi
export AI_SERVICE_TOKEN="${AI_SERVICE_TOKEN:-${SERVICEFLOW_AI_SERVICE_TOKEN:-}}"
export AI_EMBED_CACHE="${AI_EMBED_CACHE:-$(pwd)/.cache/fastembed}"
cd ai-service
exec .venv/bin/python -m uvicorn serviceflow_ai.app:app --host 127.0.0.1 --port "${AI_PORT:-18082}"
