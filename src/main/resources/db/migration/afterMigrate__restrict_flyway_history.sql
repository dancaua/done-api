-- The runtime principal may modify domain rows, never the migration history.
-- Works with the separate migration principal; local development need not create this role.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='done_runtime') THEN
    REVOKE ALL ON public.flyway_schema_history FROM done_runtime;
  END IF;
END $$;
