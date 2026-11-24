package com.rackwatch.web.dto;

import com.rackwatch.model.Reading;
import java.time.Instant;

/** One stored reading, for GET /api/v1/readings. Exactly one of tempC /
 * rhPct is non-null, as in the stored row. */
public record ReadingResponse(String sensorId, Instant ts, Double tempC, Double rhPct) {

    public static ReadingResponse from(Reading reading) {
        return new ReadingResponse(
                reading.getSensor().getId(),
                reading.getTs(),
                reading.getTempC(),
                reading.getRhPct());
    }
}
