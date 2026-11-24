package com.rackwatch.alert;

import com.rackwatch.model.SensorKind;
import java.time.Instant;

/** One reading as the evaluator sees it: a timestamp and the value its
 * sensor measures. Deliberately not the JPA entity — the evaluator is
 * pure and must work on replayed CSV rows exactly as it works on rows
 * read from the database. Exactly one of tempC / rhPct is non-null. */
public record ReadingValue(Instant ts, Double tempC, Double rhPct) {

    /** The value this reading carries for a sensor of the given kind. */
    public Double valueFor(SensorKind kind) {
        return kind == SensorKind.TEMP ? tempC : rhPct;
    }
}
