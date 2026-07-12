package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Adopts live ECS truth into the in-memory indexes immediately and queues only changed state.
 */
public final class CompanionPopulationRuntimeReconciler
        implements CoalescedCompanionPopulationWriter.Listener {
    private final OwnerPopulationIndex ownerIndex;
    private final ClaimOccupancyIndex claimIndex;
    private final CompanionIdentityResolver identityResolver;
    private final CoalescedCompanionPopulationWriter writer;
    private final PersistenceHealthService persistenceHealth;
    private final CompanionPopulationObservationPolicy observationPolicy;
    private final CompanionPopulationIndexReplayService indexReplay;
    private final CompanionLiveEvidenceRevision liveEvidenceRevision;
    private final Object reloadLock = new Object();
    private final Map<String, CompanionPopulationObservation> observationsDuringReload = new HashMap<>();
    private final Map<String, CompanionPopulationObservation> deferredObservations = new HashMap<>();
    private boolean canonicalReloadInProgress;

    public CompanionPopulationRuntimeReconciler(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull CoalescedCompanionPopulationWriter writer,
            @Nonnull PersistenceHealthService persistenceHealth
    ) {
        this(ownerIndex, claimIndex, identityResolver, writer, persistenceHealth,
                new CompanionPopulationObservationPolicy(ownerIndex),
                new CompanionLiveEvidenceRevision());
    }

    public CompanionPopulationRuntimeReconciler(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull CoalescedCompanionPopulationWriter writer,
            @Nonnull PersistenceHealthService persistenceHealth,
            @Nonnull CompanionLiveEvidenceRevision liveEvidenceRevision
    ) {
        this(ownerIndex, claimIndex, identityResolver, writer, persistenceHealth,
                new CompanionPopulationObservationPolicy(ownerIndex), liveEvidenceRevision);
    }

    CompanionPopulationRuntimeReconciler(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull CoalescedCompanionPopulationWriter writer,
            @Nonnull PersistenceHealthService persistenceHealth,
            @Nonnull CompanionPopulationObservationPolicy observationPolicy
    ) {
        this(ownerIndex, claimIndex, identityResolver, writer, persistenceHealth,
                observationPolicy, new CompanionLiveEvidenceRevision());
    }

    CompanionPopulationRuntimeReconciler(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull CoalescedCompanionPopulationWriter writer,
            @Nonnull PersistenceHealthService persistenceHealth,
            @Nonnull CompanionPopulationObservationPolicy observationPolicy,
            @Nonnull CompanionLiveEvidenceRevision liveEvidenceRevision
    ) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.claimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.persistenceHealth = Objects.requireNonNull(persistenceHealth, "persistenceHealth");
        this.observationPolicy = Objects.requireNonNull(observationPolicy, "observationPolicy");
        this.liveEvidenceRevision = Objects.requireNonNull(
                liveEvidenceRevision, "liveEvidenceRevision");
        this.indexReplay = new CompanionPopulationIndexReplayService(ownerIndex, claimIndex);
    }

    public void setWarningSink(@Nullable WarningSink warningSink) {
        observationPolicy.setWarningSink(warningSink == null ? null : warningSink::warn);
    }

    /** Number of preserved cross-world moves observed after they made a per-world bucket over-cap. */
    public long unavoidablePerWorldOverCapRelocations() {
        return observationPolicy.unavoidablePerWorldOverCapRelocations();
    }

    @Nonnull
    public ObservationOutcome observePhysical(@Nonnull UUID npcUuid,
                                              @Nullable UUID ownerUuid,
                                              @Nonnull String worldName,
                                              int chunkX,
                                              int chunkZ,
                                              @Nonnull CompanionLifecycleState lifecycleState,
                                              @Nonnull String source) {
        if (lifecycleState != CompanionLifecycleState.ACTIVE
                && lifecycleState != CompanionLifecycleState.UNLOADED) {
            throw new IllegalArgumentException("Physical observations must be ACTIVE or UNLOADED.");
        }
        return observe(
                npcUuid,
                ownerUuid,
                worldName,
                lifecycleState,
                new ClaimChunkCoordinate(worldName, chunkX, chunkZ),
                source,
                true,
                false
        );
    }

    /** Owner-component callbacks are the expected live side of a claimed mutation, not a lost lifecycle event. */
    @Nonnull
    ObservationOutcome observeOwnerComponentPhysical(@Nonnull UUID npcUuid,
                                                      @Nullable UUID ownerUuid,
                                                      @Nonnull String worldName,
                                                      int chunkX,
                                                      int chunkZ,
                                                      @Nonnull String source) {
        return observe(
                npcUuid,
                ownerUuid,
                worldName,
                CompanionLifecycleState.ACTIVE,
                new ClaimChunkCoordinate(worldName, chunkX, chunkZ),
                source,
                false,
                false
        );
    }

    /**
     * Distinguishes an explicit journaled owner clear from an unjournaled component removal.
     * The removed owner is retained until a pending transition or durable RELEASED entry proves
     * the clear was authorized.
     */
    @Nonnull
    ObservationOutcome observeOwnerComponentRemoval(@Nonnull UUID npcUuid,
                                                     @Nullable UUID removedOwnerUuid,
                                                     @Nonnull String worldName,
                                                     int chunkX,
                                                     int chunkZ,
                                                     @Nonnull String source) {
        return observe(
                npcUuid,
                removedOwnerUuid,
                worldName,
                CompanionLifecycleState.ACTIVE,
                new ClaimChunkCoordinate(worldName, chunkX, chunkZ),
                source,
                false,
                true
        );
    }

    @Nonnull
    public ObservationOutcome observeDormant(@Nonnull UUID npcUuid,
                                             @Nullable UUID ownerUuid,
                                             @Nullable String ownershipWorldName,
                                             @Nonnull CompanionLifecycleState lifecycleState,
                                             @Nonnull String source) {
        if (lifecycleState == CompanionLifecycleState.ACTIVE
                || lifecycleState == CompanionLifecycleState.UNLOADED) {
            throw new IllegalArgumentException("Dormant observations cannot use a physical lifecycle state.");
        }
        return observe(npcUuid, ownerUuid, ownershipWorldName, lifecycleState, null, source, true, false);
    }

    @Nonnull
    private ObservationOutcome observe(@Nonnull UUID npcUuid,
                                       @Nullable UUID ownerUuid,
                                       @Nullable String ownershipWorldName,
                                       @Nonnull CompanionLifecycleState lifecycleState,
                                       @Nullable ClaimChunkCoordinate physicalChunk,
                                       @Nonnull String source,
                                       boolean deferInFlight,
                                       boolean ownerComponentRemoval) {
        ObservationResult result;
        synchronized (reloadLock) {
            result = observeLocked(
                    npcUuid,
                    ownerUuid,
                    ownershipWorldName,
                    lifecycleState,
                    physicalChunk,
                    source,
                    deferInFlight,
                    ownerComponentRemoval
            );
        }
        if (result.outcome() != ObservationOutcome.NO_CHANGE) {
            liveEvidenceRevision.advance();
        }
        if (result.observation() != null) {
            queueObservation(result.observation());
        }
        if (result.warning() != null) {
            observationPolicy.warn(result.warning());
        }
        return result.outcome();
    }

    @Nonnull
    private ObservationResult observeLocked(@Nonnull UUID npcUuid,
                                             @Nullable UUID ownerUuid,
                                             @Nullable String ownershipWorldName,
                                             @Nonnull CompanionLifecycleState lifecycleState,
                                             @Nullable ClaimChunkCoordinate physicalChunk,
                                             @Nonnull String source,
                                             boolean deferInFlight,
                                             boolean ownerComponentRemoval) {
        Objects.requireNonNull(npcUuid, "npcUuid");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        String profileId = identityResolver.resolveProfileId(npcUuid)
                .orElseGet(() -> identityResolver.resolveOrAllocate(
                        npcUuid,
                        "runtime-observation:" + npcUuid
                ).profileId());
        OwnerPopulationEntry currentOwner = ownerIndex.entry(profileId).orElse(null);
        long revision = currentOwner == null ? 0L : currentOwner.revision();
        String effectiveWorld = normalizeWorld(ownershipWorldName);
        if (effectiveWorld == null && currentOwner != null) {
            effectiveWorld = currentOwner.ownershipWorldName();
        }
        if (ownerComponentRemoval) {
            CompanionPopulationObservationPolicy.RemovalDisposition disposition =
                    observationPolicy.authorizeRemoval(profileId, ownerUuid, currentOwner);
            if (disposition == CompanionPopulationObservationPolicy.RemovalDisposition.SUPPRESSED_IN_FLIGHT) {
                return ObservationResult.only(ObservationOutcome.SUPPRESSED_IN_FLIGHT);
            }
            if (disposition == CompanionPopulationObservationPolicy.RemovalDisposition.AUTHORIZED_RELEASE) {
                return ObservationResult.only(ObservationOutcome.AUTHORIZED_RELEASE);
            }
            if (disposition == CompanionPopulationObservationPolicy.RemovalDisposition.REJECTED_UNJOURNALED_CLEAR) {
                return new ObservationResult(
                        ObservationOutcome.REJECTED_UNJOURNALED_CLEAR,
                        null,
                        observationPolicy.rejectedRemoval(currentOwner, effectiveWorld)
                );
            }
        }
        OwnerPopulationEntry observedOwner = new OwnerPopulationEntry(
                profileId,
                ownerUuid,
                effectiveWorld,
                lifecycleState,
                revision
        );
        ClaimOccupancyEntry observedClaim = new ClaimOccupancyEntry(
                profileId,
                ownerUuid,
                lifecycleState,
                physicalChunk,
                revision
        );
        CompanionPopulationObservation observation = observation(
                profileId, npcUuid, ownerUuid, effectiveWorld, lifecycleState,
                physicalChunk, revision, source
        );
        if (!ownerComponentRemoval && ownerIndex.hasPendingTransition(profileId)) {
            if (!deferInFlight) {
                return ObservationResult.only(ObservationOutcome.SUPPRESSED_IN_FLIGHT);
            }
            deferObservation(observation);
            return new ObservationResult(
                    ObservationOutcome.SUPPRESSED_IN_FLIGHT,
                    observation,
                    null
            );
        }
        ClaimOccupancyEntry currentClaim = claimIndex.entry(profileId).orElse(null);
        if (Objects.equals(currentOwner, observedOwner) && Objects.equals(currentClaim, observedClaim)) {
            CompanionPopulationObservation deferred = deferredObservations.remove(profileId);
            return deferred == null
                    ? ObservationResult.only(ObservationOutcome.NO_CHANGE)
                    : new ObservationResult(ObservationOutcome.NO_CHANGE, observation, null);
        }
        CompanionPopulationObservationPolicy.WarningEvent warning =
                observationPolicy.changedEntry(currentOwner, ownerUuid, effectiveWorld);

        if (!ownerIndex.tryReconcileCommittedEntry(observedOwner)) {
            deferObservation(observation);
            return new ObservationResult(
                    ObservationOutcome.SUPPRESSED_IN_FLIGHT,
                    observation,
                    null
            );
        }
        claimIndex.observeMovement(observedClaim);
        deferredObservations.remove(profileId);
        if (canonicalReloadInProgress) {
            observationsDuringReload.put(profileId, observation);
        }
        return new ObservationResult(
                currentOwner == null ? ObservationOutcome.ADOPTED : ObservationOutcome.UPDATED,
                observation,
                warning
        );
    }

    @Nonnull
    private static CompanionPopulationObservation observation(
            @Nonnull String profileId,
            @Nonnull UUID npcUuid,
            @Nullable UUID ownerUuid,
            @Nullable String ownershipWorldName,
            @Nonnull CompanionLifecycleState lifecycleState,
            @Nullable ClaimChunkCoordinate physicalChunk,
            long revision,
            @Nonnull String source
    ) {
        return new CompanionPopulationObservation(
                profileId,
                npcUuid,
                ownerUuid,
                ownershipWorldName,
                lifecycleState,
                physicalChunk == null ? null : physicalChunk.worldName(),
                physicalChunk == null ? null : physicalChunk.chunkX(),
                physicalChunk == null ? null : physicalChunk.chunkZ(),
                revision,
                source
        );
    }

    private void deferObservation(@Nonnull CompanionPopulationObservation observation) {
        deferredObservations.put(observation.profileId(), observation);
        if (canonicalReloadInProgress) {
            observationsDuringReload.put(observation.profileId(), observation);
        }
    }

    private void queueObservation(@Nonnull CompanionPopulationObservation observation) {
        boolean queued;
        try {
            queued = writer.record(observation);
        } catch (RuntimeException | LinkageError failure) {
            queued = false;
        }
        if (!queued) {
            persistenceHealth.markDegraded("population-observation-queue-failed");
            ownerIndex.setReadiness(OwnerPopulationReadiness.DEGRADED);
            claimIndex.setReadiness(ClaimOccupancyReadiness.DEGRADED);
        }
    }

    /** Starts a short replay window around the final canonical DB reload. */
    public void beginCanonicalReload() {
        synchronized (reloadLock) {
            if (canonicalReloadInProgress) {
                throw new IllegalStateException("A canonical population reload is already active.");
            }
            observationsDuringReload.clear();
            canonicalReloadInProgress = true;
        }
    }

    /** Replays observations that arrived while bootstrap replaced the canonical index snapshots. */
    public void finishCanonicalReload() {
        synchronized (reloadLock) {
            if (!canonicalReloadInProgress) {
                return;
            }
            try {
                for (CompanionPopulationObservation observation : observationsDuringReload.values()) {
                    indexReplay.replay(observation);
                }
            } finally {
                observationsDuringReload.clear();
                canonicalReloadInProgress = false;
            }
        }
    }

    @Override
    public void onCompleted(@Nonnull CompanionPopulationObservation observation,
                            @Nonnull CompanionPopulationObservationPersistResult result) {
        if (result.persisted()) {
            try {
                identityResolver.markDurable(
                        observation.profileId(), observation.currentNpcUuid()
                );
            } catch (RuntimeException | LinkageError failure) {
                persistenceHealth.markDegraded("population-observation-identity-conflict");
                ownerIndex.setReadiness(OwnerPopulationReadiness.DEGRADED);
                claimIndex.setReadiness(ClaimOccupancyReadiness.DEGRADED);
                return;
            }
            if (applyPersistedDeferredObservation(observation, result.revision())) {
                return;
            }
            boolean ownerAdvanced = ownerIndex.advanceReconciledRevision(
                    observation.profileId(),
                    observation.expectedRevision(),
                    result.revision()
            );
            if (ownerAdvanced) {
                indexReplay.advanceClaimRevision(
                        observation.profileId(), observation.expectedRevision(), result.revision()
                );
            }
            return;
        }
        if (result.retryable()) {
            return;
        }
        synchronized (reloadLock) {
            CompanionPopulationObservation deferred = deferredObservations.get(observation.profileId());
            if (sameState(deferred, observation)) {
                deferredObservations.remove(observation.profileId());
            }
        }
        persistenceHealth.markDegraded(
                "population-observation-failed:" + (result.reason() == null ? result.status() : result.reason())
        );
        ownerIndex.setReadiness(OwnerPopulationReadiness.DEGRADED);
        claimIndex.setReadiness(ClaimOccupancyReadiness.DEGRADED);
    }

    private boolean applyPersistedDeferredObservation(
            @Nonnull CompanionPopulationObservation observation,
            long revision
    ) {
        synchronized (reloadLock) {
            CompanionPopulationObservation deferred = deferredObservations.get(observation.profileId());
            if (!sameState(deferred, observation)) {
                return false;
            }
            deferredObservations.remove(observation.profileId());
            if (ownerIndex.hasPendingTransition(observation.profileId())) {
                return true;
            }
            OwnerPopulationEntry owner = new OwnerPopulationEntry(
                    observation.profileId(),
                    observation.ownerUuid(),
                    observation.ownershipWorldName(),
                    observation.lifecycleState(),
                    revision
            );
            if (!ownerIndex.tryReconcileCommittedEntry(owner)) {
                return true;
            }
            ClaimChunkCoordinate physical = observation.physicalWorldName() == null
                    ? null
                    : new ClaimChunkCoordinate(
                    observation.physicalWorldName(),
                    Objects.requireNonNull(observation.physicalChunkX(), "physicalChunkX"),
                    Objects.requireNonNull(observation.physicalChunkZ(), "physicalChunkZ")
            );
            claimIndex.observeMovement(new ClaimOccupancyEntry(
                    observation.profileId(),
                    observation.ownerUuid(),
                    observation.lifecycleState(),
                    physical,
                    revision
            ));
            return true;
        }
    }

    private static boolean sameState(@Nullable CompanionPopulationObservation first,
                                     @Nonnull CompanionPopulationObservation second) {
        return first != null
                && first.profileId().equals(second.profileId())
                && first.currentNpcUuid().equals(second.currentNpcUuid())
                && Objects.equals(first.ownerUuid(), second.ownerUuid())
                && Objects.equals(first.ownershipWorldName(), second.ownershipWorldName())
                && first.lifecycleState() == second.lifecycleState()
                && Objects.equals(first.physicalWorldName(), second.physicalWorldName())
                && Objects.equals(first.physicalChunkX(), second.physicalChunkX())
                && Objects.equals(first.physicalChunkZ(), second.physicalChunkZ());
    }

    @Nullable
    private static String normalizeWorld(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum ObservationOutcome {
        ADOPTED,
        UPDATED,
        NO_CHANGE,
        SUPPRESSED_IN_FLIGHT,
        AUTHORIZED_RELEASE,
        REJECTED_UNJOURNALED_CLEAR
    }

    private record ObservationResult(@Nonnull ObservationOutcome outcome,
                                     @Nullable CompanionPopulationObservation observation,
                                     @Nullable CompanionPopulationObservationPolicy.WarningEvent warning) {
        @Nonnull
        private static ObservationResult only(@Nonnull ObservationOutcome outcome) {
            return new ObservationResult(outcome, null, null);
        }
    }

    @FunctionalInterface
    public interface WarningSink {
        void warn(@Nonnull String message);
    }
}
