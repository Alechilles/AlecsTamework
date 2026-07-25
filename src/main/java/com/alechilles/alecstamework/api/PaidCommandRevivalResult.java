package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Terminal or recoverable result of an idempotent paid command revival. */
public record PaidCommandRevivalResult(@Nullable UUID operationId,
                                       @Nonnull Status status,
                                       @Nonnull String profileId,
                                       @Nonnull List<ItemCostComponentView> exactCost,
                                       @Nullable String reason,
                                       boolean recovered) {
    public PaidCommandRevivalResult {
        status = Objects.requireNonNull(status, "status");
        profileId = requireText(profileId, "profileId");
        exactCost = List.copyOf(Objects.requireNonNull(exactCost, "exactCost"));
        reason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public boolean succeeded() {
        return status == Status.REVIVED || status == Status.ALREADY_REVIVED;
    }

    public enum Status {
        REVIVED,
        ALREADY_REVIVED,
        DENIED,
        INSUFFICIENT_COST,
        COOLDOWN,
        CONFLICT,
        REFUND_PENDING,
        REFUNDED,
        RECOVERY_PENDING,
        UNAVAILABLE
    }

    public static PaidCommandRevivalResult unavailable(String profileId, String reason) {
        return new PaidCommandRevivalResult(null, Status.UNAVAILABLE, profileId, List.of(), reason, false);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
