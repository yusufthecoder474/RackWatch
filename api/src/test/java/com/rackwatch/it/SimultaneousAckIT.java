package com.rackwatch.it;

import com.rackwatch.model.Alert;
import com.rackwatch.model.AlertState;
import com.rackwatch.repository.AlertRepository;
import com.rackwatch.repository.AuditLogRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
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

/** Two simultaneous acks of the same alert against the real API.
 * One must return 200 as the ack that wins; the other must also
 * return 200 — idempotently, with no second audit row. The alert
 * ends ACKED, exactly one audit row exists for it, and the recorded
 * actor is whichever of the two callers won. The pessimistic write
 * lock in AlertService makes this deterministic: without it, both
 * transactions could read OPEN and both write an audit row. */
class SimultaneousAckIT extends PostgresIT {

    @LocalServerPort
    private int port;

    @Autowired
    private AlertRepository alerts;

    @Autowired
    private AuditLogRepository auditLog;

    @Test
    void twoSimultaneousAcksProduceOneAuditRow() throws Exception {
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-API-Key", API_KEY)
                .build();

        // Open a real alert through ingestion: three consecutive
        // out-of-range temps for ROOM-B-TEMP-01.
        Instant base = Instant.now().minusSeconds(180).truncatedTo(ChronoUnit.SECONDS);
        String body = "["
                + "{\"sensor_id\":\"ROOM-B-TEMP-01\",\"ts\":\"" + base + "\",\"temp_c\":30.0},"
                + "{\"sensor_id\":\"ROOM-B-TEMP-01\",\"ts\":\"" + base.plusSeconds(60) + "\",\"temp_c\":30.0},"
                + "{\"sensor_id\":\"ROOM-B-TEMP-01\",\"ts\":\"" + base.plusSeconds(120) + "\",\"temp_c\":30.0}"
                + "]";
        client.post()
                .uri("/api/v1/readings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        List<Alert> open = alerts.findBySensorIdAndStateIn(
                "ROOM-B-TEMP-01", List.of(AlertState.OPEN));
        assertEquals(1, open.size(), "ingestion must have opened exactly one alert");
        long alertId = open.get(0).getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> alice = pool.submit(() -> ack(client, alertId, "Alice", ready, start));
            Future<Integer> bob = pool.submit(() -> ack(client, alertId, "Bob", ready, start));
            ready.await();
            start.countDown();
            // Both return 200: the winner acks, the loser is idempotent.
            assertEquals(200, alice.get().intValue());
            assertEquals(200, bob.get().intValue());
        } finally {
            pool.shutdownNow();
        }

        Alert alert = alerts.findById(alertId).orElseThrow();
        assertEquals(AlertState.ACKED, alert.getState());
        assertTrue(Set.of("Alice", "Bob").contains(alert.getAckedBy()),
                "the recorded actor must be one of the two callers");
        assertEquals(1, auditLog.countByAlert_Id(alertId),
                "exactly one audit row for the alert, not one per ack");
        long actorRows = auditLog.findAll().stream()
                .filter(row -> row.getAlert() != null && row.getAlert().getId() == alertId)
                .filter(row -> alert.getAckedBy().equals(row.getActor()))
                .count();
        assertEquals(1, actorRows, "the audit row's actor must match the ack");
    }

    private static int ack(RestClient client, long alertId, String name,
                           CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return client.post()
                .uri("/api/v1/alerts/" + alertId + "/ack")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"acked_by\": \"" + name + "\"}")
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value();
    }
}
