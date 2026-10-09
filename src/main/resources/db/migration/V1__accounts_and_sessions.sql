CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    email VARCHAR(254),
    password_hash VARCHAR(100),
    display_name VARCHAR(80) NOT NULL,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Europe/Bucharest',
    notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_users_email UNIQUE(email),
    CONSTRAINT ck_users_email CHECK(email IS NULL OR email = lower(trim(email)))
);
CREATE TABLE apple_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES app_users(id) ON DELETE CASCADE,
    subject VARCHAR(255) NOT NULL UNIQUE,
    encrypted_refresh_token TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP
);
CREATE INDEX ix_auth_sessions_user ON auth_sessions(user_id);
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP
);
CREATE INDEX ix_refresh_session ON refresh_tokens(session_id);
CREATE TABLE apple_challenges (
    id UUID PRIMARY KEY,
    nonce_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP
);
CREATE TABLE appliances (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK(kind IN ('washer','dryer','dishwasher','oven','hob')),
    created_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_appliance_name ON appliances(user_id, lower(name));
CREATE INDEX ix_appliance_user ON appliances(user_id,created_at,id);
CREATE TABLE programs (
    id UUID PRIMARY KEY,
    appliance_id UUID NOT NULL REFERENCES appliances(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    minutes INTEGER NOT NULL CHECK(minutes BETWEEN 1 AND 1440),
    suggested BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_program_appliance ON programs(appliance_id,position,id);
CREATE UNIQUE INDEX uq_program_name ON programs(appliance_id,lower(name));
CREATE TABLE appliance_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    appliance_id UUID NOT NULL REFERENCES appliances(id) ON DELETE CASCADE,
    program_id UUID REFERENCES programs(id) ON DELETE SET NULL,
    program_name VARCHAR(60) NOT NULL,
    minutes INTEGER NOT NULL,
    mode VARCHAR(20) NOT NULL CHECK(mode IN ('countdown','stopwatch')),
    started_at TIMESTAMP NOT NULL,
    expected_end TIMESTAMP,
    completed_at TIMESTAMP,
    collected_at TIMESTAMP,
    canceled_at TIMESTAMP,
    halfway_sent BOOLEAN NOT NULL DEFAULT FALSE,
    five_sent BOOLEAN NOT NULL DEFAULT FALSE,
    due_sent BOOLEAN NOT NULL DEFAULT FALSE,
    reminder30_sent BOOLEAN NOT NULL DEFAULT FALSE,
    reminder120_sent BOOLEAN NOT NULL DEFAULT FALSE,
    next_event_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_session_mode CHECK((mode='countdown' AND minutes BETWEEN 1 AND 1440 AND expected_end IS NOT NULL AND expected_end > started_at) OR (mode='stopwatch' AND minutes=0 AND expected_end IS NULL)),
    CONSTRAINT ck_session_completion CHECK(completed_at IS NULL OR (completed_at>=started_at AND canceled_at IS NULL)),
    CONSTRAINT ck_session_cancel CHECK(canceled_at IS NULL OR (canceled_at>=started_at AND completed_at IS NULL)),
    CONSTRAINT ck_session_collection CHECK(collected_at IS NULL OR (completed_at IS NOT NULL AND collected_at>=completed_at))
);
CREATE UNIQUE INDEX uq_open_session ON appliance_sessions(appliance_id) WHERE collected_at IS NULL AND canceled_at IS NULL;
CREATE INDEX ix_session_history ON appliance_sessions(user_id,appliance_id,started_at DESC,id);
CREATE INDEX ix_session_next_event ON appliance_sessions(next_event_at) WHERE next_event_at IS NOT NULL;
CREATE TABLE activity_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    appliance_id UUID NOT NULL REFERENCES appliances(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES appliance_sessions(id) ON DELETE CASCADE,
    event_key VARCHAR(200) NOT NULL UNIQUE,
    kind VARCHAR(30) NOT NULL,
    title VARCHAR(150) NOT NULL,
    detail VARCHAR(300) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP
);
CREATE INDEX ix_activity_user ON activity_events(user_id,occurred_at DESC,id);
CREATE TABLE mutation_receipts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE(user_id,request_id)
);
