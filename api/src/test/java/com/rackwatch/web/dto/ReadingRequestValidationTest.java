package com.rackwatch.web.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The clock-skew rule on ReadingRequest.ts: a timestamp up to 60 s in
 * the future is accepted (the daemon's clock and the API host's clock
 * are never exactly in step), 61 s ahead is rejected. Runs the real
 * Jakarta Validator against the record, so it proves the @ClockSkew
 * annotation is actually on the DTO — a unit test of the validator
 * class alone would not. */
class ReadingRequestValidationTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static ReadingRequest request(Instant ts) {
        return new ReadingRequest("ROOM-A-TEMP-01", ts, 22.5, null);
    }

    @Test
    void nowIsValid() {
        assertTrue(VALIDATOR.validate(request(Instant.now())).isEmpty());
    }

    @Test
    void pastIsValid() {
        assertTrue(VALIDATOR.validate(request(Instant.now().minusSeconds(3600))).isEmpty());
    }

    @Test
    void exactlySixtySecondsAheadIsValid() {
        assertTrue(VALIDATOR.validate(request(Instant.now().plusSeconds(60))).isEmpty());
    }

    @Test
    void sixtyOneSecondsAheadIsRejected() {
        var violations = VALIDATOR.validate(request(Instant.now().plusSeconds(61)));
        assertEquals(1, violations.size());
        var violation = violations.iterator().next();
        assertEquals("ts", violation.getPropertyPath().toString());
        assertTrue(violation.getMessage().contains("60 seconds"), violation.getMessage());
    }

    @Test
    void nullTimestampIsRejected() {
        var violations = VALIDATOR.validate(request(null));
        assertEquals(1, violations.size());
        assertEquals("ts", violations.iterator().next().getPropertyPath().toString());
    }
}
