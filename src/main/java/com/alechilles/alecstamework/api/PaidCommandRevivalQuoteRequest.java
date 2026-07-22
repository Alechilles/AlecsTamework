package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Server-side request for the current paid command-revival quote. */
public record PaidCommandRevivalQuoteRequest(@Nonnull UUID ownerUuid,
                                              @Nonnull String profileId,
                                              @Nonnull String commandFamilyId) {
    public PaidCommandRevivalQuoteRequest {
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
