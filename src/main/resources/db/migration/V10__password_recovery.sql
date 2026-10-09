-- New migration only. All existing TIMESTAMP migrations remain unchanged.
CREATE TABLE password_reset_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX password_reset_tokens_user ON password_reset_tokens(user_id);
CREATE INDEX password_reset_tokens_expiry ON password_reset_tokens(expires_at);
-- Durable delivery queue contains no passwords, bearer tokens or reset tokens.
-- Unknown addresses take exactly the same enqueue path and are discarded by the worker.
CREATE TABLE recovery_mail_queue (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('reset','changed')),
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    lease_id UUID,
    UNIQUE (email,kind),
    CHECK (expires_at > created_at)
);
CREATE INDEX recovery_mail_queue_delivery ON recovery_mail_queue(next_attempt_at);
