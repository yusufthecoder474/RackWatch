package com.rackwatch.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rackwatch.model.AlertRule;
import com.rackwatch.model.SensorKind;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Evaluation replay. Replays the three labelled, deterministic,
 * SIMULATED scenarios through R1 (naive) and through R2+R3+R4 (smart),
 * one reading at a time, exactly as live ingestion would see them,
 * and writes the results to docs/evaluation.md.
 *
 * Metrics (per scenario, per system):
 *   false alarm     — an alert opened on a reading labelled NORMAL.
 *   missed incident — a labelled incident (a maximal run of rows with
 *                     the same non-NORMAL label on one sensor) during
 *                     which no alert opened.
 *   detection delay — minutes (and readings) from the incident's first
 *                     labelled reading to the first alert opened for it.
 *
 * Every number in docs/evaluation.md comes from this run: the test
 * computes the metrics and writes the file, then asserts the design
 * claims. No number in the report is written by hand. */
class EvaluationReplayTest {

    private static final String[] SCENARIOS = {
            "normal_day.csv", "cooling_failure.csv", "stuck_sensor.csv"
    };

    private final AlertEvaluator evaluator = new AlertEvaluator(AlertParameters.defaults());

    @Test
    void replayScenariosAndWriteReport() throws IOException {
        List<ScenarioResult> results = new ArrayList<>();
        for (String scenario : SCENARIOS) {
            List<Row> rows = loadScenario(scenario);
            results.add(replay(scenario, rows, SystemUnderTest.NAIVE));
            results.add(replay(scenario, rows, SystemUnderTest.SMART));
        }
        writeReport(results);
        assertClaims(results);
    }

    // -- replay ----------------------------------------------------------

    /** Named SystemUnderTest, not System: a nested enum called System
     * would shadow java.lang.System inside this class and break
     * System.getProperty below. */
    private enum SystemUnderTest { NAIVE, SMART }

    private enum Label { NORMAL, COOLING_FAILURE, SENSOR_FAULT }

    private record Row(String sensorId, Instant ts, Double tempC, Double rhPct, Label label) {
    }

    private record Incident(Label label, int startIndex, Instant startTs, int endIndex) {
    }

    private record ScenarioResult(
            String scenario,
            SystemUnderTest system,
            int falseAlarms,
            int missedIncidents,
            int incidents,
            int alertsOpened,
            List<Long> delayMinutes,
            List<Long> delayReadings) {
    }

    private ScenarioResult replay(String scenario, List<Row> rows, SystemUnderTest system) {
        Map<String, List<Row>> bySensor = rows.stream()
                .collect(Collectors.groupingBy(Row::sensorId));
        int falseAlarms = 0;
        int missedIncidents = 0;
        int incidents = 0;
        int alertsOpened = 0;
        List<Long> delayMinutes = new ArrayList<>();
        List<Long> delayReadings = new ArrayList<>();

        for (List<Row> sensorRows : bySensor.values()) {
            sensorRows.sort((a, b) -> a.ts().compareTo(b.ts()));
            SensorKind kind = sensorRows.get(0).tempC() != null
                    ? SensorKind.TEMP : SensorKind.HUMIDITY;
            List<Incident> sensorIncidents = findIncidents(sensorRows);
            incidents += sensorIncidents.size();
            Map<Incident, Long> detectedAt = new HashMap<>();
            List<ReadingValue> history = new ArrayList<>();
            Set<AlertRule> unresolved = EnumSet.noneOf(AlertRule.class);

            for (int i = 0; i < sensorRows.size(); i++) {
                Row row = sensorRows.get(i);
                history.add(new ReadingValue(row.ts(), row.tempC(), row.rhPct()));
                List<AlertDecision> decisions = system == SystemUnderTest.NAIVE
                        ? evaluator.evaluateNaive(kind, history, unresolved)
                        : evaluator.evaluate(kind, history, unresolved);
                for (AlertDecision decision : decisions) {
                    if (decision.action() == AlertAction.OPEN) {
                        alertsOpened++;
                        unresolved.add(decision.rule());
                        if (row.label() == Label.NORMAL) {
                            falseAlarms++;
                        } else {
                            for (Incident incident : sensorIncidents) {
                                if (i >= incident.startIndex() && i <= incident.endIndex()
                                        && !detectedAt.containsKey(incident)) {
                                    detectedAt.put(incident, (long) i);
                                    delayMinutes.add(Duration.between(
                                            incident.startTs(), row.ts()).toMinutes());
                                    delayReadings.add((long) (i - incident.startIndex()));
                                }
                            }
                        }
                    } else {
                        unresolved.remove(decision.rule());
                    }
                }
            }
            missedIncidents += sensorIncidents.size() - detectedAt.size();
        }
        return new ScenarioResult(scenario, system, falseAlarms, missedIncidents,
                incidents, alertsOpened, delayMinutes, delayReadings);
    }

    /** Maximal runs of consecutive rows carrying the same non-NORMAL label. */
    private List<Incident> findIncidents(List<Row> rows) {
        List<Incident> incidents = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Label label = rows.get(i).label();
            if (label == Label.NORMAL) {
                continue;
            }
            int end = i;
            while (end + 1 < rows.size() && rows.get(end + 1).label() == label) {
                end++;
            }
            incidents.add(new Incident(label, i, rows.get(i).ts(), end));
            i = end;
        }
        return incidents;
    }

    private List<Row> loadScenario(String file) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/scenarios/" + file)) {
            if (in == null) {
                throw new IOException("Scenario not on classpath: /scenarios/" + file);
            }
            List<Row> rows = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (header == null || !header.startsWith("sensor_id,ts,")) {
                    throw new IOException("Unexpected header in " + file + ": " + header);
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split(",", -1);
                    rows.add(new Row(
                            parts[0],
                            Instant.parse(parts[1]),
                            parts[2].isEmpty() ? null : Double.valueOf(parts[2]),
                            parts[3].isEmpty() ? null : Double.valueOf(parts[3]),
                            Label.valueOf(parts[4])));
                }
            }
            return rows;
        }
    }

    // -- report ----------------------------------------------------------

    private void writeReport(List<ScenarioResult> results) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# RackWatch evaluation\n\n");
        md.append("> All data in this report is SIMULATED. The scenarios are\n");
        md.append("> deterministic labelled CSVs (seed 42, one reading per minute),\n");
        md.append("> generated by `api/src/test/resources/scenarios/generate_scenarios.py`.\n");
        md.append("> Every number below was computed by `EvaluationReplayTest` from\n");
        md.append("> those CSVs; none was written by hand.\n\n");
        md.append("## Method\n\n");
        md.append("Each scenario is replayed one reading at a time, per sensor, through\n");
        md.append("two systems: **naive** (R1 only — first out-of-range reading opens)\n");
        md.append("and **smart** (R2 threshold + R3 rate of change + R4 sensor health).\n");
        md.append("The evaluator sees the same history live ingestion would: all readings\n");
        md.append("so far for that sensor, in timestamp order.\n\n");
        md.append("## Metrics\n\n");
        md.append("- **False alarm** — an alert opened on a reading labelled NORMAL.\n");
        md.append("- **Missed incident** — a labelled incident (a maximal run of rows\n");
        md.append("  with the same non-NORMAL label on one sensor) during which no\n");
        md.append("  alert opened.\n");
        md.append("- **Detection delay** — minutes (and readings) from the incident's\n");
        md.append("  first labelled reading to the first alert opened for it. Only\n");
        md.append("  detected incidents have a delay; a missed incident has none.\n\n");
        md.append("## Results\n\n");
        md.append("| Scenario | System | False alarms | Missed incidents | Incidents | "
                + "Alerts opened | Detection delay (min) | Detection delay (readings) |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        for (ScenarioResult r : results) {
            md.append("| ").append(r.scenario().replace(".csv", ""))
                    .append(" | ").append(r.system().name().toLowerCase())
                    .append(" | ").append(r.falseAlarms())
                    .append(" | ").append(r.missedIncidents())
                    .append(" | ").append(r.incidents())
                    .append(" | ").append(r.alertsOpened())
                    .append(" | ").append(joinOrDash(r.delayMinutes()))
                    .append(" | ").append(joinOrDash(r.delayReadings()))
                    .append(" |\n");
        }
        md.append("\n## Totals\n\n");
        for (SystemUnderTest system : SystemUnderTest.values()) {
            int falseAlarms = results.stream()
                    .filter(r -> r.system() == system).mapToInt(ScenarioResult::falseAlarms).sum();
            int missed = results.stream()
                    .filter(r -> r.system() == system).mapToInt(ScenarioResult::missedIncidents).sum();
            int opened = results.stream()
                    .filter(r -> r.system() == system).mapToInt(ScenarioResult::alertsOpened).sum();
            md.append("- ").append(system.name().toLowerCase())
                    .append(": ").append(falseAlarms).append(" false alarms, ")
                    .append(missed).append(" missed incidents, ")
                    .append(opened).append(" alerts opened.\n");
        }
        md.append("\n## Scenarios\n\n");
        md.append("- **normal_day** — a full day (1440 readings per sensor) of normal\n");
        md.append("  temperature and humidity with five single-reading temperature\n");
        md.append("  spikes and two single-reading humidity blips, all labelled NORMAL.\n");
        md.append("- **cooling_failure** — 120 minutes: 30 normal, then the temperature\n");
        md.append("  ramps +0.5 C per minute for 20 minutes and holds near 32 C.\n");
        md.append("- **stuck_sensor** — 120 minutes: 30 normal, then the temperature\n");
        md.append("  sensor sticks at exactly 22.5 C (in range) for 90 minutes.\n");

        Path report = reportPath();
        Files.createDirectories(report.getParent());
        Files.writeString(report, md.toString(), StandardCharsets.UTF_8);
    }

    private String joinOrDash(List<Long> values) {
        if (values.isEmpty()) {
            return "n/a";
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    /** Tests run with the working directory at api/, so docs/ is one
     * level up. If the layout ever changes, prefer docs/ beside the
     * working directory. */
    private Path reportPath() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        if (Files.isDirectory(cwd.resolve("docs"))) {
            return cwd.resolve("docs/evaluation.md");
        }
        return cwd.resolve("../docs/evaluation.md");
    }

    // -- claims ----------------------------------------------------------

    private void assertClaims(List<ScenarioResult> results) {
        ScenarioResult normalSmart = find(results, "normal_day.csv", SystemUnderTest.SMART);
        ScenarioResult normalNaive = find(results, "normal_day.csv", SystemUnderTest.NAIVE);
        ScenarioResult coolingSmart = find(results, "cooling_failure.csv", SystemUnderTest.SMART);
        ScenarioResult coolingNaive = find(results, "cooling_failure.csv", SystemUnderTest.NAIVE);
        ScenarioResult stuckSmart = find(results, "stuck_sensor.csv", SystemUnderTest.SMART);
        ScenarioResult stuckNaive = find(results, "stuck_sensor.csv", SystemUnderTest.NAIVE);

        // Smart never fires on a blip; naive fires on every one (5 temp + 2 humidity).
        assertEquals(0, normalSmart.falseAlarms(), "smart must not fire on blips");
        assertEquals(7, normalNaive.falseAlarms(), "naive fires on every blip");
        // No scenario may give the smart system a false alarm.
        for (ScenarioResult r : results) {
            if (r.system() == SystemUnderTest.SMART) {
                assertEquals(0, r.falseAlarms(), "smart false alarm in " + r.scenario());
            }
        }
        // Both systems catch the cooling failure, but the smart system's
        // rate rule sees it first (a rise, not just a crossed limit).
        assertEquals(0, coolingSmart.missedIncidents());
        assertEquals(0, coolingNaive.missedIncidents());
        assertEquals(1, coolingSmart.delayMinutes().size());
        assertEquals(1, coolingNaive.delayMinutes().size());
        assertTrue(coolingSmart.delayMinutes().get(0) <= coolingNaive.delayMinutes().get(0),
                "smart should detect the cooling failure no later than naive");
        // The stuck sensor is in range, so naive never sees it; only the
        // flatline rule catches it.
        assertEquals(0, stuckSmart.missedIncidents());
        assertEquals(1, stuckNaive.missedIncidents());
        assertEquals(1, stuckSmart.delayMinutes().size());
    }

    private ScenarioResult find(List<ScenarioResult> results, String scenario, SystemUnderTest system) {
        return results.stream()
                .filter(r -> r.scenario().equals(scenario) && r.system() == system)
                .findFirst()
                .orElseThrow();
    }
}
