CREATE TABLE auth_rate_buckets (
    bucket_key VARCHAR(100) PRIMARY KEY,
    requests INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_auth_rate_expiry ON auth_rate_buckets(expires_at);
CREATE INDEX ix_auth_expiry ON auth_sessions(expires_at);
CREATE INDEX ix_receipt_expiry ON mutation_receipts(created_at);
CREATE INDEX ix_challenge_expiry ON apple_challenges(expires_at);
