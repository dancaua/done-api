#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/preflight.py --env-file .env.production
docker compose --env-file .env.production -f compose.production.yaml config --quiet
# Build before changing database credentials or restarting services.
docker compose --env-file .env.production -f compose.production.yaml build api
# This also upgrades existing volumes, for which initdb scripts do not run again.
docker compose --env-file .env.production -f compose.production.yaml up -d --wait db
docker compose --env-file .env.production -f compose.production.yaml exec -T db sh /opt/done/provision-runtime-role.sh
exec docker compose --env-file .env.production -f compose.production.yaml up -d --no-build
