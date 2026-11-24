package com.rackwatch.service;

import com.rackwatch.model.Alert;
import com.rackwatch.model.AlertState;
import com.rackwatch.model.AuditLog;
import com.rackwatch.repository.AlertRepository;
import com.rackwatch.repository.AuditLogRepository;
import com.rackwatch.web.dto.AlertResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alert reads and acknowledgement. Acknowledging writes an audit_log
 * row in the same transaction as the state change, so the two can never
 * disagree: either both happen or neither does. */
@Service
public class AlertService {

    private final AlertRepository alerts;
    private final AuditLogRepository auditLog;

    public AlertService(AlertRepository alerts, AuditLogRepository auditLog) {
        this.alerts = alerts;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> listAlerts(AlertState state) {
        return alerts.findByStateOrderByOpenedAtDesc(state).stream()
                .map(AlertResponse::from)
                .toList();
    }

    /** Acknowledge an alert. Idempotent: acknowledging an already-ACKED
     * alert returns it unchanged and writes no second audit row.
     * Acknowledging a CLEARED alert is a conflict (409). The row is
     * read with a pessimistic write lock, so two simultaneous acks
     * serialise: the second waits, then reads ACKED and returns
     * idempotently instead of writing a second audit row. Without the
     * lock both could read OPEN and both write an audit row. */
    @Transactional
    public AlertResponse acknowledge(long id, String actor) {
        Alert alert = alerts.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Alert not found: " + id));
        if (alert.getState() == AlertState.CLEARED) {
            throw new ConflictException(
                    "Alert " + id + " is already cleared and cannot be acknowledged");
        }
        if (alert.getState() == AlertState.ACKED) {
            return AlertResponse.from(alert);
        }
        alert.acknowledge(actor, Instant.now());
        alerts.save(alert);
        auditLog.save(new AuditLog(alert, "ACK", actor, "Alert acknowledged by " + actor));
        return AlertResponse.from(alert);
    }
}
