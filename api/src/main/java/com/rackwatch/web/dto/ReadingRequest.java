package com.rackwatch.web.dto;

import com.rackwatch.validation.ClockSkew;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** One reading in a POST /api/v1/readings batch. JSON uses snake_case
 * (temp_c, rh_pct, sensor_id) via the global SNAKE_CASE naming strategy
 * in application.yml, matching what the daemon sends.
 *
 * Timestamps may be up to 60 s in the future (@ClockSkew): the daemon
 * stamps readings at sampling time on its own clock, and the API host's
 * clock is never exactly in step with it. A reading stamped a few
 * seconds "ahead" is clock skew, not bad data. Anything more than 60 s
 * ahead is still rejected. */
public record ReadingRequest(
        @NotBlank String sensorId,
        @NotNull @ClockSkew Instant ts,
        @DecimalMin("-40.0") @DecimalMax("100.0") Double tempC,
        @DecimalMin("0.0") @DecimalMax("100.0") Double rhPct) {

    /** Exactly one value per reading. Which one must match the sensor
     * kind — that needs a database lookup, so the service checks it
     * (Part 2, ReadingIngestionService). */
    @AssertTrue(message = "Exactly one of temp_c and rh_pct must be present")
    public boolean isExactlyOneValue() {
        return (tempC == null) != (rhPct == null);
    }
}
