#!/bin/sh
# Idempotent on both fresh and existing production databases. No secret arguments/logging.
set -eu
: "${POSTGRES_USER:?}"
: "${POSTGRES_DB:?}"
: "${RUNTIME_DATABASE_PASSWORD:?}"
psql --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 <<'SQL'
\getenv runtime_password RUNTIME_DATABASE_PASSWORD
BEGIN;
SELECT 'CREATE ROLE done_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='done_runtime') \gexec
SELECT format('ALTER ROLE done_runtime WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD %L', :'runtime_password') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO done_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO done_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO done_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO done_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO done_runtime;
DO $$ BEGIN
  IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
    REVOKE ALL ON public.flyway_schema_history FROM done_runtime;
  END IF;
END $$;
COMMIT;
SQL
