package com.alechilles.alecstamework.companion.bonded;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Replays bounded physical cleanup without ever widening an exact target. */
public final class BondedCompanionProjectionCleanupService {
    /**
     * Exact marker identity is retained long enough to reconcile a world that was unloaded
     * during cleanup, while still giving every retained record a finite terminal lifetime.
     */
    public static final long CLEANUP_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L;
    private final WorldGateway world;

    public BondedCompanionProjectionCleanupService(@Nonnull WorldGateway world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    /** Attempts one exact cleanup from stable IDs on the owning world thread. */
    @Nonnull
    public Outcome recover(@Nonnull CleanupIntent intent) {
        Objects.requireNonNull(intent, "intent");
        try {
            Outcome outcome = world.removeIfExact(intent);
            return outcome == null ? Outcome.RETRY_REQUIRED : outcome;
        } catch (RuntimeException failure) {
            return Outcome.RETRY_REQUIRED;
        }
    }

    /** Revalidates the complete intent and removes in one world-thread/command-buffer operation. */
    @FunctionalInterface
    public interface WorldGateway {
        @Nonnull Outcome removeIfExact(@Nonnull CleanupIntent intent);
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
            @Nonnull String worldKey,
            @Nonnull String reason,
            long createdAtMs,
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
            worldKey = text(worldKey, "worldKey");
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
                String worldKey, String reason, long retainedUntilMs
        ) {
            return new CleanupIntent(
                    cleanupId, ownerUuid, rosterId, profileId, leaseToken,
                    Target.PROJECTION, targetNpcUuid, worldKey, reason,
                    inferredCreatedAt(retainedUntilMs), retainedUntilMs
            );
        }

        @Nonnull
        public static CleanupIntent source(
                String cleanupId, UUID ownerUuid, String rosterId,
                String profileId, UUID targetNpcUuid, String worldKey,
                String reason,
                long retainedUntilMs
        ) {
            return new CleanupIntent(
                    cleanupId, ownerUuid, rosterId, profileId, null,
                    Target.SOURCE, targetNpcUuid, worldKey, reason,
                    inferredCreatedAt(retainedUntilMs), retainedUntilMs
            );
        }

        private static long inferredCreatedAt(long retainedUntilMs) {
            try {
                return Math.subtractExact(retainedUntilMs, CLEANUP_RETENTION_MS);
            } catch (ArithmeticException underflow) {
                return Long.MIN_VALUE;
            }
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
