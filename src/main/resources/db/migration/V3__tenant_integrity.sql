ALTER TABLE appliances ADD CONSTRAINT uq_appliance_owner UNIQUE(id,user_id);
ALTER TABLE programs ADD CONSTRAINT uq_program_owner UNIQUE(id,appliance_id,user_id);
ALTER TABLE appliance_sessions ADD CONSTRAINT uq_session_owner UNIQUE(id,appliance_id,user_id);
ALTER TABLE programs ADD CONSTRAINT fk_program_owner FOREIGN KEY(appliance_id,user_id) REFERENCES appliances(id,user_id) ON DELETE CASCADE;
ALTER TABLE appliance_sessions ADD CONSTRAINT fk_session_owner FOREIGN KEY(appliance_id,user_id) REFERENCES appliances(id,user_id) ON DELETE CASCADE;
-- The simple program FK sets program_id to NULL on delete. This NO ACTION
-- constraint then sees a NULL program_id and keeps the historical snapshot.
ALTER TABLE appliance_sessions ADD CONSTRAINT fk_session_program_owner FOREIGN KEY(program_id,appliance_id,user_id) REFERENCES programs(id,appliance_id,user_id);
ALTER TABLE activity_events ADD CONSTRAINT fk_activity_session_owner FOREIGN KEY(session_id,appliance_id,user_id) REFERENCES appliance_sessions(id,appliance_id,user_id) ON DELETE CASCADE;
