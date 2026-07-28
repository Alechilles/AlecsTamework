package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Validates a disposable projection against one exact bonded lease. */
public final class BondedCompanionProjectionValidator {

    /** Matches only the bonded kind, stable profile, and opaque lease token. */
    public boolean markerMatches(
            @Nonnull LeaseExpectation lease,
            @Nullable TameworkProjectionIdentityComponent marker
    ) {
        Objects.requireNonNull(lease, "lease");
        return marker != null
                && marker.isBondedCompanion()
                && lease.profileId().equals(marker.getProfileId())
                && lease.leaseToken().equals(marker.getBondedLeaseToken());
    }

    /** Classifies all loaded projections without adopting foreign marker kinds or leases. */
    @Nonnull
    public Validation validate(
            @Nonnull LeaseExpectation lease,
            @Nonnull List<Projection> observed
    ) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(observed, "observed");
        ArrayList<Projection> matches = new ArrayList<>();
        Projection valid = null;
        for (Projection projection : observed) {
            if (projection != null && markerMatches(lease, projection.marker())) {
                matches.add(projection);
                if (valid == null && exactLocation(lease, projection)) {
                    valid = projection;
                }
            }
        }
        if (matches.isEmpty()) {
            return new Validation(Status.MISSING, null, List.of(), List.of());
        }
        if (matches.size() > 1) {
            ArrayList<Projection> duplicates = new ArrayList<>(matches);
            if (valid != null) {
                duplicates.remove(valid);
            }
            return new Validation(
                    Status.DUPLICATE, valid, duplicates, matches
            );
        }
        Projection only = matches.getFirst();
        if (!lease.liveNpcUuid().equals(only.npcUuid())) {
            return new Validation(
                    Status.UUID_MISMATCH, null, List.of(only), matches
            );
        }
        if (!lease.worldKey().equals(only.worldKey())) {
            return new Validation(
                    Status.WRONG_WORLD, null, List.of(only), matches
            );
        }
        return new Validation(Status.VALID, only, List.of(), matches);
    }

    private boolean exactLocation(LeaseExpectation lease, Projection projection) {
        return lease.liveNpcUuid().equals(projection.npcUuid())
                && lease.worldKey().equals(projection.worldKey());
    }

    /** Durable projection phase used to distinguish an interrupted spawn. */
    public enum LeasePhase { PENDING, LIVE, REMOVE_PENDING }

    /** Exact lease identity plus owner scope; all timestamps remain signed. */
    public record LeaseExpectation(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            @Nonnull String leaseToken,
            @Nonnull UUID liveNpcUuid,
            @Nonnull String worldKey,
            long startedAtMs,
            long expiresAtMs,
            @Nonnull LeasePhase phase
    ) {
        public LeaseExpectation {
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            rosterId = text(rosterId, "rosterId");
            profileId = text(profileId, "profileId");
            leaseToken = text(leaseToken, "leaseToken");
            liveNpcUuid = Objects.requireNonNull(liveNpcUuid, "liveNpcUuid");
            worldKey = text(worldKey, "worldKey");
            phase = Objects.requireNonNull(phase, "phase");
            if (expiresAtMs != 0L && expiresAtMs < startedAtMs) {
                throw new IllegalArgumentException("lease expiry precedes start");
            }
        }
    }

    /** One loaded bonded-marker observation and optional full world-thread snapshot. */
    public record Projection(
            @Nonnull UUID npcUuid,
            @Nonnull String worldKey,
            @Nonnull TameworkProjectionIdentityComponent marker,
            @Nullable BondedCompanionSnapshot snapshot
    ) {
        public Projection {
            npcUuid = Objects.requireNonNull(npcUuid, "npcUuid");
            worldKey = text(worldKey, "worldKey");
            marker = Objects.requireNonNull(marker, "marker").clone();
        }
    }

    public enum Status { VALID, MISSING, WRONG_WORLD, UUID_MISMATCH, DUPLICATE }

    /** Immutable exact-match outcome; duplicate cleanup never includes foreign leases. */
    public record Validation(
            @Nonnull Status status,
            @Nullable Projection validProjection,
            @Nonnull List<Projection> exactDuplicates,
            @Nonnull List<Projection> exactMatches
    ) {
        public Validation {
            status = Objects.requireNonNull(status, "status");
            exactDuplicates = List.copyOf(exactDuplicates);
            exactMatches = List.copyOf(exactMatches);
        }
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
