ALTER TABLE apple_identities ADD COLUMN client_id VARCHAR(255);
ALTER TABLE auth_sessions ADD COLUMN authentication_method VARCHAR(20) NOT NULL DEFAULT 'password' CHECK(authentication_method IN ('password','apple'));
UPDATE auth_sessions s SET authentication_method='apple' FROM app_users u WHERE s.user_id=u.id AND u.password_hash IS NULL;
ALTER TABLE appliance_sessions ADD CONSTRAINT uq_session_user UNIQUE(id,user_id);
CREATE TABLE session_shares (
  token VARCHAR(43) PRIMARY KEY,
  projection_id UUID NOT NULL,
  owner_id UUID REFERENCES app_users(id) ON DELETE CASCADE,
  session_id UUID REFERENCES appliance_sessions(id) ON DELETE CASCADE,
  command_id UUID UNIQUE,
  writer_hash VARCHAR(64),
  snapshot_json TEXT,
  revision BIGINT NOT NULL DEFAULT 0,
  captured_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  FOREIGN KEY(session_id,owner_id) REFERENCES appliance_sessions(id,user_id) ON DELETE CASCADE,
  CONSTRAINT ck_share_source CHECK((owner_id IS NOT NULL AND session_id IS NOT NULL AND writer_hash IS NULL AND snapshot_json IS NULL) OR (owner_id IS NULL AND session_id IS NULL AND writer_hash IS NOT NULL AND snapshot_json IS NOT NULL AND command_id IS NOT NULL AND captured_at IS NOT NULL))
);
CREATE INDEX ix_share_expiry ON session_shares(expires_at);
CREATE INDEX ix_share_owner_session ON session_shares(owner_id,session_id);
CREATE UNIQUE INDEX uq_active_owned_share ON session_shares(owner_id,session_id) WHERE revoked_at IS NULL AND owner_id IS NOT NULL;
