package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Post-commit notification emitted exactly once for a successfully paid command revival. */
public record PaidCommandRevivedEvent(@Nonnull UUID operationId,
                                      @Nonnull String callerNamespace,
                                      @Nonnull String idempotencyKey,
                                      @Nonnull UUID ownerUuid,
                                      @Nonnull String profileId,
                                      @Nonnull String commandFamilyId,
                                      @Nonnull List<ItemCostComponentView> exactCost,
                                      boolean recovered,
                                      long revivedAtMs,
                                      long emittedAtMs) implements TameworkEvent {
    public PaidCommandRevivedEvent {
        operationId = Objects.requireNonNull(operationId, "operationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        profileId = requireText(profileId, "profileId");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        exactCost = List.copyOf(Objects.requireNonNull(exactCost, "exactCost"));
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
