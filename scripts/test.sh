#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f .env ]] || ./scripts/init-local.sh
set -a
source .env
set +a
docker compose --profile test up -d --wait mysql-test redis-test
mvn -f backend/pom.xml verify
npm --prefix frontend ci
npm --prefix frontend run build

# M2 contracts use explicit fake providers, no model download or API key.
if [[ -x ai-service/.venv/bin/python ]]; then
  (cd ai-service && .venv/bin/python -m pytest -m "not real" -q)
else
  echo "Install Python3.12 ai-service/requirements.lock to run M2 pytest." >&2
  exit 1
fi
