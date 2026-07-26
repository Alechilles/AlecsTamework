package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Adapter-neutral immutable bonded storage records. */
public final class BondedCompanionRecord {
    private BondedCompanionRecord() {}

    public enum ProjectionState { PENDING, LIVE, REMOVE_PENDING }
    public enum CleanupTarget { SOURCE, PROJECTION }
    public enum CleanupState { PENDING, COMPLETED, ABANDONED }

    /** Complete durable companion profile. */
    public record Profile(
            @Nonnull String profileId, @Nonnull UUID ownerUuid,
            @Nonnull String rosterId, @Nonnull String familyId,
            @Nonnull String roleId, @Nonnull BondedCompanionState state,
            long revision, @Nonnull BondedCompanionPayload snapshot,
            long createdAtMs, long updatedAtMs,
            @Nonnull Map<String, String> policy,
            @Nullable String displayName, @Nullable String species,
            @Nullable String gender, @Nullable Long diedAtMs,
            long reviveCooldownUntilMs, long reviveCount,
            @Nullable String quarantineReason, @Nullable Long quarantinedAtMs
    ) {
        public Profile {
            profileId = text(profileId, "profileId");
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            rosterId = text(rosterId, "rosterId");
            familyId = text(familyId, "familyId");
            roleId = text(roleId, "roleId");
            state = Objects.requireNonNull(state, "state");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            policy = Map.copyOf(Objects.requireNonNull(policy, "policy"));
            displayName = optional(displayName); species = optional(species);
            gender = optional(gender); quarantineReason = optional(quarantineReason);
            if (revision < 0 || reviveCount < 0) throw new IllegalArgumentException("negative counter");
            if ((quarantineReason == null) != (quarantinedAtMs == null))
                throw new IllegalArgumentException("incomplete quarantine metadata");
        }
    }

    /** Exact live projection lease. */
    public record Lease(
            @Nonnull String profileId, @Nonnull String leaseToken,
            @Nonnull UUID liveNpcUuid, @Nonnull String worldKey,
            long startedAtMs, long expiresAtMs,
            @Nonnull ProjectionState projectionState
    ) {
        public Lease {
            profileId = text(profileId, "profileId"); leaseToken = text(leaseToken, "leaseToken");
            liveNpcUuid = Objects.requireNonNull(liveNpcUuid, "liveNpcUuid");
            worldKey = text(worldKey, "worldKey");
            projectionState = Objects.requireNonNull(projectionState, "projectionState");
            if (expiresAtMs != 0 && expiresAtMs < startedAtMs)
                throw new IllegalArgumentException("lease expiry precedes start");
        }
    }

    /** Bounded cleanup intent. */
    public record Cleanup(
            @Nonnull String cleanupId, @Nonnull UUID ownerUuid,
            @Nonnull String rosterId, @Nonnull String profileId,
            @Nullable String leaseToken, @Nonnull CleanupTarget target,
            @Nonnull UUID targetNpcUuid, @Nonnull String reason,
            @Nonnull CleanupState state, int attemptCount,
            long nextAttemptAtMs, long createdAtMs, long retainedUntilMs
    ) {
        public Cleanup {
            cleanupId = text(cleanupId, "cleanupId"); ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            rosterId = text(rosterId, "rosterId"); profileId = text(profileId, "profileId");
            leaseToken = optional(leaseToken); target = Objects.requireNonNull(target, "target");
            targetNpcUuid = Objects.requireNonNull(targetNpcUuid, "targetNpcUuid");
            reason = text(reason, "reason"); state = Objects.requireNonNull(state, "state");
            if (attemptCount < 0) throw new IllegalArgumentException("negative attempt count");
            if (retainedUntilMs == 0) throw new IllegalArgumentException("retainedUntilMs must be bounded");
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
