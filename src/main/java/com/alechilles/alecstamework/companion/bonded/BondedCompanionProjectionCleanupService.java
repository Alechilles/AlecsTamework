package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Replays bounded physical cleanup without ever widening an exact target. */
public final class BondedCompanionProjectionCleanupService {
    private final WorldGateway world;

    public BondedCompanionProjectionCleanupService(@Nonnull WorldGateway world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    /** Attempts one exact cleanup from stable IDs on the owning world thread. */
    @Nonnull
    public Outcome recover(@Nonnull CleanupIntent intent) {
        Objects.requireNonNull(intent, "intent");
        ObservedTarget observed = world.find(intent.targetNpcUuid());
        if (observed == null) {
            return Outcome.ALREADY_MISSING;
        }
        if (intent.target() == Target.PROJECTION
                && !projectionMatches(intent, observed)) {
            return Outcome.IDENTITY_MISMATCH;
        }
        return world.remove(intent.targetNpcUuid())
                ? Outcome.REMOVED : Outcome.RETRY_REQUIRED;
    }

    private boolean projectionMatches(CleanupIntent intent, ObservedTarget observed) {
        TameworkProjectionIdentityComponent marker = observed.marker();
        return marker != null
                && marker.isBondedCompanion()
                && intent.profileId().equals(marker.getProfileId())
                && intent.leaseToken().equals(marker.getBondedLeaseToken())
                && intent.targetNpcUuid().equals(observed.npcUuid());
    }

    /** World implementations resolve the UUID and remove through a command buffer or world callback. */
    public interface WorldGateway {
        @Nullable ObservedTarget find(@Nonnull UUID targetNpcUuid);

        boolean remove(@Nonnull UUID targetNpcUuid);
    }

    public enum Target { SOURCE, PROJECTION }
    public enum Outcome { REMOVED, ALREADY_MISSING, IDENTITY_MISMATCH, RETRY_REQUIRED }

    /** Bounded durable intent for one source UUID or exact bonded projection marker. */
    public record CleanupIntent(
            @Nonnull String cleanupId,
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            @Nullable String leaseToken,
            @Nonnull Target target,
            @Nonnull UUID targetNpcUuid,
            @Nonnull String reason,
            long retainedUntilMs
    ) {
        public CleanupIntent {
            cleanupId = text(cleanupId, "cleanupId");
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            rosterId = text(rosterId, "rosterId");
            profileId = text(profileId, "profileId");
            leaseToken = optional(leaseToken);
            target = Objects.requireNonNull(target, "target");
            targetNpcUuid = Objects.requireNonNull(targetNpcUuid, "targetNpcUuid");
            reason = text(reason, "reason");
            if (target == Target.PROJECTION && leaseToken == null) {
                throw new IllegalArgumentException("projection cleanup requires leaseToken");
            }
            if (retainedUntilMs == 0L) {
                throw new IllegalArgumentException("cleanup retention must be bounded");
            }
        }

        @Nonnull
        public static CleanupIntent projection(
                String cleanupId, UUID ownerUuid, String rosterId,
                String profileId, String leaseToken, UUID targetNpcUuid,
                String reason, long retainedUntilMs
        ) {
            return new CleanupIntent(
                    cleanupId, ownerUuid, rosterId, profileId, leaseToken,
                    Target.PROJECTION, targetNpcUuid, reason, retainedUntilMs
            );
        }

        @Nonnull
        public static CleanupIntent source(
                String cleanupId, UUID ownerUuid, String rosterId,
                String profileId, UUID targetNpcUuid, String reason,
                long retainedUntilMs
        ) {
            return new CleanupIntent(
                    cleanupId, ownerUuid, rosterId, profileId, null,
                    Target.SOURCE, targetNpcUuid, reason, retainedUntilMs
            );
        }
    }

    /** Current world-thread observation used to recheck identity immediately before removal. */
    public record ObservedTarget(
            @Nonnull UUID npcUuid,
            @Nonnull String worldKey,
            @Nullable TameworkProjectionIdentityComponent marker
    ) {
        public ObservedTarget {
            npcUuid = Objects.requireNonNull(npcUuid, "npcUuid");
            worldKey = text(worldKey, "worldKey");
            marker = marker == null ? null : marker.clone();
        }
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
