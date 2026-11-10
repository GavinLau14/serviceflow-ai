#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f .env ]] || ./scripts/init-local.sh
set -a
source .env
set +a
export SERVICEFLOW_DEMO_ENABLED=true
mvn -f backend/pom.xml -DskipTests package
exec java -jar backend/target/serviceflow-api-0.1.0.jar
