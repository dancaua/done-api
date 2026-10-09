-- Preserve arbitrary Unicode names in the legacy display columns too.
ALTER TABLE activity_events ALTER COLUMN title TYPE TEXT;
ALTER TABLE activity_events ALTER COLUMN detail TYPE TEXT;
-- Backfill semantic messages without parsing user-supplied text. Historical names
-- are recovered from snapshots/relationships, never from a translated string.
UPDATE activity_events e SET
 title_key=CASE e.kind
  WHEN 'started' THEN 'Timer pornit.'
  WHEN 'finished' THEN CASE a.kind WHEN 'washer' THEN 'Rufele sunt gata!' WHEN 'dryer' THEN 'Rufele sunt uscate!' WHEN 'dishwasher' THEN 'Vasele sunt curate!' ELSE 'Sesiune încheiată!' END
  WHEN 'halfway' THEN CASE WHEN e.title='Mai sunt 5 minute.' THEN 'La jumătate — încă 5 minute.' ELSE 'Suntem la jumătate!' END
  WHEN 'five_minutes' THEN 'Încă 5 minute.' WHEN 'due' THEN 'E timpul să verifici.'
  WHEN 'reminder30' THEN 'Psst…' WHEN 'reminder120' THEN 'Încă te așteaptă…' ELSE 'Oprită' END,
 message_key=CASE e.kind
  WHEN 'started' THEN CASE s.mode WHEN 'stopwatch' THEN '{0} · Cronometru' ELSE '{0} · {1} min' END
  WHEN 'finished' THEN '{0} · Finalizare confirmată de tine.'
  WHEN 'halfway' THEN '{0}: jumătate din durata programului a trecut.'
  WHEN 'five_minutes' THEN '{0}: programul ar trebui să fie gata în curând.'
  WHEN 'due' THEN '{0}: durata estimată a trecut.'
  WHEN 'reminder30' THEN '{0} te așteaptă de 30 de minute.'
  WHEN 'reminder120' THEN '{0}: au trecut două ore de la finalizare.' ELSE '{0}' END,
 message_arguments=CASE WHEN e.kind='started' THEN CASE s.mode WHEN 'stopwatch' THEN json_build_array(s.program_name)::text ELSE json_build_array(s.program_name,s.minutes::text)::text END ELSE json_build_array(a.name)::text END,
 program_snapshot=CASE WHEN e.kind='started' THEN json_build_object('name',s.program_name,'minutes',s.minutes,'localizationKey',s.program_localization_key,'isCustom',s.program_custom)::text ELSE NULL END
FROM appliances a, appliance_sessions s WHERE e.appliance_id=a.id AND e.session_id=s.id AND e.title_key IS NULL;
