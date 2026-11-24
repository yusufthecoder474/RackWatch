package com.rackwatch.web.dto;

import com.rackwatch.model.SensorKind;
import java.time.Instant;
import java.util.List;

/** One room with its latest reading per sensor, for GET /api/v1/rooms.
 * Status: GREEN (all latest readings inside limits), RED (any latest
 * reading outside), AMBER (a sensor has no reading yet). */
public record RoomStatusResponse(
        String roomId,
        String roomName,
        String status,
        List<SensorStatus> sensors) {

    public record SensorStatus(
            String sensorId,
            SensorKind kind,
            Double tempC,
            Double rhPct,
            Instant ts,
            boolean inRange) {
    }
}
