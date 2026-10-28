package org.sterl.llmpeon.session;

import java.time.Instant;

/** Lightweight session listing entry for the history menu. */
public record SessionSummary(SessionId id, String title, Instant createdAt, Instant updatedAt, int messageCount) {
}
