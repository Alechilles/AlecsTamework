package com.alechilles.alecstamework.companion.bonded;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Maintains an in-memory view of leases that durable operations committed. */
public final class BondedCompanionLeaseRuntimeIndex
        implements BondedCompanionProjectionService.LeaseLifecycleObserver {
    private static final Comparator<BondedCompanionProjectionValidator.LeaseExpectation> ORDER =
            Comparator.comparing(BondedCompanionProjectionValidator.LeaseExpectation::profileId)
                    .thenComparing(BondedCompanionProjectionValidator.LeaseExpectation::leaseToken);
    private final Map<String, Map<LeaseIdentity,
            BondedCompanionProjectionValidator.LeaseExpectation>> leasesByWorld = new HashMap<>();
    private final Map<LeaseIdentity, String> worldByLease = new HashMap<>();

    /** Publishes one lease after its durable LIVE transition succeeds. */
    @Override
    public synchronized void activated(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        BondedCompanionProjectionValidator.LeaseExpectation required =
                Objects.requireNonNull(lease, "lease");
        LeaseIdentity identity = LeaseIdentity.of(required);
        removeIdentity(identity);
        leasesByWorld.computeIfAbsent(required.worldKey(), ignored -> new LinkedHashMap<>())
                .put(identity, required);
        worldByLease.put(identity, required.worldKey());
    }

    /** Publishes one lease when rebuilding from complete durable evidence. */
    public synchronized void activate(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        activated(lease);
    }

    /** Removes only the committed profile/token lease identity. */
    @Override
    public synchronized void ended(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        removeIdentity(LeaseIdentity.of(Objects.requireNonNull(lease, "lease")));
    }

    public synchronized void remove(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        ended(lease);
    }

    /** Atomically replaces one world's view after a complete durable read. */
    public synchronized void replaceWorld(
            @Nonnull String worldKey,
            @Nonnull Collection<BondedCompanionProjectionValidator.LeaseExpectation> leases
    ) {
        String world = text(worldKey, "worldKey");
        clearWorld(world);
        for (BondedCompanionProjectionValidator.LeaseExpectation lease
                : Objects.requireNonNull(leases, "leases")) {
            if (lease != null && world.equals(lease.worldKey())) {
                activate(lease);
            }
        }
    }

    /** Returns a bounded deterministic immutable snapshot without durable I/O. */
    @Nonnull
    public synchronized List<BondedCompanionProjectionValidator.LeaseExpectation> snapshotWorld(
            @Nonnull String worldKey,
            int maximumResults
    ) {
        if (maximumResults < 1) {
            return List.of();
        }
        Map<LeaseIdentity, BondedCompanionProjectionValidator.LeaseExpectation> worldLeases =
                leasesByWorld.get(text(worldKey, "worldKey"));
        if (worldLeases == null || worldLeases.isEmpty()) {
            return List.of();
        }
        ArrayList<BondedCompanionProjectionValidator.LeaseExpectation> ordered =
                new ArrayList<>(worldLeases.values());
        ordered.sort(ORDER);
        if (ordered.size() > maximumResults) {
            ordered.subList(maximumResults, ordered.size()).clear();
        }
        return List.copyOf(ordered);
    }

    public synchronized boolean hasWorldActivity(@Nonnull String worldKey) {
        Map<LeaseIdentity, BondedCompanionProjectionValidator.LeaseExpectation> leases =
                leasesByWorld.get(text(worldKey, "worldKey"));
        return leases != null && !leases.isEmpty();
    }

    public synchronized void clearWorld(@Nonnull String worldKey) {
        String world = text(worldKey, "worldKey");
        Map<LeaseIdentity, BondedCompanionProjectionValidator.LeaseExpectation> removed =
                leasesByWorld.remove(world);
        if (removed != null) {
            for (LeaseIdentity identity : removed.keySet()) {
                worldByLease.remove(identity, world);
            }
        }
    }

    private void removeIdentity(LeaseIdentity identity) {
        String world = worldByLease.remove(identity);
        if (world == null) {
            return;
        }
        Map<LeaseIdentity, BondedCompanionProjectionValidator.LeaseExpectation> leases =
                leasesByWorld.get(world);
        if (leases == null) {
            return;
        }
        leases.remove(identity);
        if (leases.isEmpty()) {
            leasesByWorld.remove(world);
        }
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private record LeaseIdentity(String profileId, String leaseToken) {
        private static LeaseIdentity of(
                BondedCompanionProjectionValidator.LeaseExpectation lease
        ) {
            return new LeaseIdentity(lease.profileId(), lease.leaseToken());
        }
    }
}
