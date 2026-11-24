package com.rackwatch.repository;

import com.rackwatch.model.Reading;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Batch insert for readings. One JDBC batch, one SQL statement:
 * INSERT ... ON CONFLICT (sensor_id, ts) DO NOTHING, so a reading that
 * is already stored (daemon retry, concurrent duplicate) is skipped
 * silently instead of failing the batch. All queries use parameters —
 * no string concatenation of values into SQL anywhere in this project. */
@Component
public class ReadingBatchInserter {

    private static final String INSERT_SQL =
            "INSERT INTO readings (sensor_id, ts, temp_c, rh_pct) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (sensor_id, ts) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    public ReadingBatchInserter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Inserts the batch and returns how many rows were actually stored.
     * PostgreSQL reports 1 per inserted row and 0 per row skipped by
     * ON CONFLICT DO NOTHING; SUCCESS_NO_INFO (-2) is counted as 1,
     * because the driver only reports it for a statement that ran. */
    public int insertBatch(List<Reading> readings) {
        if (readings.isEmpty()) {
            return 0;
        }
        int[] counts = jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Reading reading = readings.get(i);
                ps.setString(1, reading.getSensor().getId());
                ps.setObject(2, reading.getTs().atOffset(ZoneOffset.UTC));
                if (reading.getTempC() == null) {
                    ps.setNull(3, Types.DOUBLE);
                } else {
                    ps.setDouble(3, reading.getTempC());
                }
                if (reading.getRhPct() == null) {
                    ps.setNull(4, Types.DOUBLE);
                } else {
                    ps.setDouble(4, reading.getRhPct());
                }
            }

            @Override
            public int getBatchSize() {
                return readings.size();
            }
        });
        int inserted = 0;
        for (int count : counts) {
            if (count == Statement.SUCCESS_NO_INFO) {
                inserted += 1;
            } else if (count > 0) {
                inserted += count;
            }
        }
        return inserted;
    }
}
