package com.rackwatch.model;

/** What a sensor measures. A sensor measures exactly one thing, which is
 * why a reading carries exactly one value (see Reading). */
public enum SensorKind {
    TEMP,
    HUMIDITY
}
