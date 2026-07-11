package com.alechilles.alecstamework.npc.breeding;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Immutable identity snapshot for one parent when a breeding job is admitted.
 *
 * <p>The entity UUID identifies the current live incarnation while the profile ID prevents an
 * aliased incarnation of the same companion from entering another active job.
 */
public record BreedingParentIdentity(@Nonnull UUID entityUuid, @Nonnull String profileId) {
    public BreedingParentIdentity {
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(profileId, "profileId");
        profileId = profileId.trim();
        if (profileId.isEmpty()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
    }
}
