package com.alechilles.alecstamework.companion.bonded;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Routes bonded recovery through only the exact world recorded by each lease.
 *
 * <p>World-load and maintenance signals require a conclusive inspection of
 * their current world. Logout and transfer are authoritative non-death exits;
 * they retain the durable snapshot when their recorded world cannot be read.
 * No method enumerates worlds or pages a global live-lease population.</p>
 */
public final class BondedCompanionLocalProjectionLifecycle {
    private final BondedCompanionWorldLifecycleObserver observer;
    private final LeaseSource leases;
    private final ObservationSource observations;
    private final int maximumLeases;
    private final int maximumObservations;

    public BondedCompanionLocalProjectionLifecycle(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull LeaseSource leases,
            @Nonnull ObservationSource observations,
            int maximumLeases,
            int maximumObservations
    ) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.observations = Objects.requireNonNull(observations, "observations");
        if (maximumLeases < 1 || maximumObservations < 1) {
            throw new IllegalArgumentException("local recovery limits must be positive");
        }
        this.maximumLeases = maximumLeases;
        this.maximumObservations = maximumObservations;
    }

    /** Submits one conclusive-only inspection for leases owned by this world. */
    public int reconcileCurrentWorld(
            @Nonnull String worldKey,
            @Nonnull BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs
    ) {
        String currentWorld = text(worldKey, "worldKey");
        List<BondedCompanionProjectionValidator.LeaseExpectation> local =
                localLeases(currentWorld);
        submit(currentWorld, local, cause, observedAtMs, false);
        return local.size();
    }

    /** Stores every exact lease for an owner, grouped by its recorded world. */
    public int storeOwner(
            @Nonnull UUID ownerUuid,
            @Nonnull BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs
    ) {
        requireOwnerExit(cause);
        List<BondedCompanionProjectionValidator.LeaseExpectation> owned =
                safeOwnerLeases(Objects.requireNonNull(ownerUuid, "ownerUuid"));
        Map<String, List<BondedCompanionProjectionValidator.LeaseExpectation>>
                byWorld = new LinkedHashMap<>();
        for (var lease : owned) {
            byWorld.computeIfAbsent(lease.worldKey(), ignored -> new ArrayList<>())
                    .add(lease);
        }
        byWorld.forEach((worldKey, worldLeases) -> submit(
                worldKey, List.copyOf(worldLeases), cause, observedAtMs, true));
        return owned.size();
    }

    /** Stores only the owner's leases recorded in the exact prior world. */
    public int storeOwnerInWorld(
            @Nonnull UUID ownerUuid,
            @Nonnull String worldKey,
            @Nonnull BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs
    ) {
        requireOwnerExit(cause);
        UUID owner = Objects.requireNonNull(ownerUuid, "ownerUuid");
        String exactWorld = text(worldKey, "worldKey");
        List<BondedCompanionProjectionValidator.LeaseExpectation> exact;
        try {
            exact = List.copyOf(Objects.requireNonNull(
                    leases.forOwnerInWorld(owner, exactWorld, maximumLeases),
                    "owner world leases").stream().filter(Objects::nonNull)
                    .filter(lease -> owner.equals(lease.ownerUuid())
                            && exactWorld.equals(lease.worldKey())).toList());
        } catch (RuntimeException | LinkageError failure) {
            exact = List.of();
        }
        submit(exactWorld, exact, cause, observedAtMs, true);
        return exact.size();
    }

    /** Resolves death from the exact marker supplied by the current world event. */
    public void onConfirmedDeath(
            @Nonnull BondedCompanionProjectionValidator.Projection projection,
            long diedAtMs
    ) {
        Objects.requireNonNull(projection, "projection");
        var marker = projection.marker();
        if (!marker.isBondedCompanion() || marker.getProfileId() == null
                || marker.getBondedLeaseToken() == null) {
            return;
        }
        Optional<BondedCompanionProjectionValidator.LeaseExpectation> exact =
                safeExact(marker.getProfileId(), marker.getBondedLeaseToken());
        exact.filter(lease -> lease.liveNpcUuid().equals(projection.npcUuid())
                        && lease.worldKey().equals(projection.worldKey()))
                .ifPresent(lease -> observer.onConfirmedDeath(
                        lease, projection, diedAtMs));
    }

    private void submit(
            String worldKey,
            List<BondedCompanionProjectionValidator.LeaseExpectation> exactLeases,
            BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs,
            boolean authoritativeExit
    ) {
        if (exactLeases.isEmpty()) return;
        CompletionStage<WorldObservation> stage;
        try {
            stage = observations.inspect(
                    worldKey, exactLeases, maximumObservations);
        } catch (RuntimeException | LinkageError failure) {
            settleUnavailable(exactLeases, cause, observedAtMs, authoritativeExit);
            return;
        }
        if (stage == null) {
            settleUnavailable(exactLeases, cause, observedAtMs, authoritativeExit);
            return;
        }
        stage.whenComplete((result, failure) -> {
            if (failure != null || result == null || !result.conclusive()) {
                settleUnavailable(
                        exactLeases, cause, observedAtMs, authoritativeExit);
                return;
            }
            List<BondedCompanionProjectionValidator.Projection> localObserved =
                    result.observed().stream().filter(projection ->
                            worldKey.equals(projection.worldKey())).toList();
            observer.onScanned(
                    exactLeases, localObserved, cause, observedAtMs);
        });
    }

    private void settleUnavailable(
            List<BondedCompanionProjectionValidator.LeaseExpectation> exactLeases,
            BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs,
            boolean authoritativeExit
    ) {
        if (authoritativeExit) {
            observer.onScanned(exactLeases, List.of(), cause, observedAtMs);
        }
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation> localLeases(
            String worldKey
    ) {
        try {
            return List.copyOf(Objects.requireNonNull(
                    leases.inWorld(worldKey, maximumLeases), "world leases")
                    .stream().filter(Objects::nonNull)
                    .filter(lease -> worldKey.equals(lease.worldKey())).toList());
        } catch (RuntimeException | LinkageError failure) {
            return List.of();
        }
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation>
            safeOwnerLeases(UUID ownerUuid) {
        try {
            return List.copyOf(Objects.requireNonNull(
                    leases.forOwner(ownerUuid, maximumLeases), "owner leases")
                    .stream().filter(Objects::nonNull)
                    .filter(lease -> ownerUuid.equals(lease.ownerUuid())).toList());
        } catch (RuntimeException | LinkageError failure) {
            return List.of();
        }
    }

    private Optional<BondedCompanionProjectionValidator.LeaseExpectation>
            safeExact(String profileId, String leaseToken) {
        try {
            return Objects.requireNonNull(
                    leases.exact(profileId, leaseToken), "exact lease");
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    private void requireOwnerExit(
            BondedCompanionProjectionService.RecoveryCause cause
    ) {
        Objects.requireNonNull(cause, "cause");
        if (cause != BondedCompanionProjectionService.RecoveryCause.LOGOUT
                && cause != BondedCompanionProjectionService.RecoveryCause
                .WORLD_TRANSFER) {
            throw new IllegalArgumentException("owner exit cause required");
        }
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    /** Targeted durable reads; none enumerate all active leases for recovery. */
    public interface LeaseSource {
        @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> inWorld(
                @Nonnull String worldKey, int limit);

        @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> forOwner(
                @Nonnull UUID ownerUuid, int limit);

        @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation>
        forOwnerInWorld(
                @Nonnull UUID ownerUuid, @Nonnull String worldKey, int limit);

        @Nonnull Optional<BondedCompanionProjectionValidator.LeaseExpectation> exact(
                @Nonnull String profileId, @Nonnull String leaseToken);
    }

    /** Schedules one inspection of the supplied exact recorded world. */
    @FunctionalInterface
    public interface ObservationSource {
        @Nonnull CompletionStage<WorldObservation> inspect(
                @Nonnull String worldKey,
                @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
                int maximumObservations);
    }

    /** Current-world-only observations; inconclusive evidence never infers missing. */
    public record WorldObservation(
            @Nonnull List<BondedCompanionProjectionValidator.Projection> observed,
            boolean conclusive
    ) {
        public WorldObservation {
            observed = List.copyOf(Objects.requireNonNull(observed, "observed"));
        }

        @Nonnull public static WorldObservation inconclusive() {
            return new WorldObservation(List.of(), false);
        }
    }
}
