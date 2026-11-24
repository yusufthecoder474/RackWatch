package com.rackwatch.it;

import com.rackwatch.model.Alert;
import com.rackwatch.model.AlertRule;
import com.rackwatch.model.AlertSeverity;
import com.rackwatch.model.Reading;
import com.rackwatch.model.Sensor;
import com.rackwatch.repository.AlertRepository;
import com.rackwatch.repository.ReadingRepository;
import com.rackwatch.repository.SensorRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The dashboard's JSON contract: the exact field paths dashboard.js
 * reads must exist in the API's responses, with the exact names and
 * the exact ack body. If a field is renamed on either side, this test
 * fails — that is the point. Rows are seeded directly through the
 * repositories (not through ingestion), so no evaluator runs and the
 * test methods cannot interfere with each other: each method asserts
 * on the rows it seeded. Runs under Failsafe (*IT) because it needs a
 * real PostgreSQL for the Flyway schema. */
class DashboardJsonContractIT extends PostgresIT {

    private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SensorRepository sensors;

    @Autowired
    private ReadingRepository readings;

    @Autowired
    private AlertRepository alerts;

    private Sensor sensor(String id) {
        return sensors.findById(id).orElseThrow(() -> new IllegalStateException("no sensor " + id));
    }

    @Test
    void roomsReturnsTheFieldsTheDashboardReads() throws Exception {
        readings.save(new Reading(sensor("ROOM-A-TEMP-01"), T0, 22.5, null));
        readings.save(new Reading(sensor("ROOM-A-RH-01"), T0, null, 50.0));

        mockMvc.perform(get("/api/v1/rooms").header("X-API-Key", API_KEY)).andDo(print())
                .andExpect(status().isOk())
                // The dashboard reads room.room_name, room.status, and
                // for each sensor: sensor_id, kind, temp_c, rh_pct, ts,
                // in_range. The filter picks the seeded room/sensor so
                // the assertion does not depend on list order.
                .andExpect(jsonPath("$[0].room_name").value("Server Room A"))
                .andExpect(jsonPath("$[0].status").value("GREEN"))
                .andExpect(jsonPath("$[0].sensors[1].kind")
                        .value("TEMP"))
                .andExpect(jsonPath("$[0].sensors[1].temp_c")
                        .value(22.5))
                .andExpect(jsonPath("$[0].sensors[1].rh_pct")
                        .value(nullValue()))
                .andExpect(jsonPath("$[0].sensors[1].ts")
                        .value("2026-11-23T10:00:00Z"))
                .andExpect(jsonPath("$[0].sensors[1].in_range")
                        .value(true))
                .andExpect(jsonPath("$[0].sensors[0].rh_pct")
                        .value(50.0));
    }

    @Test
    void roomsWithoutAKeyIs401() throws Exception {
        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void alertsReturnsTheFieldsTheDashboardReads() throws Exception {
        Alert alert = alerts.save(new Alert(sensor("ROOM-B-TEMP-01"),
                AlertRule.SMART_THRESHOLD, AlertSeverity.MEDIUM,
                "ROOM-B-TEMP-01 above 27.0 C for 3 readings"));

        mockMvc.perform(get("/api/v1/alerts").param("state", "OPEN")
                        .header("X-API-Key", API_KEY)).andDo(print())
                .andExpect(status().isOk())
                // The dashboard reads alert.id, sensor_id, rule,
                // severity, message, opened_at.
                .andExpect(jsonPath("$[0].id")
                        .value(alert.getId()))
                .andExpect(jsonPath("$[0].rule")
                        .value("SMART_THRESHOLD"))
                .andExpect(jsonPath("$[0].severity")
                        .value("MEDIUM"))
                .andExpect(jsonPath("$[0].message")
                        .value("ROOM-B-TEMP-01 above 27.0 C for 3 readings"))
                .andExpect(jsonPath("$[0].opened_at")
                        .exists());
    }

    @Test
    void readingsReturnsTheFieldsTheChartReads() throws Exception {
        readings.save(new Reading(sensor("ROOM-B-RH-01"), T0, null, 50.0));
        readings.save(new Reading(sensor("ROOM-B-RH-01"), T0.plusSeconds(60), null, 51.0));

        mockMvc.perform(get("/api/v1/readings")
                        .param("sensorId", "ROOM-B-RH-01")
                        .param("from", "2026-11-23T09:00:00Z")
                        .param("to", "2026-11-23T11:00:00Z")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                // The chart reads reading.sensor_id, ts, and temp_c or
                // rh_pct depending on the sensor kind. The rows come
                // back oldest first.
                .andExpect(jsonPath("$[0].sensor_id").value("ROOM-B-RH-01"))
                .andExpect(jsonPath("$[0].ts").value("2026-11-23T10:00:00Z"))
                .andExpect(jsonPath("$[0].rh_pct").value(50.0))
                .andExpect(jsonPath("$[0].temp_c").value(nullValue()))
                .andExpect(jsonPath("$[1].rh_pct").value(51.0));
    }

    @Test
    void ackAcceptsTheExactBodyTheDashboardSends() throws Exception {
        Alert alert = alerts.save(new Alert(sensor("ROOM-A-TEMP-01"),
                AlertRule.SMART_THRESHOLD, AlertSeverity.MEDIUM,
                "ROOM-A-TEMP-01 above 27.0 C for 3 readings"));

        // The dashboard sends {"acked_by": "<name>"} (snake_case).
        mockMvc.perform(post("/api/v1/alerts/" + alert.getId() + "/ack")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acked_by\": \"Maya\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alert.getId()))
                .andExpect(jsonPath("$.acked_by").value("Maya"))
                .andExpect(jsonPath("$.state").value("ACKED"));
    }

    @Test
    void reportEndpointServesAnXlsxDownload() throws Exception {
        readings.save(new Reading(sensor("ROOM-A-TEMP-01"), T0.plusSeconds(120), 22.5, null));

        mockMvc.perform(get("/api/v1/reports/readings.xlsx")
                        .param("from", "2026-11-23T09:00:00Z")
                        .param("to", "2026-11-23T11:00:00Z")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("rackwatch-readings.xlsx")));
    }
}
