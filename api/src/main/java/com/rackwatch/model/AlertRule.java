package com.rackwatch.model;

/** Which rule opened an alert. NAIVE_OUT_OF_RANGE exists only for the
 * evaluation replay (Part 3): live ingestion never opens it. */
public enum AlertRule {
    NAIVE_OUT_OF_RANGE,
    SMART_THRESHOLD,
    RATE_OF_CHANGE,
    SENSOR_FAULT
}
