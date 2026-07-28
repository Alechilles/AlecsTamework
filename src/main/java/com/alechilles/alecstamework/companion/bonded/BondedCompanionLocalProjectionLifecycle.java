package com.alechilles.alecstamework.companion.bonded;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Routes bonded recovery through only the exact world recorded by each lease.
 *
 * <p>World-load and maintenance signals require a conclusive inspection of
 * their current world. Logout and transfer are authoritative non-death exits;
 * they retain the durable snapshot when their recorded world cannot be read.
 * No method enumerates worlds or pages a global live-lease population.</p>
 */
public final class BondedCompanionLocalProjectionLifecycle {
    private static final Duration DEFAULT_INSPECTION_TIMEOUT =
            Duration.ofSeconds(2);
    private final BondedCompanionWorldLifecycleObserver observer;
    private final LeaseSource leases;
    private final ObservationSource observations;
    private final int maximumLeases;
    private final int maximumObservations;
    private final long inspectionTimeoutMs;

    public BondedCompanionLocalProjectionLifecycle(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull LeaseSource leases,
            @Nonnull ObservationSource observations,
            int maximumLeases,
            int maximumObservations
    ) {
        this(observer, leases, observations, maximumLeases,
                maximumObservations, DEFAULT_INSPECTION_TIMEOUT);
    }

    public BondedCompanionLocalProjectionLifecycle(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull LeaseSource leases,
            @Nonnull ObservationSource observations,
            int maximumLeases,
            int maximumObservations,
            @Nonnull Duration inspectionTimeout
    ) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.observations = Objects.requireNonNull(observations, "observations");
        if (maximumLeases < 1 || maximumObservations < 1) {
            throw new IllegalArgumentException("local recovery limits must be positive");
        }
        this.maximumLeases = maximumLeases;
        this.maximumObservations = maximumObservations;
        Duration timeout = Objects.requireNonNull(
                inspectionTimeout, "inspectionTimeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException(
                    "inspection timeout must be positive");
        }
        this.inspectionTimeoutMs = Math.max(1L, timeout.toMillis());
    }

    /** Submits one conclusive-only inspection for leases owned by this world. */
    public int reconcileCurrentWorld(
            @Nonnull String worldKey,
            @Nonnull BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs
    ) {
        String currentWorld = text(worldKey, "worldKey");
        return reconcileWorldPages(currentWorld, cause, observedAtMs);
    }

    /** Stores every exact lease for an owner, grouped by its recorded world. */
    public int storeOwner(
            @Nonnull UUID ownerUuid,
            @Nonnull BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs
    ) {
        requireOwnerExit(cause);
        UUID owner = Objects.requireNonNull(ownerUuid, "ownerUuid");
        LeaseCursor cursor = null;
        int submitted = 0;
        while (true) {
            List<BondedCompanionProjectionValidator.LeaseExpectation> page =
                    safeOwnerPage(owner, cursor);
            if (page.isEmpty()) return submitted;
            Map<String, List<BondedCompanionProjectionValidator.LeaseExpectation>>
                    byWorld = new LinkedHashMap<>();
            for (var lease : page) {
                if (owner.equals(lease.ownerUuid())) {
                    byWorld.computeIfAbsent(lease.worldKey(), ignored ->
                            new ArrayList<>()).add(lease);
                    submitted++;
                }
            }
            byWorld.forEach((worldKey, worldLeases) -> submit(
                    worldKey, List.copyOf(worldLeases), cause, observedAtMs, true));
            if (page.size() < maximumLeases) return submitted;
            LeaseCursor next = cursor(page.getLast());
            if (!advances(cursor, next)) return submitted;
            cursor = next;
        }
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
        String cursor = null;
        int submitted = 0;
        while (true) {
            List<BondedCompanionProjectionValidator.LeaseExpectation> page =
                    safeOwnerWorldPage(owner, exactWorld, cursor);
            if (page.isEmpty()) return submitted;
            List<BondedCompanionProjectionValidator.LeaseExpectation> exact =
                    page.stream().filter(lease -> owner.equals(lease.ownerUuid())
                            && exactWorld.equals(lease.worldKey())).toList();
            submit(exactWorld, exact, cause, observedAtMs, true);
            submitted += exact.size();
            if (page.size() < maximumLeases) return submitted;
            String next = page.getLast().profileId();
            if (!advances(cursor, next)) return submitted;
            cursor = next;
        }
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
            stage = bound(observations.inspect(
                    worldKey, exactLeases, maximumObservations));
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

    private CompletionStage<WorldObservation> bound(
            CompletionStage<WorldObservation> stage
    ) {
        if (stage == null) return null;
        CompletableFuture<WorldObservation> bounded = new CompletableFuture<>();
        stage.whenComplete((result, failure) -> {
            if (failure == null) bounded.complete(result);
            else bounded.completeExceptionally(failure);
        });
        if (!bounded.isDone()) {
            CompletableFuture.delayedExecutor(
                    inspectionTimeoutMs, TimeUnit.MILLISECONDS).execute(() ->
                    bounded.complete(WorldObservation.inconclusive()));
        }
        return bounded;
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

    private int reconcileWorldPages(
            String worldKey,
            BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs
    ) {
        String cursor = null;
        int submitted = 0;
        while (true) {
            List<BondedCompanionProjectionValidator.LeaseExpectation> page =
                    safeWorldPage(worldKey, cursor);
            if (page.isEmpty()) return submitted;
            List<BondedCompanionProjectionValidator.LeaseExpectation> local =
                    page.stream().filter(lease -> worldKey.equals(
                            lease.worldKey())).toList();
            submit(worldKey, local, cause, observedAtMs, false);
            submitted += local.size();
            if (page.size() < maximumLeases) return submitted;
            String next = page.getLast().profileId();
            if (!advances(cursor, next)) return submitted;
            cursor = next;
        }
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation>
    safeWorldPage(String worldKey, @Nullable String afterProfileId) {
        try {
            return List.copyOf(Objects.requireNonNull(
                    leases.inWorldAfter(
                            worldKey, afterProfileId, maximumLeases),
                    "world leases").stream().filter(Objects::nonNull).toList());
        } catch (RuntimeException | LinkageError failure) {
            return List.of();
        }
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation>
    safeOwnerPage(UUID ownerUuid, @Nullable LeaseCursor cursor) {
        try {
            return List.copyOf(Objects.requireNonNull(
                    leases.forOwnerAfter(
                            ownerUuid, cursor == null ? null : cursor.worldKey(),
                            cursor == null ? null : cursor.profileId(), maximumLeases),
                    "owner leases").stream().filter(Objects::nonNull).toList());
        } catch (RuntimeException | LinkageError failure) {
            return List.of();
        }
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation>
    safeOwnerWorldPage(
            UUID ownerUuid, String worldKey, @Nullable String afterProfileId
    ) {
        try {
            return List.copyOf(Objects.requireNonNull(
                    leases.forOwnerInWorldAfter(ownerUuid, worldKey,
                            afterProfileId, maximumLeases),
                    "owner world leases").stream().filter(Objects::nonNull).toList());
        } catch (RuntimeException | LinkageError failure) {
            return List.of();
        }
    }

    private LeaseCursor cursor(
            BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        return new LeaseCursor(lease.worldKey(), lease.profileId());
    }

    private boolean advances(@Nullable String previous, String next) {
        return previous == null || next.compareTo(previous) > 0;
    }

    private boolean advances(
            @Nullable LeaseCursor previous, LeaseCursor next
    ) {
        if (previous == null) return true;
        int world = next.worldKey().compareTo(previous.worldKey());
        return world > 0 || world == 0
                && next.profileId().compareTo(previous.profileId()) > 0;
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
        @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation>
        inWorldAfter(
                @Nonnull String worldKey, @Nullable String afterProfileId,
                int limit);

        @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation>
        forOwnerAfter(
                @Nonnull UUID ownerUuid, @Nullable String afterWorldKey,
                @Nullable String afterProfileId, int limit);

        @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation>
        forOwnerInWorldAfter(
                @Nonnull UUID ownerUuid, @Nonnull String worldKey,
                @Nullable String afterProfileId, int limit);

        @Nonnull Optional<BondedCompanionProjectionValidator.LeaseExpectation> exact(
                @Nonnull String profileId, @Nonnull String leaseToken);
    }

    private record LeaseCursor(String worldKey, String profileId) { }

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
