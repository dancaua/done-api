-- MySQL 8.4+ baseline for a new database. The PostgreSQL migration history is archived separately.
-- Database is selected by the JDBC URL; no hardcoded USE that could redirect migrations.
-- Explicit utf8mb4 supports emoji and all nine languages even if the database was created as utf8.
USE done_db;

CREATE TABLE app_users (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    email VARCHAR(254),
    password_hash VARCHAR(100),
    display_name TEXT NOT NULL,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Europe/Bucharest',
    notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_users_email UNIQUE(email),
    CONSTRAINT ck_users_email CHECK(email IS NULL OR BINARY email = BINARY lower(trim(email)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE apple_identities (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE ,
    subject VARCHAR(255) NOT NULL UNIQUE,
    encrypted_refresh_token TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE auth_sessions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL ,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX ix_auth_sessions_user ON auth_sessions(user_id);
CREATE TABLE refresh_tokens (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL ,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6) NULL,
    FOREIGN KEY (session_id) REFERENCES auth_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX ix_refresh_session ON refresh_tokens(session_id);
CREATE TABLE apple_challenges (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    nonce_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE appliances (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL ,
    name TEXT NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK(kind IN ('washer','dryer','dishwasher','oven','hob','custom')),
    created_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE INDEX ix_appliance_user ON appliances(user_id,created_at,id);
CREATE TABLE programs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    appliance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL ,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL ,
    name TEXT NOT NULL,
    minutes INTEGER NOT NULL CHECK(minutes BETWEEN 1 AND 1440),
    suggested BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (appliance_id) REFERENCES appliances(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX ix_program_appliance ON programs(appliance_id,position,id);

CREATE TABLE appliance_sessions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL ,
    appliance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL ,
    program_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin ,
    program_name TEXT NOT NULL,
    minutes INTEGER NOT NULL,
    mode VARCHAR(20) NOT NULL CHECK(mode IN ('countdown','stopwatch')),
    started_at TIMESTAMP(6) NOT NULL,
    expected_end TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    collected_at TIMESTAMP(6) NULL,
    canceled_at TIMESTAMP(6) NULL,
    halfway_sent BOOLEAN NOT NULL DEFAULT FALSE,
    five_sent BOOLEAN NOT NULL DEFAULT FALSE,
    due_sent BOOLEAN NOT NULL DEFAULT FALSE,
    reminder30_sent BOOLEAN NOT NULL DEFAULT FALSE,
    reminder120_sent BOOLEAN NOT NULL DEFAULT FALSE,
    next_event_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_session_mode CHECK((mode='countdown' AND minutes BETWEEN 1 AND 1440 AND expected_end IS NOT NULL AND expected_end > started_at) OR (mode='stopwatch' AND minutes=0 AND expected_end IS NULL)),
    CONSTRAINT ck_session_completion CHECK(completed_at IS NULL OR (completed_at>=started_at AND canceled_at IS NULL)),
    CONSTRAINT ck_session_cancel CHECK(canceled_at IS NULL OR (canceled_at>=started_at AND completed_at IS NULL)),
    CONSTRAINT ck_session_collection CHECK(collected_at IS NULL OR (completed_at IS NOT NULL AND collected_at>=completed_at)),
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    FOREIGN KEY (appliance_id) REFERENCES appliances(id) ON DELETE CASCADE,
    FOREIGN KEY (program_id) REFERENCES programs(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
ALTER TABLE appliance_sessions ADD COLUMN open_slot TINYINT GENERATED ALWAYS AS (IF(collected_at IS NULL AND canceled_at IS NULL,1,NULL)) VIRTUAL;
CREATE UNIQUE INDEX uq_open_session ON appliance_sessions(appliance_id,open_slot);
CREATE INDEX ix_session_history ON appliance_sessions(user_id,appliance_id,started_at DESC,id);
CREATE INDEX ix_session_next_event ON appliance_sessions(next_event_at);
CREATE TABLE activity_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL ,
    appliance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL ,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL ,
    event_key VARCHAR(200) NOT NULL UNIQUE,
    kind VARCHAR(30) NOT NULL,
    title TEXT NOT NULL,
    detail TEXT NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    read_at TIMESTAMP(6) NULL,
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    FOREIGN KEY (appliance_id) REFERENCES appliances(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES appliance_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX ix_activity_user ON activity_events(user_id,occurred_at DESC,id);
CREATE TABLE mutation_receipts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL ,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE(user_id,request_id),
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

ALTER TABLE app_users ADD COLUMN language VARCHAR(2) NOT NULL DEFAULT 'en',
 ADD COLUMN onboarded BOOLEAN NOT NULL DEFAULT TRUE,
 ADD CONSTRAINT app_users_language_supported CHECK(language IN ('en','ro','es','it','fr','de','pl','hi','ja'));
CREATE TABLE households (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, name TEXT NOT NULL,
 localization_key VARCHAR(60), created_at TIMESTAMP(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 name_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(LOWER(name),256))) STORED,
 CONSTRAINT uq_household_owner UNIQUE(id,user_id),
 CONSTRAINT uq_household_name UNIQUE(user_id,name_hash),
 FOREIGN KEY(user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX ix_household_user ON households(user_id,created_at,id);
ALTER TABLE appliances ADD COLUMN household_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ADD COLUMN notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN name_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(LOWER(name),256))) STORED,
 ADD CONSTRAINT uq_appliance_name UNIQUE(household_id,name_hash),
 ADD CONSTRAINT uq_appliance_owner UNIQUE(id,user_id),
 ADD CONSTRAINT fk_appliance_household_owner FOREIGN KEY(household_id,user_id) REFERENCES households(id,user_id);
CREATE INDEX ix_appliance_household ON appliances(user_id,household_id,created_at,id);
ALTER TABLE programs ADD COLUMN localization_key VARCHAR(200), ADD COLUMN is_custom BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN name_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(name,256))) STORED,
 ADD COLUMN localization_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(COALESCE(localization_key,''),256))) STORED,
 ADD CONSTRAINT uq_program_value UNIQUE(appliance_id,name_hash,minutes,is_custom,localization_hash),
 ADD CONSTRAINT uq_program_owner UNIQUE(id,appliance_id,user_id),
 ADD CONSTRAINT fk_program_owner FOREIGN KEY(appliance_id,user_id) REFERENCES appliances(id,user_id) ON DELETE CASCADE;
ALTER TABLE appliance_sessions ADD COLUMN source_program_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 ADD COLUMN program_localization_key VARCHAR(200), ADD COLUMN program_custom BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN measured_program_name TEXT, ADD COLUMN measured_program_minutes INTEGER,
 ADD CONSTRAINT ck_measured_program CHECK((measured_program_name IS NULL AND measured_program_minutes IS NULL) OR (measured_program_name IS NOT NULL AND measured_program_minutes BETWEEN 1 AND 1440 AND mode='stopwatch' AND completed_at IS NOT NULL AND canceled_at IS NULL)),
 ADD CONSTRAINT uq_session_owner UNIQUE(id,appliance_id,user_id),
 ADD CONSTRAINT uq_session_user UNIQUE(id,user_id),
 ADD CONSTRAINT fk_session_owner FOREIGN KEY(appliance_id,user_id) REFERENCES appliances(id,user_id) ON DELETE CASCADE;
ALTER TABLE activity_events ADD COLUMN title_key VARCHAR(200), ADD COLUMN message_key VARCHAR(200),
 ADD COLUMN message_arguments TEXT, ADD COLUMN program_snapshot TEXT,
 ADD CONSTRAINT fk_activity_session_owner FOREIGN KEY(session_id,appliance_id,user_id) REFERENCES appliance_sessions(id,appliance_id,user_id) ON DELETE CASCADE;
ALTER TABLE apple_identities ADD COLUMN client_id VARCHAR(255);
ALTER TABLE auth_sessions ADD COLUMN authentication_method VARCHAR(20) NOT NULL DEFAULT 'password',
 ADD CONSTRAINT ck_authentication_method CHECK(authentication_method IN ('password','apple'));
CREATE TABLE auth_rate_buckets (
 bucket_key VARCHAR(100) PRIMARY KEY, requests INTEGER NOT NULL, expires_at TIMESTAMP(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX ix_auth_rate_expiry ON auth_rate_buckets(expires_at);
CREATE INDEX ix_auth_expiry ON auth_sessions(expires_at);
CREATE INDEX ix_receipt_expiry ON mutation_receipts(created_at);
CREATE INDEX ix_challenge_expiry ON apple_challenges(expires_at);
CREATE TABLE session_shares (
 token VARCHAR(43) PRIMARY KEY, projection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin, session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin UNIQUE, writer_hash VARCHAR(64), snapshot_json TEXT, revision BIGINT NOT NULL DEFAULT 0,
 captured_at TIMESTAMP(6) NULL, created_at TIMESTAMP(6) NOT NULL, published_at TIMESTAMP(6) NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL, revoked_at TIMESTAMP(6) NULL, version BIGINT NOT NULL DEFAULT 0,
 active_slot TINYINT GENERATED ALWAYS AS (IF(revoked_at IS NULL,1,NULL)) VIRTUAL,
 CONSTRAINT uq_active_owned_share UNIQUE(owner_id,session_id,active_slot),
 FOREIGN KEY(owner_id) REFERENCES app_users(id) ON DELETE CASCADE,
 FOREIGN KEY(session_id) REFERENCES appliance_sessions(id) ON DELETE CASCADE,
 FOREIGN KEY(session_id,owner_id) REFERENCES appliance_sessions(id,user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX ix_share_expiry ON session_shares(expires_at);
CREATE INDEX ix_share_owner_session ON session_shares(owner_id,session_id);
CREATE TABLE password_reset_tokens (
 token_hash CHAR(64) PRIMARY KEY, user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES app_users(id) ON DELETE CASCADE, CHECK(expires_at>created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX password_reset_tokens_user ON password_reset_tokens(user_id);
CREATE INDEX password_reset_tokens_expiry ON password_reset_tokens(expires_at);
CREATE TABLE recovery_mail_queue (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, email VARCHAR(254) NOT NULL, kind VARCHAR(12) NOT NULL CHECK(kind IN ('reset','changed')),
 created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL, next_attempt_at TIMESTAMP(6) NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3), lease_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 UNIQUE(email,kind), CHECK(expires_at>created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX recovery_mail_queue_delivery ON recovery_mail_queue(next_attempt_at);
CREATE TABLE application_locks (lock_name VARCHAR(64) PRIMARY KEY)
 ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
INSERT INTO application_locks(lock_name) VALUES ('done-recovery-queue'),('done-share-create');
-- CHECK cannot reference columns with cascading foreign keys in MySQL. Triggers enforce the source invariant.
DELIMITER $$
CREATE TRIGGER share_source_insert BEFORE INSERT ON session_shares FOR EACH ROW
BEGIN
 IF NOT ((NEW.owner_id IS NOT NULL AND NEW.session_id IS NOT NULL AND NEW.writer_hash IS NULL AND NEW.snapshot_json IS NULL)
 OR (NEW.owner_id IS NULL AND NEW.session_id IS NULL AND NEW.writer_hash IS NOT NULL AND NEW.snapshot_json IS NOT NULL AND NEW.command_id IS NOT NULL AND NEW.captured_at IS NOT NULL)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid share source';
 END IF;
END$$
CREATE TRIGGER share_source_update BEFORE UPDATE ON session_shares FOR EACH ROW
BEGIN
 IF NOT ((NEW.owner_id IS NOT NULL AND NEW.session_id IS NOT NULL AND NEW.writer_hash IS NULL AND NEW.snapshot_json IS NULL)
 OR (NEW.owner_id IS NULL AND NEW.session_id IS NULL AND NEW.writer_hash IS NOT NULL AND NEW.snapshot_json IS NOT NULL AND NEW.command_id IS NOT NULL AND NEW.captured_at IS NOT NULL)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid share source';
 END IF;
END$$
-- A composite SET NULL FK would also erase appliance_id/user_id. Triggers enforce ownership,
-- while the simple FK nulls only program_id on deletion, preserving session history.
CREATE TRIGGER session_program_insert BEFORE INSERT ON appliance_sessions FOR EACH ROW
BEGIN
 DECLARE owner_appliance CHAR(36);
 DECLARE owner_user CHAR(36);
 IF NEW.program_id IS NOT NULL THEN
  SELECT appliance_id,user_id INTO owner_appliance,owner_user FROM programs WHERE id=NEW.program_id FOR SHARE;
  IF owner_appliance IS NULL OR owner_appliance<>NEW.appliance_id OR owner_user<>NEW.user_id THEN
   SIGNAL SQLSTATE '23000' SET MESSAGE_TEXT='Program must belong to this appliance and user';
  END IF;
 END IF;
END$$
CREATE TRIGGER session_program_update BEFORE UPDATE ON appliance_sessions FOR EACH ROW
BEGIN
 DECLARE owner_appliance CHAR(36);
 DECLARE owner_user CHAR(36);
 IF NEW.program_id IS NOT NULL THEN
  SELECT appliance_id,user_id INTO owner_appliance,owner_user FROM programs WHERE id=NEW.program_id FOR SHARE;
  IF owner_appliance IS NULL OR owner_appliance<>NEW.appliance_id OR owner_user<>NEW.user_id THEN
   SIGNAL SQLSTATE '23000' SET MESSAGE_TEXT='Program must belong to this appliance and user';
  END IF;
 END IF;
END$$
CREATE TRIGGER program_owner_update BEFORE UPDATE ON programs FOR EACH ROW
BEGIN
 IF OLD.appliance_id<>NEW.appliance_id OR OLD.user_id<>NEW.user_id THEN
  SIGNAL SQLSTATE '23000' SET MESSAGE_TEXT='Program ownership is immutable';
 END IF;
END$$
DELIMITER ;
