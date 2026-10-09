#!/usr/bin/env bash
# Store BACKUP_DIR on encrypted storage outside the repository and Docker volumes.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${BACKUP_DIR:?Set BACKUP_DIR to an encrypted, restricted backup directory}"
umask 077
mkdir -p "$BACKUP_DIR"
backup_file="$BACKUP_DIR/done-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
trap 'rm -f "$backup_file.partial"' EXIT
docker compose --env-file .env.production -f compose.production.yaml exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --user="$MYSQL_USER" --single-transaction --no-tablespaces --set-gtid-purged=OFF --triggers --routines "$MYSQL_DATABASE"' | gzip > "$backup_file.partial"
gzip -t "$backup_file.partial"
mv "$backup_file.partial" "$backup_file"
printf 'Backup created and compression checked: %s\n' "$backup_file"
# Test restoration separately against an isolated MySQL database; never overwrite live data.
