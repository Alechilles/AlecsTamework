package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable readiness status for one required managed-content profile. */
public record RequiredContentProfileStatus(
        @Nonnull String profileId,
        boolean available,
        @Nonnull String providerId,
        int providerContractVersion,
        long configRevision,
        @Nonnull String detail
) {
    public RequiredContentProfileStatus {
        profileId = requireText(profileId, "profileId");
        providerId = providerId == null ? "" : providerId.trim();
        if (providerContractVersion < 0 || configRevision < 0) {
            throw new IllegalArgumentException("Profile readiness revisions cannot be negative");
        }
        detail = requireText(detail, "detail");
    }

    @Nonnull
    public static RequiredContentProfileStatus unavailable(
            @Nonnull String profileId,
            @Nonnull String detail
    ) {
        return new RequiredContentProfileStatus(
                profileId,
                false,
                "",
                0,
                0,
                detail
        );
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
