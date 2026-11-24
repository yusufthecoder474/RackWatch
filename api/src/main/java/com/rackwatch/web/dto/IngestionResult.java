package com.rackwatch.web.dto;

/** Result of POST /api/v1/readings. "skipped" counts readings that were
 * already stored (same sensor and timestamp) and ignored by
 * ON CONFLICT DO NOTHING — not errors. */
public record IngestionResult(int totalReceived, int inserted, int skipped) {
}
