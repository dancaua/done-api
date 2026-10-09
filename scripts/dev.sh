#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/init-env.sh
set -a
source .env
set +a
if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
# Existing local MySQL is the default. Opt in to Compose on a separate port.
if [[ "${1:-}" == "--docker" ]]; then
  docker compose up -d --wait db
  export DATABASE_URL="jdbc:mysql://localhost:${DB_PORT:-3307}/done_db"
elif [[ $# -gt 0 ]]; then
  echo 'Usage: scripts/dev.sh [--docker]' >&2; exit 2
fi
exec ./mvnw spring-boot:run
