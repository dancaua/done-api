CREATE TABLE households (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 60),
    localization_key VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_household_owner UNIQUE(id,user_id)
);
CREATE UNIQUE INDEX uq_household_name ON households(user_id,lower(name));
CREATE INDEX ix_household_user ON households(user_id,created_at,id);
-- A deterministic default home preserves existing appliances and session IDs.
INSERT INTO households(id,user_id,name,localization_key,created_at)
SELECT id,id,'My home','household.default',created_at FROM app_users;
ALTER TABLE appliances ADD COLUMN household_id UUID;
UPDATE appliances SET household_id=user_id;
ALTER TABLE appliances ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE appliances ADD CONSTRAINT fk_appliance_household_owner
    FOREIGN KEY(household_id,user_id) REFERENCES households(id,user_id);
DROP INDEX uq_appliance_name;
CREATE UNIQUE INDEX uq_appliance_name ON appliances(household_id,lower(name));
CREATE INDEX ix_appliance_household ON appliances(user_id,household_id,created_at,id);
