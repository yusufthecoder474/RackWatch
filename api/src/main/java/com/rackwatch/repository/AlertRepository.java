package com.rackwatch.repository;

import com.rackwatch.model.Alert;
import com.rackwatch.model.AlertRule;
import com.rackwatch.model.AlertState;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByStateOrderByOpenedAtDesc(AlertState state);

    List<Alert> findByStateInOrderByOpenedAtDesc(Collection<AlertState> states);

    /** Unresolved alerts (OPEN or ACKED) for one sensor, for the
     * evaluator's rebuild of state. */
    List<Alert> findBySensorIdAndStateIn(String sensorId, Collection<AlertState> states);

    /** The evaluator's "is there already an unresolved alert for this
     * sensor and rule" check. The database's partial unique index is
     * the real guard under concurrency; this query is the cheap
     * check-before-insert. */
    Optional<Alert> findFirstBySensorIdAndRuleAndStateIn(
            String sensorId, AlertRule rule, Collection<AlertState> states);

    /** The alert row, locked for update until the caller's transaction
     * commits. Used by acknowledge(): two simultaneous acks of the
     * same alert serialise on this lock, so the second one reads the
     * already-ACKED state and returns idempotently instead of writing
     * a second audit row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Alert a where a.id = :id")
    Optional<Alert> findByIdForUpdate(@Param("id") long id);
}
