#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/preflight.py --env-file .env.production
docker compose --env-file .env.production -f compose.production.yaml config --quiet
exec docker compose --env-file .env.production -f compose.production.yaml up -d --build
