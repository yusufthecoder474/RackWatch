package com.rackwatch.alert;

import com.rackwatch.model.AlertRule;
import com.rackwatch.model.AlertSeverity;
import com.rackwatch.model.SensorKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** The alert engine. A pure class: no Spring, no database, no clock.
 * All it needs is a sensor's recent readings (ascending by timestamp),
 * which rules already have an unresolved alert, and its parameters.
 * Because every decision is recomputed from stored readings, a restart
 * changes nothing — there is no in-memory streak counter to lose — and
 * two concurrent evaluations of the same sensor compute the same
 * decisions from the same stored state.
 *
 * Rules (see README for the table):
 *   R1 NAIVE — evaluateNaive() only. First out-of-range reading opens.
 *     Kept as the baseline for the evaluation replay; live ingestion
 *     never calls it.
 *   R2 SMART_THRESHOLD — opens after N consecutive out-of-range
 *     readings; clears after M consecutive readings back inside the
 *     range with a hysteresis margin.
 *   R3 RATE_OF_CHANGE — temperature rise >= 3 C within 10 minutes,
 *     HELD for confirm-readings consecutive readings, opens HIGH, even
 *     inside the limits. TEMP sensors only. The rise is measured as
 *     the minimum of the last confirm-readings values minus the window
 *     minimum: a single out-of-range blip rises fast and falls back,
 *     so its minimum over the last 3 readings is still low and no
 *     alert opens. Measuring latest-minus-minimum instead fired on
 *     every blip (5 false alarms for the smart system on the
 *     normal-day scenario).
 *   R4 SENSOR_HEALTH — flatline (identical value for >= 30 readings)
 *     or a jump > 15 C between consecutive readings opens SENSOR_FAULT.
 *     While a fault is detected, the sensor's readings are excluded
 *     from the room rules (R2/R3 make no decision at all, open or
 *     clear) — a bad sensor must not open, or silently clear, a
 *     room alert. Once the sensor reads normally again, R4 clears
 *     the fault and the room rules resume.
 *
 * Boundaries are inclusive unless stated: a rise of exactly 3.0 C,
 * held for exactly confirm-readings readings, opens; a rise held for
 * one fewer reading does not; a value exactly on a hysteresis edge
 * counts as back inside; a flatline of exactly 30 opens; a jump of
 * exactly 15.0 C does not (the rule is strictly greater than 15).
 * Every boundary has a unit test in AlertEvaluatorTest. */
public class AlertEvaluator {

    private final AlertParameters params;

    public AlertEvaluator(AlertParameters params) {
        this.params = params;
    }

    /** Live evaluation: R2 + R3 + R4. Never R1. */
    public List<AlertDecision> evaluate(
            SensorKind kind, List<ReadingValue> history, Set<AlertRule> unresolved) {
        List<ReadingValue> readings = sortedCopy(history);
        if (readings.isEmpty()) {
            return List.of();
        }
        List<AlertDecision> decisions = new ArrayList<>();

        boolean fault = isFlatline(kind, readings) || isJump(kind, readings);
        if (fault) {
            if (!unresolved.contains(AlertRule.SENSOR_FAULT)) {
                decisions.add(open(AlertRule.SENSOR_FAULT, AlertSeverity.HIGH,
                        faultMessage(kind, readings)));
            }
            return decisions;
        }
        if (unresolved.contains(AlertRule.SENSOR_FAULT)) {
            decisions.add(clear(AlertRule.SENSOR_FAULT,
                    "Sensor readings are plausible again"));
        }

        evaluateSmartThreshold(kind, readings, unresolved, decisions);
        evaluateRateOfChange(kind, readings, unresolved, decisions);
        return decisions;
    }

    /** R1 NAIVE baseline, for the evaluation replay ONLY. Opens on the
     * first out-of-range reading and clears on the first in-range one.
     * Live ingestion must never call this method; the processor does
     * not reference it. */
    public List<AlertDecision> evaluateNaive(
            SensorKind kind, List<ReadingValue> history, Set<AlertRule> unresolved) {
        List<ReadingValue> readings = sortedCopy(history);
        if (readings.isEmpty()) {
            return List.of();
        }
        ReadingValue latest = readings.get(readings.size() - 1);
        boolean out = isOutOfRange(kind, latest);
        if (out && !unresolved.contains(AlertRule.NAIVE_OUT_OF_RANGE)) {
            return List.of(open(AlertRule.NAIVE_OUT_OF_RANGE, AlertSeverity.LOW,
                    "Naive: " + describe(kind, latest) + " out of range"));
        }
        if (!out && unresolved.contains(AlertRule.NAIVE_OUT_OF_RANGE)) {
            return List.of(clear(AlertRule.NAIVE_OUT_OF_RANGE,
                    "Naive: reading back in range"));
        }
        return List.of();
    }

    private void evaluateSmartThreshold(
            SensorKind kind, List<ReadingValue> readings,
            Set<AlertRule> unresolved, List<AlertDecision> decisions) {
        int outStreak = streakFromEnd(readings, value -> isOutOfRange(kind, value));
        int clearStreak = streakFromEnd(readings, value -> isInClearBand(kind, value));
        if (unresolved.contains(AlertRule.SMART_THRESHOLD)) {
            if (clearStreak >= params.consecutiveClear()) {
                decisions.add(clear(AlertRule.SMART_THRESHOLD,
                        clearStreak + " consecutive readings back inside the range "
                                + "(hysteresis " + hysteresisFor(kind) + ")"));
            }
        } else if (outStreak >= params.consecutiveOutOfRange()) {
            ReadingValue latest = readings.get(readings.size() - 1);
            decisions.add(open(AlertRule.SMART_THRESHOLD, AlertSeverity.MEDIUM,
                    describe(kind, latest) + " out of range for "
                            + outStreak + " consecutive readings"));
        }
    }

    private void evaluateRateOfChange(
            SensorKind kind, List<ReadingValue> readings,
            Set<AlertRule> unresolved, List<AlertDecision> decisions) {
        if (kind != SensorKind.TEMP) {
            return;
        }
        double rise = heldRise(readings);
        if (unresolved.contains(AlertRule.RATE_OF_CHANGE)) {
            if (rise < params.rateMinRiseC()) {
                decisions.add(clear(AlertRule.RATE_OF_CHANGE,
                        String.format("Held temperature rise in the last %d minutes is %.1f C, below %.1f C",
                                params.rateWindowMinutes(), rise, params.rateMinRiseC())));
            }
        } else if (rise >= params.rateMinRiseC()) {
            decisions.add(open(AlertRule.RATE_OF_CHANGE, AlertSeverity.HIGH,
                    String.format("Temperature rose %.1f C within %d minutes and stayed up for %d readings "
                                    + "(possible cooling failure)",
                            rise, params.rateWindowMinutes(), params.confirmReadings())));
        }
    }

    /** The held rise: the minimum of the last confirm-readings values
     * inside the window, minus the minimum of all values in the window.
     * Using the minimum of the recent readings (not the latest) is what
     * makes a one-reading spike invisible: after a blip the minimum of
     * the last 3 readings is still a normal reading, so the rise is
     * near zero. A genuine ramp stays up, so its minimum over the last
     * 3 readings is high and the rise counts. The same measure is used
     * for opening and for clearing, so an alert clears as soon as the
     * held rise falls below the threshold. If fewer than
     * confirm-readings values fall inside the window, all of them are
     * used. */
    private double heldRise(List<ReadingValue> readings) {
        ReadingValue latest = readings.get(readings.size() - 1);
        Instant windowStart = latest.ts().minus(java.time.Duration.ofMinutes(params.rateWindowMinutes()));
        List<ReadingValue> inWindow = readings.stream()
                .filter(value -> !value.ts().isBefore(windowStart))
                .toList();
        double windowMin = inWindow.stream()
                .mapToDouble(ReadingValue::tempC)
                .min()
                .orElse(latest.tempC());
        int confirm = params.confirmReadings();
        List<ReadingValue> recent = inWindow.subList(
                Math.max(0, inWindow.size() - confirm), inWindow.size());
        double confirmedLevel = recent.stream()
                .mapToDouble(ReadingValue::tempC)
                .min()
                .orElse(latest.tempC());
        return confirmedLevel - windowMin;
    }

    /** Flatline: the last N readings carry exactly the same value. */
    private boolean isFlatline(SensorKind kind, List<ReadingValue> readings) {
        int n = params.flatlineReadings();
        if (readings.size() < n) {
            return false;
        }
        List<ReadingValue> tail = readings.subList(readings.size() - n, readings.size());
        Double first = tail.get(0).valueFor(kind);
        return tail.stream().allMatch(value -> Objects.equals(value.valueFor(kind), first));
    }

    /** Implausible jump: strictly more than maxJumpC between the last
     * two readings. Exactly maxJumpC is not a fault. TEMP only: the
     * limit is stated in degrees C. */
    private boolean isJump(SensorKind kind, List<ReadingValue> readings) {
        if (kind != SensorKind.TEMP || readings.size() < 2) {
            return false;
        }
        ReadingValue previous = readings.get(readings.size() - 2);
        ReadingValue latest = readings.get(readings.size() - 1);
        return Math.abs(latest.tempC() - previous.tempC()) > params.maxJumpC();
    }

    private String faultMessage(SensorKind kind, List<ReadingValue> readings) {
        if (isFlatline(kind, readings)) {
            return "Sensor flatline: identical " + describeUnit(kind) + " for "
                    + params.flatlineReadings() + " consecutive readings";
        }
        ReadingValue previous = readings.get(readings.size() - 2);
        ReadingValue latest = readings.get(readings.size() - 1);
        return String.format("Implausible jump: %s changed by %.1f C between consecutive readings",
                describeUnit(kind), Math.abs(latest.tempC() - previous.tempC()));
    }

    private boolean isOutOfRange(SensorKind kind, ReadingValue value) {
        Double measured = value.valueFor(kind);
        if (kind == SensorKind.TEMP) {
            return measured < params.tempMinC() || measured > params.tempMaxC();
        }
        return measured < params.rhMinPct() || measured > params.rhMaxPct();
    }

    /** Inside the limits with the hysteresis margin applied: far enough
     * inside that a wobble on the boundary does not flap the alert.
     * A value exactly on the hysteresis edge counts as inside. */
    private boolean isInClearBand(SensorKind kind, ReadingValue value) {
        Double measured = value.valueFor(kind);
        if (kind == SensorKind.TEMP) {
            return measured >= params.tempMinC() + params.hysteresisTempC()
                    && measured <= params.tempMaxC() - params.hysteresisTempC();
        }
        return measured >= params.rhMinPct() + params.hysteresisRhPct()
                && measured <= params.rhMaxPct() - params.hysteresisRhPct();
    }

    private String hysteresisFor(SensorKind kind) {
        return kind == SensorKind.TEMP
                ? params.hysteresisTempC() + " C"
                : params.hysteresisRhPct() + " %RH";
    }

    private String describe(SensorKind kind, ReadingValue value) {
        if (kind == SensorKind.TEMP) {
            return String.format("Temperature %.1f C", value.tempC());
        }
        return String.format("Humidity %.1f %%RH", value.rhPct());
    }

    private String describeUnit(SensorKind kind) {
        return kind == SensorKind.TEMP ? "temperature" : "humidity";
    }

    /** Consecutive readings from the newest backwards that satisfy the
     * predicate. Stops at the first reading that does not. */
    private int streakFromEnd(List<ReadingValue> readings, Predicate<ReadingValue> predicate) {
        int streak = 0;
        for (int i = readings.size() - 1; i >= 0; i--) {
            if (!predicate.test(readings.get(i))) {
                break;
            }
            streak++;
        }
        return streak;
    }

    private List<ReadingValue> sortedCopy(List<ReadingValue> history) {
        List<ReadingValue> copy = new ArrayList<>(history);
        copy.sort((a, b) -> a.ts().compareTo(b.ts()));
        return copy;
    }

    private AlertDecision open(AlertRule rule, AlertSeverity severity, String message) {
        return new AlertDecision(rule, AlertAction.OPEN, severity, message);
    }

    private AlertDecision clear(AlertRule rule, String message) {
        return new AlertDecision(rule, AlertAction.CLEAR, null, message);
    }
}
