package com.rackwatch.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** One sensor reading. Exactly one of tempC / rhPct is non-null — the
 * database enforces that with CHECK (num_nonnulls(temp_c, rh_pct) = 1)
 * and the ingestion service enforces that the value matches the sensor
 * kind. The database also enforces UNIQUE (sensor_id, ts), so a
 * re-sent reading is a duplicate, not a second row. */
@Entity
@Table(name = "readings")
public class Reading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sensor_id", nullable = false)
    private Sensor sensor;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    @Column(name = "temp_c")
    private Double tempC;

    @Column(name = "rh_pct")
    private Double rhPct;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Reading() {
    }

    public Reading(Sensor sensor, Instant ts, Double tempC, Double rhPct) {
        this.sensor = sensor;
        this.ts = ts;
        this.tempC = tempC;
        this.rhPct = rhPct;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Sensor getSensor() {
        return sensor;
    }

    public Instant getTs() {
        return ts;
    }

    public Double getTempC() {
        return tempC;
    }

    public Double getRhPct() {
        return rhPct;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
