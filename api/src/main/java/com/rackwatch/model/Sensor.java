package com.rackwatch.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** A physical sensor in a room, e.g. ROOM-A-TEMP-01. The id is the
 * identifier the daemon sends in every reading. */
@Entity
@Table(name = "sensors")
public class Sensor {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private SensorKind kind;

    @Column(name = "unit", nullable = false, length = 16)
    private String unit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Sensor() {
    }

    public Sensor(String id, Room room, SensorKind kind, String unit) {
        this.id = id;
        this.room = room;
        this.kind = kind;
        this.unit = unit;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public SensorKind getKind() {
        return kind;
    }

    public String getUnit() {
        return unit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
