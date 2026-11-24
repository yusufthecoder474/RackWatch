package com.rackwatch.repository;

import com.rackwatch.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** How many audit rows exist for one alert. Used by the
     * simultaneous-ack integration test: two concurrent acks must
     * produce exactly one row. */
    long countByAlert_Id(long alertId);
}
