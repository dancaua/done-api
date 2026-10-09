-- Appliance preferences are independent of the account-wide notification switch.
-- Existing explicit account preferences are preserved.
ALTER TABLE appliances ADD COLUMN notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE;
