package com.alechilles.alecstamework.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Immutable identity and ordering data shared by every activity payload. */
public record ActivityHeader(
        @Nonnull UUID operationId,
        long sequence,
        @Nonnull String actionId,
        @Nonnull Instant occurredAt
) {
    public ActivityHeader {
        operationId = Objects.requireNonNull(operationId, "operationId");
        actionId = requireNamespacedText(actionId, "actionId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence cannot be negative.");
        }
    }

    /** Creates a producer header before the feed assigns its process-local sequence. */
    public ActivityHeader(
            @Nonnull UUID operationId,
            @Nonnull String actionId,
            @Nonnull Instant occurredAt
    ) {
        this(operationId, 0L, actionId, occurredAt);
    }

    /** Returns this header with a feed-assigned sequence. */
    @Nonnull
    public ActivityHeader withSequence(long nextSequence) {
        return new ActivityHeader(operationId, nextSequence, actionId, occurredAt);
    }

    static String requireNamespacedText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        int separator = normalized.indexOf(':');
        if (separator <= 0 || separator == normalized.length() - 1
                || normalized.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException(
                    field + " must be a namespaced action ID."
            );
        }
        return normalized;
    }
}
