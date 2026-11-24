package com.rackwatch.web.dto;

import com.rackwatch.model.Alert;
import com.rackwatch.model.AlertRule;
import com.rackwatch.model.AlertSeverity;
import com.rackwatch.model.AlertState;
import java.time.Instant;

/** One alert, for GET /api/v1/alerts and POST /api/v1/alerts/{id}/ack. */
public record AlertResponse(
        long id,
        String sensorId,
        AlertRule rule,
        AlertSeverity severity,
        AlertState state,
        String message,
        Instant openedAt,
        Instant ackedAt,
        String ackedBy,
        Instant clearedAt) {

    public static AlertResponse from(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getSensor().getId(),
                alert.getRule(),
                alert.getSeverity(),
                alert.getState(),
                alert.getMessage(),
                alert.getOpenedAt(),
                alert.getAckedAt(),
                alert.getAckedBy(),
                alert.getClearedAt());
    }
}
