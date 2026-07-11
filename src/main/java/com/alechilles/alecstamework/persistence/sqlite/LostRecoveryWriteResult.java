package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Value committed by a tracked lost-envelope upsert. */
public record LostRecoveryWriteResult(@Nonnull String profileId,
                                      @Nonnull UUID sourceNpcUuid,
                                      int formatVersion,
                                      boolean fullSnapshotStored,
                                      @Nullable String fullSnapshotSha256) {
    public LostRecoveryWriteResult {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId is required");
        }
        profileId = profileId.trim();
        Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        if (formatVersion <= 0) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
    }
}
