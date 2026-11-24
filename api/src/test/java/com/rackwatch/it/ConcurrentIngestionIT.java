package com.rackwatch.it;

import com.rackwatch.model.AlertRule;
import com.rackwatch.model.AlertState;
import com.rackwatch.repository.AlertRepository;
import com.rackwatch.repository.ReadingRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concurrent ingestion against the real API (Testcontainers
 * PostgreSQL). Eight threads post the identical out-of-range batch
 * at the same moment. The batch is the same three readings, so the
 * readings table's UNIQUE (sensor_id, ts) must leave exactly three
 * rows, and the alerts table's partial unique index (one OPEN|ACKED
 * alert per sensor+rule) must leave exactly one SMART_THRESHOLD
 * alert — no matter how the transactions interleave. Every POST
 * still returns 200. */
class ConcurrentIngestionIT extends PostgresIT {

    private static final int THREADS = 8;

    @LocalServerPort
    private int port;

    @Autowired
    private ReadingRepository readings;

    @Autowired
    private AlertRepository alerts;

    @Test
    void concurrentIdenticalBatchesLeaveThreeReadingsAndOneAlert() throws Exception {
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-API-Key", API_KEY)
                .build();

        // Three consecutive out-of-range temps, all in the past so the
        // clock-skew rule (60 s) cannot reject them.
        Instant base = Instant.now().minusSeconds(180).truncatedTo(ChronoUnit.SECONDS);
        String body = "["
                + "{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"" + base + "\",\"temp_c\":30.0},"
                + "{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"" + base.plusSeconds(60) + "\",\"temp_c\":30.0},"
                + "{\"sensor_id\":\"ROOM-A-TEMP-01\",\"ts\":\"" + base.plusSeconds(120) + "\",\"temp_c\":30.0}"
                + "]";

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return client.post()
                            .uri("/api/v1/readings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .toBodilessEntity()
                            .getStatusCode()
                            .value();
                }));
            }
            // Wait until every thread is poised on the latch, then
            // release them all at once for a real race.
            ready.await();
            start.countDown();
            for (Future<Integer> future : futures) {
                assertEquals(200, future.get().intValue(), "every concurrent POST must succeed");
            }
        } finally {
            pool.shutdownNow();
        }

        // The duplicates were de-duplicated, not stored twice.
        assertEquals(3, readings.count(), "one row per (sensor_id, ts), not per thread");
        List<com.rackwatch.model.Alert> open = alerts.findBySensorIdAndStateIn(
                "ROOM-A-TEMP-01", List.of(AlertState.OPEN, AlertState.ACKED));
        assertEquals(1, open.size(), "exactly one unresolved alert for the sensor");
        assertEquals(AlertRule.SMART_THRESHOLD, open.get(0).getRule());
        assertTrue(open.get(0).getMessage().contains("30.0"));
    }
}
