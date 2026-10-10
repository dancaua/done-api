USE done_db;

CREATE TABLE household_members (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 household_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 joined_at TIMESTAMP(6) NOT NULL,
 UNIQUE(household_id,user_id),
 FOREIGN KEY(household_id) REFERENCES households(id) ON DELETE CASCADE,
 FOREIGN KEY(user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX ix_member_user ON household_members(user_id,household_id);
CREATE TABLE household_invitations (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 household_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 redeemed_at TIMESTAMP(6), revoked_at TIMESTAMP(6),
 redeemed_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 FOREIGN KEY(household_id) REFERENCES households(id) ON DELETE CASCADE,
 FOREIGN KEY(redeemed_by) REFERENCES app_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE INDEX ix_invite_household ON household_invitations(household_id,created_at);
CREATE TABLE activity_reads (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 read_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(event_id,user_id),
 FOREIGN KEY(event_id) REFERENCES activity_events(id) ON DELETE CASCADE,
 FOREIGN KEY(user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

-- Keep authorization context for safe retries after an appliance was deleted.
ALTER TABLE mutation_receipts ADD COLUMN household_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
