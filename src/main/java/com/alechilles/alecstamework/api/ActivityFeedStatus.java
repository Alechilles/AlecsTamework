package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable live availability view for one Activity API V2 consumer. */
public record ActivityFeedStatus(
        boolean available,
        boolean subscribed,
        /** Last attempted process-local sequence; it is not a durable checkpoint. */
        long lastAttemptedSequence,
        @Nonnull String detail
) {
    public ActivityFeedStatus {
        detail = requireText(detail, "detail");
        if (lastAttemptedSequence < 0L) {
            throw new IllegalArgumentException("lastAttemptedSequence cannot be negative.");
        }
        if (subscribed && !available) {
            throw new IllegalArgumentException("An unavailable feed cannot be subscribed.");
        }
    }

    /** Returns the stable fail-closed status used by unavailable implementations. */
    @Nonnull
    public static ActivityFeedStatus unavailable() {
        return new ActivityFeedStatus(
                false,
                false,
                0L,
                "activity-api-v2-unavailable"
        );
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
