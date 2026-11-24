package com.rackwatch.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A timestamp may be up to {@code toleranceSeconds} in the future.
 * The daemon stamps readings at sampling time on its own clock, and
 * the API host's clock is never exactly in step with it — a reading
 * stamped a few seconds "in the future" is clock skew, not bad data.
 * The strict @PastOrPresent rejected those readings; this allows 60 s
 * of skew and still rejects anything further ahead. Null is valid here
 * (a separate @NotNull rejects it) — that is the standard Jakarta
 * Validation rule for a null value. */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ClockSkewValidator.class)
@Documented
public @interface ClockSkew {

    String message() default "Timestamp must not be more than 60 seconds in the future";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** How far into the future a timestamp may be, in seconds. */
    long toleranceSeconds() default 60;
}
