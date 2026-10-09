-- Existing accounts used Romanian labels. Preserve that preference on upgrade.
ALTER TABLE app_users ADD COLUMN language varchar(2) NOT NULL DEFAULT 'ro';
ALTER TABLE app_users ADD CONSTRAINT app_users_language_supported
  CHECK (language IN ('en', 'ro', 'es', 'it', 'fr', 'de', 'pl', 'hi', 'ja'));
-- New clients send their onboarding choice; clients without a choice default to English.
ALTER TABLE app_users ALTER COLUMN language SET DEFAULT 'en';
