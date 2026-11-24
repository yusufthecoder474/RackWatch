package com.rackwatch.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Instant;

/** Validates @ClockSkew: the value must not be more than
 * toleranceSeconds ahead of the current time. */
public class ClockSkewValidator implements ConstraintValidator<ClockSkew, Instant> {

    private long toleranceSeconds = 60;

    @Override
    public void initialize(ClockSkew annotation) {
        this.toleranceSeconds = annotation.toleranceSeconds();
    }

    @Override
    public boolean isValid(Instant value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // @NotNull handles null
        }
        return !value.isAfter(Instant.now().plusSeconds(toleranceSeconds));
    }
}
