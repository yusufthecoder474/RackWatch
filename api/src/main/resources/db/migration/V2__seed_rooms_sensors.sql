-- RackWatch V2 — seed data: 2 rooms x 2 sensors, matching sensors.conf.
-- ON CONFLICT DO NOTHING keeps the migration idempotent if it is ever
-- re-run against a database that already has these rows.

INSERT INTO rooms (id, name, description) VALUES
    ('ROOM-A', 'Server Room A', 'Primary server room'),
    ('ROOM-B', 'Server Room B', 'Secondary server room')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sensors (id, room_id, kind, unit) VALUES
    ('ROOM-A-TEMP-01', 'ROOM-A', 'TEMP', 'C'),
    ('ROOM-A-RH-01',   'ROOM-A', 'HUMIDITY', '%RH'),
    ('ROOM-B-TEMP-01', 'ROOM-B', 'TEMP', 'C'),
    ('ROOM-B-RH-01',   'ROOM-B', 'HUMIDITY', '%RH')
ON CONFLICT (id) DO NOTHING;
