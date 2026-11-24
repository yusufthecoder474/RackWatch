package com.rackwatch.service;

import com.rackwatch.repository.ReadingRepository;
import com.rackwatch.web.dto.ReadingResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only history queries for the dashboard chart. This is separate
 * from ReadingIngestionService (writes) so the two paths stay
 * independent: a bad read query can never touch ingestion. */
@Service
public class ReadingQueryService {

    /** At most this many readings per history request. A sensor sampled
     * every 5 s produces over 17,000 readings a day; returning them all
     * would be a multi-megabyte response and a chart nobody can read.
     * When the range holds more, the most recent 1,000 are returned —
     * the chart shows recent data, not the oldest slice of the range. */
    public static final int MAX_HISTORY_ROWS = 1000;

    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    private final ReadingRepository readings;

    public ReadingQueryService(ReadingRepository readings) {
        this.readings = readings;
    }

    /** Readings for one sensor in [from, to]. Both bounds default when
     * omitted: to = now, from = 24 h before to. Throws
     * IllegalArgumentException (mapped to 400) when from is after to. */
    @Transactional(readOnly = true)
    public List<ReadingResponse> history(String sensorId, Instant from, Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(DEFAULT_WINDOW);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        // Newest first with a limit, then reversed: the caller gets the
        // most recent readings in the range, in time order.
        List<ReadingResponse> newestFirst = readings
                .findBySensorIdAndTsBetweenOrderByTsDesc(
                        sensorId, start, end, PageRequest.of(0, MAX_HISTORY_ROWS))
                .stream()
                .map(ReadingResponse::from)
                .toList();
        return new ArrayList<>(newestFirst.reversed());
    }
}
