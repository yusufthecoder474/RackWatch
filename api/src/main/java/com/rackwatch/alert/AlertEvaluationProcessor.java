package com.rackwatch.alert;

import com.rackwatch.config.RackwatchProperties;
import com.rackwatch.model.Alert;
import com.rackwatch.model.AlertRule;
import com.rackwatch.model.AlertState;
import com.rackwatch.model.Reading;
import com.rackwatch.model.Sensor;
import com.rackwatch.repository.AlertRepository;
import com.rackwatch.repository.ReadingRepository;
import com.rackwatch.repository.SensorRepository;
import com.rackwatch.service.ReadingPostProcessor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Wires the pure AlertEvaluator into live ingestion. Runs inside the
 * ingestion transaction, after the readings are stored.
 *
 * State is rebuilt from stored readings on every call: the history is
 * the readings in the rate window plus the last N readings (for the
 * flatline rule), merged by timestamp, plus the sensor's unresolved
 * alerts from the alerts table. Nothing is kept in memory between
 * calls, so a restart cannot change results.
 *
 * Late readings are stored but not re-evaluated: a sensor is only
 * evaluated when this batch delivers a new maximum timestamp for it.
 * If the stored newest reading is newer than the batch (a buffer
 * flush of old readings, or a concurrent request that already moved
 * the sensor forward), evaluation is skipped — the newer evaluation
 * already covered, or will cover, this history. */
@Component
public class AlertEvaluationProcessor implements ReadingPostProcessor {

    private final ReadingRepository readings;
    private final AlertRepository alerts;
    private final SensorRepository sensors;
    private final AlertOpeningInserter opener;
    private final AlertEvaluator evaluator;
    private final int rateWindowMinutes;
    private final int flatlineReadings;

    public AlertEvaluationProcessor(
            ReadingRepository readings,
            AlertRepository alerts,
            SensorRepository sensors,
            AlertOpeningInserter opener,
            RackwatchProperties properties) {
        this.readings = readings;
        this.alerts = alerts;
        this.sensors = sensors;
        this.opener = opener;
        this.evaluator = new AlertEvaluator(properties.toAlertParameters());
        this.rateWindowMinutes = properties.alerts().rate().windowMinutes();
        this.flatlineReadings = properties.alerts().health().flatlineReadings();
    }

    @Override
    public void afterInsert(List<Reading> inserted) {
        Map<String, List<Reading>> bySensor = inserted.stream()
                .collect(Collectors.groupingBy(reading -> reading.getSensor().getId()));
        for (Map.Entry<String, List<Reading>> entry : bySensor.entrySet()) {
            String sensorId = entry.getKey();
            Instant batchMax = entry.getValue().stream()
                    .map(Reading::getTs)
                    .max(Instant::compareTo)
                    .orElseThrow();
            Optional<Reading> storedLatest = readings.findFirstBySensorIdOrderByTsDesc(sensorId);
            if (storedLatest.isEmpty() || storedLatest.get().getTs().isAfter(batchMax)) {
                continue;
            }
            evaluateSensor(sensorId, batchMax);
        }
    }

    private void evaluateSensor(String sensorId, Instant latestTs) {
        Sensor sensor = sensors.findById(sensorId).orElseThrow();
        List<ReadingValue> history = loadHistory(sensorId, latestTs);
        Set<AlertRule> unresolved = alerts
                .findBySensorIdAndStateIn(sensorId, List.of(AlertState.OPEN, AlertState.ACKED))
                .stream()
                .map(Alert::getRule)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AlertRule.class)));
        List<AlertDecision> decisions = evaluator.evaluate(sensor.getKind(), history, unresolved);
        for (AlertDecision decision : decisions) {
            applyDecision(sensorId, decision, latestTs);
        }
    }

    /** History for one evaluation: every reading in the rate window
     * (plus one minute of margin) and the last flatlineReadings
     * readings, merged by timestamp and sorted ascending. The window
     * covers R3 at any sampling interval; the last-N covers R4 and
     * the streaks of R2. */
    private List<ReadingValue> loadHistory(String sensorId, Instant latestTs) {
        TreeMap<Instant, ReadingValue> merged = new TreeMap<>();
        Instant windowStart = latestTs.minus(java.time.Duration.ofMinutes(rateWindowMinutes + 1L));
        for (Reading reading : readings.findBySensorIdAndTsBetweenOrderByTsAsc(
                sensorId, windowStart, latestTs)) {
            merged.put(reading.getTs(), toValue(reading));
        }
        for (Reading reading : readings.findBySensorIdOrderByTsDesc(
                sensorId, PageRequest.of(0, flatlineReadings))) {
            merged.put(reading.getTs(), toValue(reading));
        }
        return new ArrayList<>(merged.values());
    }

    private ReadingValue toValue(Reading reading) {
        return new ReadingValue(reading.getTs(), reading.getTempC(), reading.getRhPct());
    }

    private void applyDecision(String sensorId, AlertDecision decision, Instant at) {
        if (decision.action() == AlertAction.OPEN) {
            opener.insertIfAbsent(
                    sensorId, decision.rule(), decision.severity(), decision.message(), at);
            return;
        }
        alerts.findFirstBySensorIdAndRuleAndStateIn(
                        sensorId, decision.rule(), List.of(AlertState.OPEN, AlertState.ACKED))
                .ifPresent(alert -> {
                    // Clears OPEN and ACKED alike: an acknowledged alert
                    // whose condition has ended is finished, not open.
                    alert.clear(at);
                    alerts.save(alert);
                });
    }
}
