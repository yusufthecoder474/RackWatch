package com.rackwatch.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rackwatch.model.AlertRule;
import com.rackwatch.model.SensorKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Boundary tests for AlertEvaluator. Every rule is tested at its exact
 * boundary, not just in the middle: exactly N readings, the hysteresis
 * edge, a jump of exactly 15.0 C, a flatline of exactly 30 readings.
 * The evaluator is pure, so these tests need no Spring and no database. */
class AlertEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");
    private static final Set<AlertRule> NONE = Set.of();
    private static final Set<AlertRule> SMART_OPEN = Set.of(AlertRule.SMART_THRESHOLD);
    private static final Set<AlertRule> RATE_OPEN = Set.of(AlertRule.RATE_OF_CHANGE);
    private static final Set<AlertRule> FAULT_OPEN = Set.of(AlertRule.SENSOR_FAULT);
    private static final Set<AlertRule> NAIVE_OPEN = Set.of(AlertRule.NAIVE_OUT_OF_RANGE);

    private final AlertEvaluator evaluator = new AlertEvaluator(AlertParameters.defaults());

    @Test
    void smartOpensAfterExactlyThreeOutOfRange() {
        assertNoOpen(evaluator.evaluate(SensorKind.TEMP, temp(28.0, 28.0), NONE));
        List<AlertDecision> decisions = evaluator.evaluate(SensorKind.TEMP, temp(28.0, 28.0, 28.0), NONE);
        assertSingleOpen(decisions, AlertRule.SMART_THRESHOLD);
        assertEquals(com.rackwatch.model.AlertSeverity.MEDIUM, decisions.get(0).severity());
    }

    @Test
    void smartOpensForHumidityOutOfRange() {
        assertSingleOpen(
                evaluator.evaluate(SensorKind.HUMIDITY, rh(65.0, 65.0, 65.0), NONE),
                AlertRule.SMART_THRESHOLD);
        assertSingleOpen(
                evaluator.evaluate(SensorKind.HUMIDITY, rh(38.0, 38.0, 38.0), NONE),
                AlertRule.SMART_THRESHOLD);
    }

    @Test
    void smartClearsAfterExactlyThreeInClearBand() {
        List<ReadingValue> twoBack = temp(28.0, 28.0, 28.0, 25.0, 25.0);
        assertNoDecisions(evaluator.evaluate(SensorKind.TEMP, twoBack, SMART_OPEN));
        List<ReadingValue> threeBack = temp(28.0, 28.0, 28.0, 25.0, 25.0, 25.0);
        assertSingleClear(evaluator.evaluate(SensorKind.TEMP, threeBack, SMART_OPEN),
                AlertRule.SMART_THRESHOLD);
    }

    @Test
    void inRangeButInsideHysteresisDoesNotClear() {
        // 26.5 C is inside the 18-27 C limits but within the 1 C
        // hysteresis margin (clear band tops out at 26.0 C), so an open
        // alert must NOT clear on it.
        List<ReadingValue> history = temp(28.0, 28.0, 28.0, 26.5, 26.5, 26.5);
        assertNoDecisions(evaluator.evaluate(SensorKind.TEMP, history, SMART_OPEN));
    }

    @Test
    void hysteresisEdgeCountsAsBackInside() {
        // Exactly on the edge: 26.0 = 27 - 1.0 (upper) and 19.0 = 18 + 1.0
        // (lower). Both count as back inside and clear the alert.
        List<ReadingValue> upper = temp(28.0, 28.0, 28.0, 26.0, 26.0, 26.0);
        assertSingleClear(evaluator.evaluate(SensorKind.TEMP, upper, SMART_OPEN),
                AlertRule.SMART_THRESHOLD);
        List<ReadingValue> lower = temp(17.0, 17.0, 17.0, 19.0, 19.0, 19.0);
        assertSingleClear(evaluator.evaluate(SensorKind.TEMP, lower, SMART_OPEN),
                AlertRule.SMART_THRESHOLD);
    }

    @Test
    void rateOpensOnRiseOfExactlyThreeDegreesHeld() {
        // The rise must be held for confirm-readings (3) readings: the
        // last three values are all 23.0, the window minimum is 20.0,
        // so the held rise is exactly 3.0 C and the rule opens.
        List<AlertDecision> decisions = evaluator.evaluate(
                SensorKind.TEMP, temp(20.0, 21.0, 22.0, 23.0, 23.0, 23.0), NONE);
        assertSingleOpen(decisions, AlertRule.RATE_OF_CHANGE);
        assertEquals(com.rackwatch.model.AlertSeverity.HIGH, decisions.get(0).severity());
    }

    @Test
    void rateDoesNotOpenOnRiseBelowThreeDegreesHeld() {
        // Held at 22.9 C, the held rise is 2.9 C — just below the
        // threshold, so nothing opens.
        assertNoOpen(evaluator.evaluate(
                SensorKind.TEMP, temp(20.0, 21.0, 22.0, 22.9, 22.9, 22.9), NONE));
    }

    @Test
    void rateDoesNotOpenWhenRiseHeldForOnlyTwoReadings() {
        // Five readings: the rise to 23.0 C has been held for only two
        // readings, so the minimum of the last three is still 22.0 C
        // and the held rise is 2.0 C. One more held reading opens it.
        assertNoOpen(evaluator.evaluate(
                SensorKind.TEMP, temp(20.0, 21.0, 22.0, 23.0, 23.0), NONE));
        assertSingleOpen(evaluator.evaluate(
                SensorKind.TEMP, temp(20.0, 21.0, 22.0, 23.0, 23.0, 23.0), NONE),
                AlertRule.RATE_OF_CHANGE);
    }

    @Test
    void rateDoesNotOpenOnSingleReadingSpike() {
        // One blip to 28.5 C: the minimum of the last three readings
        // is still 22.0 C, so the held rise is 0 C. The old
        // latest-minus-minimum measure opened on this exact shape.
        assertNoDecisions(evaluator.evaluate(SensorKind.TEMP, temp(22.0, 22.0, 22.0, 28.5), NONE));
    }

    @Test
    void blipFollowedByRecoveryOpensNothing() {
        // Live-ingestion simulation: evaluate after every reading, as
        // the processor does. A single blip to 28.5 C and the recovery
        // after it must never open anything — the naive baseline would
        // have opened on the blip, which is exactly the false alarm
        // the smart rules exist to avoid.
        double[] values = {22.0, 22.1, 28.5, 22.0, 22.2, 22.0};
        List<ReadingValue> history = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            history.add(new ReadingValue(T0.plusSeconds(60L * i), values[i], null));
            assertNoDecisions(evaluator.evaluate(SensorKind.TEMP, history, NONE));
        }
        // And the naive baseline WOULD have opened on the blip, proving
        // the test data actually exercises the difference.
        assertSingleOpen(
                evaluator.evaluateNaive(SensorKind.TEMP, temp(22.0, 22.1, 28.5), NONE),
                AlertRule.NAIVE_OUT_OF_RANGE);
    }

    @Test
    void rateIgnoresReadingsOlderThanTheWindow() {
        // A 3 C rise spread over 11 minutes is not "within 10 minutes":
        // the low reading has aged out of the window.
        List<ReadingValue> history = List.of(
                new ReadingValue(T0, 20.0, null),
                new ReadingValue(T0.plusSeconds(11 * 60), 23.0, null));
        assertNoOpen(evaluator.evaluate(SensorKind.TEMP, history, NONE));
    }

    @Test
    void rateIsTempOnly() {
        assertNoDecisions(evaluator.evaluate(SensorKind.HUMIDITY, rh(40.0, 45.0, 50.0, 55.0), NONE));
    }

    @Test
    void rateClearsWhenTheRiseFades() {
        List<ReadingValue> history = new ArrayList<>();
        // A held rise first: 20 -> 23 C, held for three readings, so
        // the alert would have opened here.
        double[] rise = {20.0, 21.0, 22.0, 23.0, 23.0, 23.0};
        for (int i = 0; i < rise.length; i++) {
            history.add(new ReadingValue(T0.plusSeconds(60L * i), rise[i], null));
        }
        assertSingleOpen(evaluator.evaluate(SensorKind.TEMP, history, NONE),
                AlertRule.RATE_OF_CHANGE);
        // Eleven more minutes of plateau readings, all near 23 C. The
        // 20 C reading ages out of the 10-minute window, so the held
        // rise falls below 3 C and the alert clears. The same held
        // measure is used for opening and clearing.
        double[] plateau = {23.0, 23.1, 22.9, 23.0, 23.2, 22.8, 23.0, 23.1, 22.9, 23.0, 23.0};
        for (int i = 0; i < plateau.length; i++) {
            history.add(new ReadingValue(T0.plusSeconds(360 + 60L * i), plateau[i], null));
        }
        assertSingleClear(evaluator.evaluate(SensorKind.TEMP, history, RATE_OPEN),
                AlertRule.RATE_OF_CHANGE);
    }

    @Test
    void flatlineOpensAtExactlyThirtyReadings() {
        assertNoDecisions(evaluator.evaluate(SensorKind.TEMP, repeatTemp(22.0, 29), NONE));
        List<AlertDecision> decisions =
                evaluator.evaluate(SensorKind.TEMP, repeatTemp(22.0, 30), NONE);
        assertSingleOpen(decisions, AlertRule.SENSOR_FAULT);
        assertEquals(com.rackwatch.model.AlertSeverity.HIGH, decisions.get(0).severity());
    }

    @Test
    void jumpOfExactlyFifteenDoesNotOpen() {
        // The rule is strictly greater than 15 C: exactly 15.0 is not
        // a fault, in either direction.
        assertNoDecisions(evaluator.evaluate(SensorKind.TEMP, temp(22.0, 37.0), NONE));
        assertNoDecisions(evaluator.evaluate(SensorKind.TEMP, temp(37.0, 22.0), NONE));
        assertSingleOpen(evaluator.evaluate(SensorKind.TEMP, temp(22.0, 37.1), NONE),
                AlertRule.SENSOR_FAULT);
    }

    @Test
    void faultSuppressesRoomRules() {
        // Thirty readings stuck at 35 C: out of range AND flatlined.
        // Only the fault opens — the bad sensor must not also open a
        // (wrong) hot-room alert.
        List<AlertDecision> decisions =
                evaluator.evaluate(SensorKind.TEMP, repeatTemp(35.0, 30), NONE);
        assertEquals(1, decisions.size());
        assertSingleOpen(decisions, AlertRule.SENSOR_FAULT);
    }

    @Test
    void noNewDecisionsWhileFaultIsAlreadyOpen() {
        List<AlertDecision> decisions =
                evaluator.evaluate(SensorKind.TEMP, repeatTemp(35.0, 30), FAULT_OPEN);
        assertNoDecisions(decisions);
    }

    @Test
    void faultClearsWhenTheSensorRecovers() {
        List<ReadingValue> history = new ArrayList<>(repeatTemp(22.5, 30));
        history.add(new ReadingValue(T0.plusSeconds(30 * 60), 22.0, null));
        history.add(new ReadingValue(T0.plusSeconds(31 * 60), 23.0, null));
        history.add(new ReadingValue(T0.plusSeconds(32 * 60), 22.0, null));
        List<AlertDecision> decisions = evaluator.evaluate(SensorKind.TEMP, history, FAULT_OPEN);
        assertSingleClear(decisions, AlertRule.SENSOR_FAULT);
    }

    @Test
    void naiveOpensOnFirstBlipAndClearsOnNextInRangeReading() {
        List<AlertDecision> opened =
                evaluator.evaluateNaive(SensorKind.TEMP, temp(22.0, 28.5), NONE);
        assertSingleOpen(opened, AlertRule.NAIVE_OUT_OF_RANGE);
        List<AlertDecision> cleared = evaluator.evaluateNaive(
                SensorKind.TEMP, temp(22.0, 28.5, 22.0), NAIVE_OPEN);
        assertSingleClear(cleared, AlertRule.NAIVE_OUT_OF_RANGE);
    }

    @Test
    void liveEvaluationNeverRunsTheNaiveRule() {
        // One blip: naive would open, but evaluate() (the live path)
        // needs three consecutive readings and stays silent.
        assertNoDecisions(evaluator.evaluate(SensorKind.TEMP, temp(22.0, 28.5), NONE));
    }

    @Test
    void emptyHistoryProducesNoDecisions() {
        assertNoDecisions(evaluator.evaluate(SensorKind.TEMP, List.of(), NONE));
        assertNoDecisions(evaluator.evaluateNaive(SensorKind.TEMP, List.of(), NONE));
    }

    // -- helpers ---------------------------------------------------------

    private static List<ReadingValue> temp(double... values) {
        List<ReadingValue> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            out.add(new ReadingValue(T0.plusSeconds(60L * i), values[i], null));
        }
        return out;
    }

    private static List<ReadingValue> rh(double... values) {
        List<ReadingValue> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            out.add(new ReadingValue(T0.plusSeconds(60L * i), null, values[i]));
        }
        return out;
    }

    private static List<ReadingValue> repeatTemp(double value, int count) {
        List<ReadingValue> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new ReadingValue(T0.plusSeconds(60L * i), value, null));
        }
        return out;
    }

    private static void assertSingleOpen(List<AlertDecision> decisions, AlertRule rule) {
        assertEquals(1, decisions.size(), "expected exactly one decision, got: " + decisions);
        assertEquals(AlertAction.OPEN, decisions.get(0).action());
        assertEquals(rule, decisions.get(0).rule());
    }

    private static void assertSingleClear(List<AlertDecision> decisions, AlertRule rule) {
        assertEquals(1, decisions.size(), "expected exactly one decision, got: " + decisions);
        assertEquals(AlertAction.CLEAR, decisions.get(0).action());
        assertEquals(rule, decisions.get(0).rule());
    }

    private static void assertNoOpen(List<AlertDecision> decisions) {
        assertTrue(decisions.stream().noneMatch(d -> d.action() == AlertAction.OPEN),
                "expected no OPEN decision, got: " + decisions);
    }

    private static void assertNoDecisions(List<AlertDecision> decisions) {
        assertTrue(decisions.isEmpty(), "expected no decisions, got: " + decisions);
    }
}
