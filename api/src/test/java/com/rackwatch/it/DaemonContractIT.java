package com.rackwatch.it;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.rackwatch.repository.ReadingRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The daemon/API contract, end to end. Posts
 * daemon/test/golden_batch.json — the exact bytes the daemon emits
 * for its default sensors.conf, captured with:
 *   cd daemon && ./rackwatchd --config sensors.conf --dry-run --once --seed 42 > test/golden_batch.json
 * The API must accept it (200) and store every row. Posting the
 * same batch again must still return 200 and insert nothing (the
 * readings table's UNIQUE (sensor_id, ts) de-duplicates it). If the
 * daemon's JSON drifts from the API's DTO, this test fails — that is
 * the point. The golden batch's timestamps are in the past, so the
 * 60 s clock-skew rule never rejects it, whenever the test runs. */
class DaemonContractIT extends PostgresIT {

    private static final Set<String> SEEDED_SENSORS = Set.of(
            "ROOM-A-TEMP-01", "ROOM-A-RH-01", "ROOM-B-TEMP-01", "ROOM-B-RH-01");

    @LocalServerPort
    private int port;

    @Autowired
    private ReadingRepository readings;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void goldenBatchPostsAndStores() throws Exception {
        Path golden = findGoldenBatch();
        String body = Files.readString(golden);
        JsonNode batch = objectMapper.readTree(body);
        assertTrue(batch.isArray(), "the golden batch must be a bare JSON array");
        assertEquals(4, batch.size(), "the golden batch has one reading per seeded sensor");
        for (JsonNode reading : batch) {
            assertTrue(SEEDED_SENSORS.contains(reading.get("sensor_id").asText()),
                    "golden batch sensor ids must be the seeded ones");
        }

        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-API-Key", API_KEY)
                .build();

        String first = postBatch(client, body);
        JsonNode firstResult = objectMapper.readTree(first);
        assertEquals(4, firstResult.get("total_received").asInt());
        assertEquals(4, firstResult.get("inserted").asInt());
        assertEquals(0, firstResult.get("skipped").asInt());

        // Every golden reading is stored, with its sampled timestamp.
        assertEquals(4, readings.count());
        for (JsonNode reading : batch) {
            String sensorId = reading.get("sensor_id").asText();
            Instant ts = Instant.parse(reading.get("ts").asText());
            assertTrue(readings.findBySensorIdAndTsBetweenOrderByTsAsc(sensorId, ts, ts)
                            .stream().anyMatch(r -> r.getTs().equals(ts)),
                    "reading stored for " + sensorId + " at " + ts);
        }

        // A re-send of the same batch is still a 200, and inserts
        // nothing: the daemon re-posts its buffer after an outage, and
        // that must never duplicate rows.
        String second = postBatch(client, body);
        JsonNode secondResult = objectMapper.readTree(second);
        assertEquals(4, secondResult.get("total_received").asInt());
        assertEquals(0, secondResult.get("inserted").asInt());
        assertEquals(4, secondResult.get("skipped").asInt());
        assertEquals(4, readings.count());
    }

    private static String postBatch(RestClient client, String body) {
        return client.post()
                .uri("/api/v1/readings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /** The golden file lives in daemon/test/, outside the api module.
     * Resolve it from the working directory, walking up so the test
     * works whether Maven runs it from api/ or from the repo root. */
    private static Path findGoldenBatch() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve("daemon/test/golden_batch.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "daemon/test/golden_batch.json not found. Generate it with: "
                + "cd daemon && ./rackwatchd --config sensors.conf --dry-run --once --seed 42 "
                + "> test/golden_batch.json");
    }
}
