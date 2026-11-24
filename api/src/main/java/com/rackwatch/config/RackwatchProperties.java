package com.rackwatch.config;

import com.rackwatch.alert.AlertParameters;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All RackWatch settings from application.yml, bound and validated at
 * startup. apiKey has no default anywhere: if RACKWATCH_API_KEY is unset
 * the application fails fast instead of running unauthenticated. */
@Validated
@ConfigurationProperties(prefix = "rackwatch")
public record RackwatchProperties(
        @NotBlank String apiKey,
        Security security,
        Limits limits,
        Ingestion ingestion,
        Alerts alerts) {

    /** The API-key filter's public list. Matched EXACTLY (String equals),
     * never by prefix: /actuator/health is public, /actuator/health/anything
     * is not. */
    public record Security(List<String> publicPaths) {
    }

    /** ASHRAE-style defaults (18-27 C, 40-60 %RH). Configurable because
     * every site is different; confirm them for your site. */
    public record Limits(
            double tempMinC,
            double tempMaxC,
            double rhMinPct,
            double rhMaxPct) {
    }

    public record Ingestion(
            @Positive int maxBatchSize,
            @Positive long maxRequestBytes) {
    }

    /** Alert-rule parameters (rackwatch.alerts.*). naive.enabled is a
     * declaration, not a switch the live code reads: R1 runs only in
     * the evaluation replay (AlertEvaluator.evaluateNaive), never in
     * live ingestion, whatever this flag says. */
    public record Alerts(
            Naive naive,
            Smart smart,
            Rate rate,
            Health health) {

        public record Naive(boolean enabled) {
        }

        public record Smart(
                @Positive int consecutiveOutOfRange,
                @Positive int consecutiveClear,
                double hysteresisTempC,
                double hysteresisRhPct) {
        }

        public record Rate(
                @Positive int windowMinutes,
                @Positive double minRiseC,
                @Positive int confirmReadings) {
        }

        public record Health(
                @Positive int flatlineReadings,
                @Positive double maxJumpC) {
        }
    }

    /** The pure evaluator's parameters, built from the bound config.
     * This is the only place Spring config meets the evaluator. */
    public AlertParameters toAlertParameters() {
        return new AlertParameters(
                limits.tempMinC(),
                limits.tempMaxC(),
                limits.rhMinPct(),
                limits.rhMaxPct(),
                alerts.smart().consecutiveOutOfRange(),
                alerts.smart().consecutiveClear(),
                alerts.smart().hysteresisTempC(),
                alerts.smart().hysteresisRhPct(),
                alerts.rate().windowMinutes(),
                alerts.rate().minRiseC(),
                alerts.rate().confirmReadings(),
                alerts.health().flatlineReadings(),
                alerts.health().maxJumpC());
    }
}
