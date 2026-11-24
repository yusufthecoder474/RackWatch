package com.rackwatch.alert;

import com.rackwatch.model.AlertRule;
import com.rackwatch.model.AlertSeverity;

/** One decision from the evaluator: open or clear an alert for a rule,
 * with its severity and a human-readable reason. The evaluator never
 * touches the database; the processor turns decisions into inserts
 * and updates. */
public record AlertDecision(
        AlertRule rule,
        AlertAction action,
        AlertSeverity severity,
        String message) {
}
