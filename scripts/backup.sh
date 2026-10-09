#!/usr/bin/env bash
# Store BACKUP_DIR on encrypted storage, outside the repository and Docker volumes.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${BACKUP_DIR:?Set BACKUP_DIR to an encrypted, restricted backup directory}"
umask 077
mkdir -p "$BACKUP_DIR"
backup_file="$BACKUP_DIR/done-$(date -u +%Y%m%dT%H%M%SZ).dump"
trap 'rm -f "$backup_file.partial"' EXIT
docker compose --env-file .env.production -f compose.production.yaml exec -T db pg_dump -U done -d done --format=custom > "$backup_file.partial"
mv "$backup_file.partial" "$backup_file"
# Verify archive readability, never restore automatically over a live database.
docker compose --env-file .env.production -f compose.production.yaml exec -T db pg_restore --list < "$backup_file" > /dev/null
printf 'Backup created and archive checked: %s\n' "$backup_file"
