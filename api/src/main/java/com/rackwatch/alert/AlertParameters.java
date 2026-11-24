package com.rackwatch.alert;

/** Every number the alert engine uses, as one plain value object. The
 * evaluator takes this — not Spring, not the properties class — so it
 * stays a pure class that a unit test can construct by hand. Values
 * come from RackwatchProperties (application.yml) in production and
 * from the test itself in tests. */
public record AlertParameters(
        double tempMinC,
        double tempMaxC,
        double rhMinPct,
        double rhMaxPct,
        int consecutiveOutOfRange,
        int consecutiveClear,
        double hysteresisTempC,
        double hysteresisRhPct,
        int rateWindowMinutes,
        double rateMinRiseC,
        int confirmReadings,
        int flatlineReadings,
        double maxJumpC) {

    public AlertParameters {
        if (tempMinC >= tempMaxC) {
            throw new IllegalArgumentException("tempMinC must be below tempMaxC");
        }
        if (rhMinPct >= rhMaxPct) {
            throw new IllegalArgumentException("rhMinPct must be below rhMaxPct");
        }
        if (consecutiveOutOfRange < 1 || consecutiveClear < 1) {
            throw new IllegalArgumentException("Consecutive counts must be at least 1");
        }
        if (hysteresisTempC < 0 || hysteresisRhPct < 0) {
            throw new IllegalArgumentException("Hysteresis margins must not be negative");
        }
        if (rateWindowMinutes < 1 || rateMinRiseC <= 0) {
            throw new IllegalArgumentException("Rate rule needs a positive window and rise");
        }
        if (confirmReadings < 1) {
            throw new IllegalArgumentException("confirmReadings must be at least 1");
        }
        if (flatlineReadings < 2 || maxJumpC <= 0) {
            throw new IllegalArgumentException("Health rule needs flatline >= 2 and a positive jump");
        }
    }

    /** The defaults from application.yml, for tests that want the
     * production numbers without loading Spring. */
    public static AlertParameters defaults() {
        return new AlertParameters(18.0, 27.0, 40.0, 60.0, 3, 3, 1.0, 3.0, 10, 3.0, 3, 30, 15.0);
    }
}
