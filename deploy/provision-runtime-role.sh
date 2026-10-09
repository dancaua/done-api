#!/bin/sh
# Run inside the MySQL container as root after it is healthy, before API startup.
# Passwords travel over stdin/environment, never SQL process arguments or logs.
set -eu
: "${MYSQL_ROOT_PASSWORD:?}"
: "${MYSQL_PASSWORD:?}"
: "${RUNTIME_DATABASE_PASSWORD:?}"
# NO_BACKSLASH_ESCAPES plus doubled quotes safely quotes arbitrary password text.
quote() { printf '%s' "$1" | sed "s/'/''/g"; }
admin_password=$(quote "$MYSQL_PASSWORD")
runtime_password=$(quote "$RUNTIME_DATABASE_PASSWORD")
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --binary-mode --user=root --database=done_db <<SQL
SET SESSION sql_mode='NO_BACKSLASH_ESCAPES,STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION';
CREATE USER IF NOT EXISTS 'done_admin'@'%' IDENTIFIED BY '$admin_password';
ALTER USER 'done_admin'@'%' IDENTIFIED BY '$admin_password';
GRANT ALL PRIVILEGES ON done_db.* TO 'done_admin'@'%' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'done_runtime'@'%' IDENTIFIED BY '$runtime_password';
ALTER USER 'done_runtime'@'%' IDENTIFIED BY '$runtime_password';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'done_runtime'@'%';
SQL
# The prod Flyway callback grants domain-table DML after migrations, excluding its history.
