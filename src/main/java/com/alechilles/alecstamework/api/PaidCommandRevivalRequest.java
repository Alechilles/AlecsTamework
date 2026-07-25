package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Namespaced idempotent paid-revival commit request. */
public record PaidCommandRevivalRequest(@Nonnull String callerNamespace,
                                        @Nonnull String idempotencyKey,
                                        @Nonnull UUID ownerUuid,
                                        @Nonnull String profileId,
                                        @Nonnull String commandFamilyId) {
    public PaidCommandRevivalRequest {
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        profileId = requireText(profileId, "profileId");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
