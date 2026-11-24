-- RackWatch V3 — one unresolved alert per sensor per rule.
-- A PARTIAL UNIQUE INDEX, not application code, is what makes this hold
-- under concurrent ingestion: two transactions that both try to open the
-- same (sensor_id, rule) alert cannot both commit — the second gets a
-- unique-violation (SQLSTATE 23505), which the API maps to HTTP 409
-- (see Part 2) and the ingestion code treats as "alert already open".
--
-- ACKED counts as unresolved here on purpose: acknowledging an alert
-- must not let a second identical alert open beside it. Only CLEARED
-- frees the (sensor_id, rule) pair, because a cleared alert is finished
-- and a later incident is genuinely a new one.

CREATE UNIQUE INDEX ux_alerts_one_open_per_sensor_rule
    ON alerts (sensor_id, rule)
    WHERE state IN ('OPEN', 'ACKED');
