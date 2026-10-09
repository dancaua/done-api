#!/bin/sh
# Run inside the MySQL container as root after it is healthy, before API startup.
# Passwords travel over stdin/environment, never SQL process arguments or logs.
set -eu
: "${MYSQL_ROOT_PASSWORD:?}"
: "${MYSQL_PASSWORD:?}"
: "${MYSQL_USER:?}"
: "${RUNTIME_DATABASE_PASSWORD:?}"
# NO_BACKSLASH_ESCAPES plus doubled quotes safely quotes arbitrary password text.
quote() { printf '%s' "$1" | sed "s/'/''/g"; }
admin_password=$(quote "$MYSQL_PASSWORD")
admin_user=$(quote "$MYSQL_USER")
runtime_password=$(quote "$RUNTIME_DATABASE_PASSWORD")
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --binary-mode --user=root --database=done_db <<SQL
SET SESSION sql_mode='NO_BACKSLASH_ESCAPES,STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION';
CREATE USER IF NOT EXISTS '$admin_user'@'%' IDENTIFIED BY '$admin_password';
ALTER USER '$admin_user'@'%' IDENTIFIED BY '$admin_password';
GRANT ALL PRIVILEGES ON done_db.* TO '$admin_user'@'%' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'done_runtime'@'%' IDENTIFIED BY '$runtime_password';
ALTER USER 'done_runtime'@'%' IDENTIFIED BY '$runtime_password';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'done_runtime'@'%';
SQL
# The prod Flyway callback grants domain-table DML after migrations, excluding its history.
