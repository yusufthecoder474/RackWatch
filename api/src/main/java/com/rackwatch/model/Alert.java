package com.rackwatch.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** An alert opened by the alert engine. State transitions live here so
 * they cannot be done half-way: acknowledge() and clear() set the state
 * and its timestamp together, matching the CHECK constraints in V1. */
@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sensor_id", nullable = false)
    private Sensor sensor;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule", nullable = false, length = 32)
    private AlertRule rule;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private AlertSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private AlertState state;

    @Column(name = "message", nullable = false)
    private String message;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "acked_at")
    private Instant ackedAt;

    @Column(name = "acked_by", length = 100)
    private String ackedBy;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    protected Alert() {
    }

    public Alert(Sensor sensor, AlertRule rule, AlertSeverity severity, String message) {
        this.sensor = sensor;
        this.rule = rule;
        this.severity = severity;
        this.state = AlertState.OPEN;
        this.message = message;
        this.openedAt = Instant.now();
    }

    /** OPEN -> ACKED. Records who acknowledged and when. */
    public void acknowledge(String actor, Instant now) {
        this.state = AlertState.ACKED;
        this.ackedAt = now;
        this.ackedBy = actor;
    }

    /** OPEN or ACKED -> CLEARED. Used by the evaluator (Part 3) when the
     * condition ends — including for ACKED alerts, so an acknowledged
     * alert does not stay open forever once the room is healthy again. */
    public void clear(Instant now) {
        this.state = AlertState.CLEARED;
        this.clearedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Sensor getSensor() {
        return sensor;
    }

    public AlertRule getRule() {
        return rule;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public AlertState getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getAckedAt() {
        return ackedAt;
    }

    public String getAckedBy() {
        return ackedBy;
    }

    public Instant getClearedAt() {
        return clearedAt;
    }
}
