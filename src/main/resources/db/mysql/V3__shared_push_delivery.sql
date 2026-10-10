USE done_db;

CREATE TABLE push_devices (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 auth_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 token VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 environment VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(auth_session_id) REFERENCES auth_sessions(id) ON DELETE CASCADE,
 UNIQUE(environment,token), FOREIGN KEY(user_id) REFERENCES app_users(id) ON DELETE CASCADE,
 CHECK(environment IN ('sandbox','production'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX ix_push_device_user ON push_devices(user_id);
CREATE TABLE push_deliveries (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 generation CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 due_at TIMESTAMP(6) NOT NULL, next_attempt_at TIMESTAMP(6) NOT NULL,
 lease_until TIMESTAMP(6), sent_at TIMESTAMP(6), attempts INTEGER NOT NULL DEFAULT 0,
 UNIQUE(device_id,session_id,kind,generation),
 FOREIGN KEY(device_id) REFERENCES push_devices(id) ON DELETE CASCADE,
 FOREIGN KEY(session_id) REFERENCES appliance_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX ix_push_due ON push_deliveries(sent_at,next_attempt_at,lease_until);
