package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Complete server-authoritative revive quote used by UI and integrations. */
public record PaidCommandRevivalQuote(@Nonnull UUID ownerUuid,
                                      @Nonnull String profileId,
                                      @Nonnull String commandFamilyId,
                                      @Nonnull Status status,
                                      long cooldownRemainingMs,
                                      @Nonnull List<PaidCommandRevivalCostQuoteView> costs,
                                      @Nonnull String configRevision,
                                      @Nullable String messageKey,
                                      @Nullable String reason) {
    public PaidCommandRevivalQuote {
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        profileId = requireText(profileId, "profileId");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        status = Objects.requireNonNull(status, "status");
        cooldownRemainingMs = Math.max(0L, cooldownRemainingMs);
        costs = List.copyOf(Objects.requireNonNull(costs, "costs"));
        configRevision = requireText(configRevision, "configRevision");
        messageKey = normalize(messageKey);
        reason = normalize(reason);
    }

    public boolean confirmEnabled() {
        return status == Status.READY && costs.stream().allMatch(PaidCommandRevivalCostQuoteView::satisfied);
    }

    public enum Status { READY, DISABLED, COOLDOWN, INSUFFICIENT_COST, DENIED, UNAVAILABLE }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
