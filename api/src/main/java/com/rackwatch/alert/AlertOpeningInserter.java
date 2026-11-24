package com.rackwatch.alert;

import com.rackwatch.model.AlertRule;
import com.rackwatch.model.AlertSeverity;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Opens alerts with a native INSERT ... ON CONFLICT DO NOTHING.
 * The ON CONFLICT target names the partial unique index exactly
 * (columns plus its WHERE predicate), so a second open for the same
 * sensor and rule is silently skipped instead of raising.
 *
 * Why native, not JPA save() plus catching the violation: in
 * PostgreSQL a unique-violation error aborts the whole transaction,
 * and JPA marks it rollback-only — catching it would roll back the
 * readings this request just stored. ON CONFLICT DO NOTHING never
 * raises, so the ingestion transaction survives a race. */
@Component
public class AlertOpeningInserter {

    private static final String INSERT_SQL =
            "INSERT INTO alerts (sensor_id, rule, severity, state, message, opened_at) "
                    + "VALUES (?, ?, ?, 'OPEN', ?, ?) "
                    + "ON CONFLICT (sensor_id, rule) WHERE state IN ('OPEN', 'ACKED') DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    public AlertOpeningInserter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Returns true if a new alert row was opened, false if one was
     * already unresolved for this sensor and rule. */
    public boolean insertIfAbsent(
            String sensorId, AlertRule rule, AlertSeverity severity,
            String message, Instant openedAt) {
        int inserted = jdbcTemplate.update(INSERT_SQL,
                sensorId, rule.name(), severity.name(), message, Timestamp.from(openedAt));
        return inserted == 1;
    }
}
