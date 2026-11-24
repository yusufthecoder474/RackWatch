-- RackWatch V1 — core tables.
-- PostgreSQL 16. All timestamps are TIMESTAMPTZ; the API stores UTC.
-- Validation ranges are enforced twice: in the database (CHECKs below,
-- the last line of defence) and in the API (Bean Validation plus the
-- service-level value-vs-sensor-kind check, Part 2), so a bad row cannot
-- reach the table even if it bypasses the API.

CREATE TABLE rooms (
    id          VARCHAR(32) PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sensors (
    id         VARCHAR(32) PRIMARY KEY,
    room_id    VARCHAR(32) NOT NULL REFERENCES rooms (id),
    kind       VARCHAR(16) NOT NULL CHECK (kind IN ('TEMP', 'HUMIDITY')),
    unit       VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE readings (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sensor_id  VARCHAR(32) NOT NULL REFERENCES sensors (id),
    ts         TIMESTAMPTZ NOT NULL,
    temp_c     DOUBLE PRECISION CHECK (temp_c IS NULL OR temp_c BETWEEN -40 AND 100),
    rh_pct     DOUBLE PRECISION CHECK (rh_pct IS NULL OR rh_pct BETWEEN 0 AND 100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Exactly one value per reading: a TEMP sensor sends temp_c, a
    -- HUMIDITY sensor sends rh_pct, never both and never neither.
    -- Which one must match the sensor's kind is checked in the service
    -- (Part 2); the database cannot know the kind without a join, so
    -- this CHECK enforces the exactly-one shape and the service
    -- enforces the kind match.
    CHECK (num_nonnulls(temp_c, rh_pct) = 1),
    -- One stored row per sensor per instant. This is what makes
    -- ingestion idempotent: a daemon retry that re-sends a batch, or
    -- two concurrent requests carrying the same reading, cannot create
    -- a duplicate row. Inserts use ON CONFLICT (sensor_id, ts)
    -- DO NOTHING (Part 2 repository) and the duplicate is silently
    -- skipped instead of failing the whole batch.
    UNIQUE (sensor_id, ts)
);

-- No separate index on (sensor_id, ts): the UNIQUE constraint above
-- already creates one, and it serves the hot path (latest readings per
-- sensor, 1-hour chart windows) by backward scan for ts DESC.

CREATE TABLE alerts (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sensor_id  VARCHAR(32) NOT NULL REFERENCES sensors (id),
    rule       VARCHAR(32) NOT NULL CHECK (rule IN (
                   'NAIVE_OUT_OF_RANGE',
                   'SMART_THRESHOLD',
                   'RATE_OF_CHANGE',
                   'SENSOR_FAULT'
               )),
    severity   VARCHAR(16) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    state      VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'ACKED', 'CLEARED')),
    message    TEXT NOT NULL,
    opened_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    acked_at   TIMESTAMPTZ,
    acked_by   VARCHAR(100),
    cleared_at TIMESTAMPTZ,
    -- State and timestamps must agree: ACKED implies acked_at is set,
    -- CLEARED implies cleared_at is set, OPEN implies neither is set.
    CHECK (state <> 'ACKED' OR acked_at IS NOT NULL),
    CHECK (state <> 'CLEARED' OR cleared_at IS NOT NULL),
    CHECK (state <> 'OPEN' OR (acked_at IS NULL AND cleared_at IS NULL))
);

CREATE INDEX idx_alerts_state_opened ON alerts (state, opened_at DESC);
CREATE INDEX idx_alerts_sensor ON alerts (sensor_id, opened_at DESC);

CREATE TABLE audit_log (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id   BIGINT REFERENCES alerts (id),
    action     VARCHAR(64) NOT NULL,
    actor      VARCHAR(100),
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_alert ON audit_log (alert_id, created_at DESC);
