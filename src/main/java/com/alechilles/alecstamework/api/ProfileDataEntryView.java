package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

/** Immutable, revisioned view of one namespaced profile-data value. */
public record ProfileDataEntryView(@Nonnull String profileId,
                                   @Nonnull String namespace,
                                   @Nonnull String key,
                                   long revision,
                                   @Nonnull String jsonPayload,
                                   long updatedAtMs) {
    public ProfileDataEntryView {
        profileId = ProfileDataValidation.requireText(profileId, "profileId", 256);
        namespace = ProfileDataValidation.requireText(namespace, "namespace", 128);
        key = ProfileDataValidation.requireText(key, "key", 256);
        jsonPayload = ProfileDataValidation.requireJson(jsonPayload);
        if (revision < 1L) {
            throw new IllegalArgumentException("revision must be positive for an existing value.");
        }
        // Signed Hytale world-time timestamps are valid; zero is not overloaded as a bound.
    }
}
