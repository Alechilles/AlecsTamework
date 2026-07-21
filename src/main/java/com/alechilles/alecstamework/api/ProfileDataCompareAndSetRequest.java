package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

/**
 * Revision-fenced, idempotent profile-data mutation. Revision {@value #MISSING_REVISION} means the
 * key must not exist; existing values begin at revision one.
 */
public record ProfileDataCompareAndSetRequest(@Nonnull String profileId,
                                              @Nonnull String namespace,
                                              @Nonnull String key,
                                              long expectedRevision,
                                              @Nonnull String idempotencyKey,
                                              @Nonnull String jsonPayload) {
    public static final long MISSING_REVISION = 0L;

    public ProfileDataCompareAndSetRequest {
        profileId = ProfileDataValidation.requireText(profileId, "profileId", 256);
        namespace = ProfileDataValidation.requireText(namespace, "namespace", 128);
        key = ProfileDataValidation.requireText(key, "key", 256);
        idempotencyKey = ProfileDataValidation.requireText(idempotencyKey, "idempotencyKey", 256);
        jsonPayload = ProfileDataValidation.requireJson(jsonPayload);
        if (expectedRevision < MISSING_REVISION || expectedRevision == Long.MAX_VALUE) {
            throw new IllegalArgumentException("expectedRevision must be between 0 and Long.MAX_VALUE - 1.");
        }
    }
}
