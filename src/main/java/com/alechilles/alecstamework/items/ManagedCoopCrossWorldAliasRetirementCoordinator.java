package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Location;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.Probe;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex.ProbeStatus;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Decision;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Observation;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coordinates exact historical-alias retirement when retained and stale NPCs occupy different
 * world stores.
 *
 * <p>Every queued hop carries immutable UUID, profile, operation, world, and store evidence only.
 * The retained world first re-proves the exact marker and linked policy {@link Action#ALLOW}; the
 * stale world then re-proves current location, authority, and
 * {@link Reason#HISTORICAL_RESIDENT_ALIAS} before starting despawn. Any ambiguity, lifecycle
 * invalidation, exception, or store replacement leaves the alias untouched.</p>
 */
public final class ManagedCoopCrossWorldAliasRetirementCoordinator
        implements ManagedCoopCrossWorldAliasRetirement, AutoCloseable {
    private final LoadedNpcIdentityIndex loadedIdentities;
    private final DecisionEvaluator evaluator;
    private final RuntimeGateway runtime;
    private final RetirementObserver observer;
    private final ConcurrentMap<RequestKey, PendingState> pending = new ConcurrentHashMap<>();
    private final AtomicLong invalidationEpoch = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    ManagedCoopCrossWorldAliasRetirementCoordinator(
            @Nonnull LoadedNpcIdentityIndex loadedIdentities,
            @Nonnull DecisionEvaluator evaluator,
            @Nonnull RuntimeGateway runtime,
            @Nonnull RetirementObserver observer) {
        this.loadedIdentities = Objects.requireNonNull(loadedIdentities, "loadedIdentities");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /** Admits one exact cross-store request or leaves it untouched with a fail-closed status. */
    @Nonnull
    public RequestStatus submit(@Nonnull RetirementRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return RequestStatus.CLOSED;
        }
        long epoch = invalidationEpoch.get();
        Location staleLocation = exactLocation(request.staleUuid());
        Location retainedLocation = exactLocation(request.retainedUuid());
        if (staleLocation == null || retainedLocation == null) {
            return RequestStatus.DEFERRED;
        }
        if (staleLocation.equals(retainedLocation)) {
            return RequestStatus.SAME_STORE;
        }
        RequestKey key = RequestKey.from(request);
        PendingRequest admitted = new PendingRequest(
                key, request, staleLocation, retainedLocation, epoch);
        if (pending.putIfAbsent(key, admitted) != null) {
            return RequestStatus.COALESCED;
        }
        if (!current(admitted)) {
            pending.remove(key, admitted);
            return RequestStatus.DEFERRED;
        }
        if (!schedule(retainedLocation, () -> proveRetained(admitted))) {
            pending.remove(key, admitted);
            return RequestStatus.DEFERRED;
        }
        return RequestStatus.SCHEDULED;
    }

    @Override
    public void request(@Nonnull RetirementRequest request) {
        submit(request);
    }

    @Override
    public void invalidateNpc(@Nonnull UUID npcUuid) {
        Objects.requireNonNull(npcUuid, "npcUuid");
        pending.entrySet().removeIf(entry -> entry.getValue().containsNpc(npcUuid));
    }

    /** Cancels requests touching one unloaded world before that world can be replaced. */
    public void invalidateWorld(@Nonnull String worldName) {
        String normalized = normalizeWorld(worldName);
        pending.entrySet().removeIf(entry -> entry.getValue().touchesWorld(normalized));
    }

    /** Cancels all retained proof after managed-coop configuration changes. */
    public void invalidateAll() {
        invalidationEpoch.incrementAndGet();
        pending.clear();
    }

    @Override
    public void close() {
        closed.set(true);
        invalidateAll();
    }

    int pendingCount() {
        return pending.size();
    }

    private void proveRetained(PendingRequest admitted) {
        if (!current(admitted) || !locationsCurrent(admitted)) {
            pending.remove(admitted.key(), admitted);
            return;
        }
        try {
            ProjectionObservation retained = runtime.observe(
                    admitted.retainedLocation(), admitted.request().retainedUuid());
            if (retained == null || !retainedProofMatches(admitted, retained.observation())) {
                pending.remove(admitted.key(), admitted);
                return;
            }
            RetainedProof proof = new RetainedProof(admitted, retained.observation());
            if (!pending.replace(admitted.key(), admitted, proof) || !current(proof)
                    || !locationsCurrent(admitted)) {
                pending.remove(admitted.key(), proof);
                return;
            }
            if (!schedule(admitted.staleLocation(), () -> retireStale(proof))) {
                pending.remove(admitted.key(), proof);
            }
        } catch (RuntimeException | AssertionError exception) {
            pending.remove(admitted.key(), admitted);
        }
    }

    private boolean retainedProofMatches(PendingRequest admitted, Observation retainedObservation) {
        Decision retainedDecision = evaluator.decide(retainedObservation);
        RetirementRequest request = admitted.request();
        Decision guarded = new Decision(
                Action.SUPPRESS,
                Reason.HISTORICAL_RESIDENT_ALIAS,
                request.profileId(),
                request.activeOperationId(),
                request.retainedUuid(),
                null);
        return ManagedCoopStaleEntityPolicy.exactRetainedProjectionProof(
                guarded,
                Observation.of(request.staleUuid(), null),
                retainedObservation,
                retainedDecision);
    }

    private void retireStale(RetainedProof proof) {
        PendingRequest admitted = proof.admitted();
        try {
            if (!current(proof) || !locationsCurrent(admitted)) {
                return;
            }
            ProjectionObservation stale = runtime.observe(
                    admitted.staleLocation(), admitted.request().staleUuid());
            if (stale == null || !staleDecisionMatches(admitted, stale.observation())
                    || !current(proof) || !locationsCurrent(admitted)) {
                return;
            }
            if (runtime.markToDespawn(admitted.staleLocation(), stale.observation())) {
                notifyRetired(admitted);
            }
        } catch (RuntimeException | AssertionError ignored) {
            // Every exceptional path is non-destructive unless the exact final mutation already won.
        } finally {
            pending.remove(admitted.key(), proof);
        }
    }

    private boolean staleDecisionMatches(PendingRequest admitted, Observation staleObservation) {
        Decision decision = evaluator.decide(staleObservation);
        RetirementRequest request = admitted.request();
        return decision != null
                && decision.action() == Action.SUPPRESS
                && decision.reason() == Reason.HISTORICAL_RESIDENT_ALIAS
                && request.profileId().equals(decision.profileId())
                && Objects.equals(request.activeOperationId(), decision.operationId())
                && request.retainedUuid().equals(decision.requiredLiveProjectionUuid())
                && decision.staleAliasUuid() == null;
    }

    private boolean locationsCurrent(PendingRequest admitted) {
        return admitted.staleLocation().equals(exactLocation(admitted.request().staleUuid()))
                && admitted.retainedLocation().equals(
                exactLocation(admitted.request().retainedUuid()));
    }

    private boolean current(PendingState state) {
        return !closed.get()
                && state.epoch() == invalidationEpoch.get()
                && pending.get(state.key()) == state;
    }

    @Nullable
    private Location exactLocation(UUID npcUuid) {
        Probe probe = loadedIdentities.probe(npcUuid);
        if (probe.status() != ProbeStatus.ONE_LOCATION || probe.locations().size() != 1) {
            return null;
        }
        Location location = probe.locations().getFirst();
        return validLocation(location) ? location : null;
    }

    private boolean validLocation(Location location) {
        return location != null
                && !"unknown".equalsIgnoreCase(location.worldName())
                && !"unknown-store".equalsIgnoreCase(location.storeIdentity());
    }

    private boolean schedule(Location location, Runnable action) {
        try {
            return runtime.execute(location, action);
        } catch (RuntimeException | AssertionError exception) {
            return false;
        }
    }

    private void notifyRetired(PendingRequest admitted) {
        try {
            observer.onRetired(new RetirementEvent(
                    admitted.request().staleUuid(), admitted.request().retainedUuid(),
                    admitted.request().profileId(), admitted.request().activeOperationId(),
                    admitted.staleLocation(), admitted.retainedLocation()));
        } catch (RuntimeException ignored) {
            // Diagnostics cannot undo a completed exact retirement.
        }
    }

    private static String normalizeWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        return worldName.trim().toLowerCase(Locale.ROOT);
    }

    public enum RequestStatus {
        SCHEDULED,
        COALESCED,
        SAME_STORE,
        DEFERRED,
        CLOSED
    }

    /** Immutable alias/replacement identity carried into the coordinator. */
    public record RetirementRequest(@Nonnull UUID staleUuid,
                                    @Nonnull UUID retainedUuid,
                                    @Nonnull String profileId,
                                    @Nullable String activeOperationId) {
        public RetirementRequest {
            Objects.requireNonNull(staleUuid, "staleUuid");
            Objects.requireNonNull(retainedUuid, "retainedUuid");
            if (staleUuid.equals(retainedUuid)) {
                throw new IllegalArgumentException("stale and retained UUIDs must differ");
            }
            profileId = canonical(profileId, "profileId");
            if (activeOperationId != null) {
                activeOperationId = canonical(activeOperationId, "activeOperationId");
            }
        }

        private static String canonical(String value, String field) {
            if (value == null || value.isBlank() || !value.equals(value.trim())) {
                throw new IllegalArgumentException(field + " must be canonical non-blank text");
            }
            return value;
        }
    }

    /** Immutable successful-retirement diagnostic. */
    public record RetirementEvent(@Nonnull UUID staleUuid,
                                  @Nonnull UUID retainedUuid,
                                  @Nonnull String profileId,
                                  @Nullable String activeOperationId,
                                  @Nonnull Location staleLocation,
                                  @Nonnull Location retainedLocation) {
    }

    private record RequestKey(UUID staleUuid,
                              UUID retainedUuid,
                              String profileId,
                              @Nullable String activeOperationId) {
        private static RequestKey from(RetirementRequest request) {
            return new RequestKey(
                    request.staleUuid(), request.retainedUuid(), request.profileId(),
                    request.activeOperationId());
        }

    }

    private sealed interface PendingState permits PendingRequest, RetainedProof {
        RequestKey key();

        long epoch();

        boolean containsNpc(UUID npcUuid);

        boolean touchesWorld(String normalizedWorld);
    }

    private record PendingRequest(RequestKey key,
                                  RetirementRequest request,
                                  Location staleLocation,
                                  Location retainedLocation,
                                  long epoch) implements PendingState {
        @Override
        public boolean containsNpc(UUID npcUuid) {
            return request.staleUuid().equals(npcUuid)
                    || request.retainedUuid().equals(npcUuid);
        }

        @Override
        public boolean touchesWorld(String normalizedWorld) {
            return normalizeWorld(staleLocation.worldName()).equals(normalizedWorld)
                    || normalizeWorld(retainedLocation.worldName()).equals(normalizedWorld);
        }
    }

    private record RetainedProof(PendingRequest admitted,
                                 Observation retainedObservation) implements PendingState {
        @Override
        public RequestKey key() {
            return admitted.key();
        }

        @Override
        public long epoch() {
            return admitted.epoch();
        }

        @Override
        public boolean containsNpc(UUID npcUuid) {
            return admitted.containsNpc(npcUuid);
        }

        @Override
        public boolean touchesWorld(String normalizedWorld) {
            return admitted.touchesWorld(normalizedWorld);
        }

    }

    record ProjectionObservation(@Nonnull Observation observation) {
    }

    @FunctionalInterface
    interface DecisionEvaluator {
        @Nonnull
        Decision decide(@Nonnull Observation observation);
    }

    interface RuntimeGateway {
        boolean execute(@Nonnull Location location, @Nonnull Runnable action);

        @Nullable
        ProjectionObservation observe(@Nonnull Location location, @Nonnull UUID npcUuid);

        boolean markToDespawn(@Nonnull Location location, @Nonnull Observation observation);
    }

    @FunctionalInterface
    interface RetirementObserver {
        void onRetired(@Nonnull RetirementEvent event);

        static RetirementObserver noop() {
            return ignored -> {
            };
        }
    }

}
