package com.rackwatch.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of POST /api/v1/alerts/{id}/ack. The name is recorded in
 * audit_log, so it is required — an acknowledgement with no name is
 * not an audit trail. */
public record AckRequest(@NotBlank String ackedBy) {
}
