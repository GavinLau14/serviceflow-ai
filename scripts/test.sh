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
