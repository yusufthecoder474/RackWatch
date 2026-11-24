package com.rackwatch.web.dto;

import java.time.Instant;
import java.util.List;

/** The one JSON error shape every endpoint and filter returns.
 * "details" carries per-field validation failures; it is null for
 * errors that have none. Internal messages and stack traces are never
 * included. */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<String> details) {
}
