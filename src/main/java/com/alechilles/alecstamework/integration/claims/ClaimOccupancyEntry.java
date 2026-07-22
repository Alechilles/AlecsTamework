package com.alechilles.alecstamework.integration.claims;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable claim-occupancy projection for one canonical companion profile.
 *
 * <p>A physical coordinate may remain attached to a dormant entry as recovery context. Only an
 * owned {@link CompanionLifecycleState#ACTIVE ACTIVE} or
 * {@link CompanionLifecycleState#UNLOADED UNLOADED}, or
 * {@link CompanionLifecycleState#STORING STORING} entry consumes claim occupancy.</p>
 */
public record ClaimOccupancyEntry(@Nonnull String profileId,
                                  @Nullable UUID ownerId,
                                  @Nonnull CompanionLifecycleState lifecycleState,
                                  @Nullable ClaimChunkCoordinate physicalChunk,
                                  long revision) {
    public ClaimOccupancyEntry {
        profileId = normalizeProfileId(profileId);
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        if (revision < 0L) {
            throw new IllegalArgumentException("Claim occupancy revisions cannot be negative.");
        }
    }

    public boolean occupiesClaim() {
        return ownerId != null
                && physicalChunk != null
                && (lifecycleState == CompanionLifecycleState.ACTIVE
                || lifecycleState == CompanionLifecycleState.UNLOADED
                || lifecycleState == CompanionLifecycleState.STORING);
    }

    @Nonnull
    static String normalizeProfileId(@Nonnull String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("A canonical profile ID is required.");
        }
        return profileId.trim();
    }
}
