package com.rackwatch.repository;

import com.rackwatch.model.Reading;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read queries only. Writes go through ReadingBatchInserter, which uses
 * a single JDBC batch with ON CONFLICT DO NOTHING — JpaRepository.save()
 * cannot express that, and saving rows one by one would turn a duplicate
 * re-send into a failed batch. */
public interface ReadingRepository extends JpaRepository<Reading, Long> {

    Optional<Reading> findFirstBySensorIdOrderByTsDesc(String sensorId);

    List<Reading> findBySensorIdOrderByTsDesc(String sensorId, Pageable pageable);

    List<Reading> findBySensorIdAndTsBetweenOrderByTsAsc(String sensorId, Instant from, Instant to);

    /** Newest first in the range, capped by the pageable: the history
     * endpoint returns the most recent readings, not the oldest slice. */
    List<Reading> findBySensorIdAndTsBetweenOrderByTsDesc(
            String sensorId, Instant from, Instant to, Pageable pageable);

    /** All sensors in the range, oldest first, capped by the pageable:
     * the report asks for one more than its row cap so it can tell
     * "too many readings" from "exactly the cap". */
    List<Reading> findByTsBetweenOrderByTsAsc(Instant from, Instant to, Pageable pageable);
}
