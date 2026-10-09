#!/usr/bin/env bash
set -euo pipefail

case "${1:-migrate}" in
  -h|--help)
    cat <<'HELP'
Usage: ./scripts/migrate.sh [migrate|info|validate]

Apply all pending MySQL migrations, in Flyway version order, without
starting the API. Re-running skips migrations already recorded by Flyway.

Configuration: DATABASE_URL (JDBC), DATABASE_USER, DATABASE_PASSWORD.
Values exported by the caller take precedence over the project's .env.
Defaults: jdbc:mysql://localhost:3306/done_db, user done_admin, database selected by JDBC URL.
Requires Java 21; Maven is provided by ./mvnw.

Create the database first with scripts/create-database.sql, or use Compose's
db service, which already creates the done_db database and account.
HELP
    exit 0 ;;
  migrate|info|validate) done_migration_action="${1:-migrate}" ;;
  *) echo 'Expected migrate, info or validate. Use --help for details.' >&2; exit 2 ;;
esac
if [[ $# -gt 1 ]]; then
  echo 'Pass only one action. Configure the database through environment variables.' >&2
  exit 2
fi

cd "$(dirname "$0")/.."

# Read only the required values from the local environment file, without
# overwriting explicit caller configuration (for example a test database).
if [[ -f .env ]]; then
  if [[ -z ${DATABASE_URL+x} ]]; then
    DATABASE_URL="$(source .env; printf '%s' "${DATABASE_URL:-}")"
  fi
  if [[ -z ${DATABASE_USER+x} ]]; then
    DATABASE_USER="$(source .env; printf '%s' "${DATABASE_USER:-}")"
  fi
  if [[ -z ${DATABASE_PASSWORD+x} ]]; then
    DATABASE_PASSWORD="$(source .env; printf '%s' "${DATABASE_PASSWORD:-}")"
  fi
fi
export DATABASE_URL="${DATABASE_URL:-jdbc:mysql://localhost:3306/done_db}"
export DATABASE_USER="${DATABASE_USER:-done_admin}"
export DATABASE_PASSWORD="${DATABASE_PASSWORD:-}"

if [[ $DATABASE_URL != jdbc:mysql://* ]]; then
  echo 'DATABASE_URL must be a MySQL JDBC URL, e.g. jdbc:mysql://localhost:3306/done_db.' >&2
  exit 2
fi
if [[ -z $DATABASE_PASSWORD ]]; then
  echo 'Set DATABASE_PASSWORD in your environment or .env before running migrations.' >&2
  exit 2
fi
if [[ -z ${JAVA_HOME:-} && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi

# No API, jobs or authentication configuration is loaded. Credentials remain
# in the environment rather than command-line arguments or script output.
exec ./mvnw --batch-mode --no-transfer-progress "flyway:$done_migration_action"
