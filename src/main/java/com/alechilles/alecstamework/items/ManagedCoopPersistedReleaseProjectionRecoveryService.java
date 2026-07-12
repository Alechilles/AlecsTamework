package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.items.ManagedCoopPersistedProjectionRecovery.Adoption;
import com.alechilles.alecstamework.items.ManagedCoopPersistedProjectionRecovery.Resolution;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.items.ManagedCoopReleasePopulationCoordinator.Preparation;
import com.alechilles.alecstamework.items.ManagedCoopReleasePopulationCoordinator.PreparedRelease;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Finalization;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.FinalizationStatus;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry.ProjectionCurrentness;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry.ProjectionStatus;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidence;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSet;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionProjectionEvidence;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Adopts one exact unloaded managed-release projection from sealed persisted-world evidence.
 *
 * <p>Projection markers never enter ordinary population repair. This service instead validates
 * both entity UUIDs, live/dead state, owner, physical location, and any ordinary evidence before
 * it reacquires the exact population admission. Legacy rows whose population state is already
 * committed may only run the separate lifecycle finalizer after the in-memory canonical indexes
 * prove the same planned UUID, owner, and chunk.</p>
 */
final class ManagedCoopPersistedReleaseProjectionRecoveryService
        implements ManagedCoopPersistedProjectionRecovery {
    private final CompanionPersistedProjectionEvidenceRegistry registry;
    private final ManagedCoopReleasePopulationCoordinator populations;
    private final CoopLifecycleOperationRepository lifecycle;
    private final ManagedCoopCompositeIndexRefreshService managedIndexes;
    private final OwnerPopulationIndex owners;
    private final CompanionIdentityResolver identities;
    private final ClaimOccupancyIndex claims;
    private final CoopResidentStateSnapshotCodec snapshots;
    private final LongSupplier clock;

    ManagedCoopPersistedReleaseProjectionRecoveryService(
            @Nonnull CompanionPersistedProjectionEvidenceRegistry registry,
            @Nonnull ManagedCoopReleasePopulationCoordinator populations,
            @Nonnull CoopLifecycleOperationRepository lifecycle,
            @Nonnull ManagedCoopCompositeIndexRefreshService managedIndexes,
            @Nonnull OwnerPopulationIndex owners,
            @Nonnull CompanionIdentityResolver identities,
            @Nonnull ClaimOccupancyIndex claims) {
        this(registry, populations, lifecycle, managedIndexes, owners, identities, claims,
                new CoopResidentStateSnapshotCodec(), System::currentTimeMillis);
    }

    ManagedCoopPersistedReleaseProjectionRecoveryService(
            CompanionPersistedProjectionEvidenceRegistry registry,
            ManagedCoopReleasePopulationCoordinator populations,
            CoopLifecycleOperationRepository lifecycle,
            ManagedCoopCompositeIndexRefreshService managedIndexes,
            OwnerPopulationIndex owners,
            CompanionIdentityResolver identities,
            ClaimOccupancyIndex claims,
            CoopResidentStateSnapshotCodec snapshots,
            LongSupplier clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.populations = Objects.requireNonNull(populations, "populations");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.managedIndexes = Objects.requireNonNull(managedIndexes, "managedIndexes");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Nonnull
    public Resolution resolve(OperationRecord operation, ResidentRecord resident) {
        Resolution resolved = resolvePersisted(registry, snapshots, operation, resident);
        if (resolved.status() == ManagedCoopPersistedProjectionRecovery.Status.BLOCKED) {
            populations.markReadinessDegraded(resolved.detail());
        }
        return resolved;
    }

    @Nonnull
    static Resolution resolvePersisted(
            @Nonnull CompanionPersistedProjectionEvidenceRegistry registry,
            @Nonnull CoopResidentStateSnapshotCodec snapshots,
            @Nonnull OperationRecord operation,
            @Nonnull ResidentRecord resident) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(resident, "resident");
        CompanionPersistedProjectionEvidenceRegistry.Snapshot sealed = registry.snapshot();
        if (!sealed.sealed()) {
            return denyResolution("managed_coop_persisted_projection_evidence_unsealed");
        }
        try {
            String slotKey = operation.authorityKey().slotKey(operation.residentSlot());
            UUID sourceNpcUuid = Objects.requireNonNull(
                    resident.sourceNpcUuid(), "resident.sourceNpcUuid");
            LoadedNpcIdentityIndex.ProjectionKey projectionKey =
                    new LoadedNpcIdentityIndex.ProjectionKey(
                            operation.profileId(),
                            operation.operationId(),
                            TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                            slotKey,
                            sourceNpcUuid,
                            1L);
            ProjectionCurrentness currentness = registry.projectionCurrentness(projectionKey);
            if (currentness.evidenceRevision() != sealed.revision()
                    || currentness.status() == ProjectionStatus.UNAVAILABLE) {
                return denyResolution("managed_coop_loaded_projection_evidence_unavailable");
            }
            if (currentness.status() == ProjectionStatus.OBSERVED) {
                return denyResolution("managed_coop_loaded_projection_observed");
            }
            String fingerprint = CompanionProjectionEvidence.fingerprint(
                    operation.profileId(), operation.operationId(),
                    TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                    slotKey,
                    sourceNpcUuid,
                    1L);
            List<CompanionPopulationEvidenceSet.ProjectionObservation> observations =
                    sealed.evidenceSet().projectionObservations(fingerprint);
            if (observations.isEmpty()) {
                UUID plannedTargetUuid = operation.plannedTargetUuid();
                if (plannedTargetUuid == null
                        || hasOrdinaryEvidence(sealed.evidenceSet(), plannedTargetUuid)) {
                    return denyResolution(
                            "managed_coop_ordinary_evidence_without_projection_marker");
                }
                return currentness.stableAbsent()
                        ? Resolution.absent(
                                sealed.revision(), currentness.loadedIdentityRevision())
                        : denyResolution("managed_coop_loaded_projection_absence_stale");
            }
            if (observations.size() != 1) {
                return denyResolution("managed_coop_persisted_projection_evidence_duplicated");
            }
            return exact(
                    snapshots, operation, resident, sealed, currentness,
                    observations.getFirst());
        } catch (RuntimeException exception) {
            return denyResolution("managed_coop_persisted_projection_evidence_invalid");
        }
    }

    private static boolean hasOrdinaryEvidence(
            CompanionPopulationEvidenceSet evidenceSet,
            UUID plannedTargetUuid) {
        if (!evidenceSet.observations(plannedTargetUuid).isEmpty()
                || evidenceSet.byNpcUuid().containsKey(plannedTargetUuid)) {
            return true;
        }
        for (CompanionPopulationEvidenceSet.Conflict conflict : evidenceSet.conflicts()) {
            if (plannedTargetUuid.equals(conflict.npcUuid())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean current(Resolution resolution) {
        return registry.current(
                resolution.evidenceRevision(), resolution.loadedIdentityRevision());
    }

    @Override
    public CompletableFuture<Adoption> adopt(
            OperationRecord operation,
            SpawnReady claim,
            ResidentRecord resident,
            Resolution projection) {
        if (!current(projection) || !projection.exact()) {
            populations.markReadinessDegraded(
                    "managed_coop_persisted_projection_evidence_changed");
            return completed(blockAdoption("managed_coop_persisted_projection_evidence_changed"));
        }
        if (legacyPopulationAlreadyCommitted(operation, claim, resident, projection)) {
            return finalizeLegacy(operation, claim, projection);
        }
        return preparePopulation(claim, resident, projection);
    }

    private static Resolution exact(
            CoopResidentStateSnapshotCodec snapshots,
            OperationRecord operation,
            ResidentRecord resident,
            CompanionPersistedProjectionEvidenceRegistry.Snapshot sealed,
            ProjectionCurrentness currentness,
            CompanionPopulationEvidenceSet.ProjectionObservation observation) {
        UUID planned = operation.plannedTargetUuid();
        CompanionPopulationEvidence marker = observation.evidence();
        UUID expectedOwner = snapshotOwner(snapshots, resident);
        if (planned == null || !planned.equals(observation.componentUuid())
                || !planned.equals(observation.legacyNpcUuid())
                || !planned.equals(marker.npcUuid())) {
            return denyResolution("managed_coop_persisted_projection_identity_mismatch");
        }
        if (observation.deathObserved()) {
            return denyResolution("managed_coop_persisted_projection_is_dead");
        }
        if (!marker.ownerObserved() || !Objects.equals(expectedOwner, marker.ownerUuid())) {
            return denyResolution("managed_coop_persisted_projection_owner_mismatch");
        }
        CompanionPopulationEvidenceSet.PhysicalLocation location = location(marker);
        if (location == null
                || !operation.authorityKey().worldName().equals(normalize(location.worldName()))
                || marker.ownershipWorldName() == null
                || !operation.authorityKey().worldName().equals(
                        normalize(marker.ownershipWorldName()))) {
            return denyResolution("managed_coop_persisted_projection_location_mismatch");
        }
        if (!ordinaryEvidenceAgrees(sealed.evidenceSet(), planned, expectedOwner, location)) {
            return denyResolution("managed_coop_persisted_projection_ordinary_evidence_mismatch");
        }
        return Resolution.exact(
                location.worldName(), location.chunkX(), location.chunkZ(), sealed.revision(),
                currentness.loadedIdentityRevision());
    }

    private static boolean ordinaryEvidenceAgrees(
            CompanionPopulationEvidenceSet evidenceSet,
            UUID planned,
            @Nullable UUID expectedOwner,
            CompanionPopulationEvidenceSet.PhysicalLocation location) {
        for (CompanionPopulationEvidenceSet.Conflict conflict : evidenceSet.conflicts()) {
            if (planned.equals(conflict.npcUuid())) {
                return false;
            }
        }
        CompanionPopulationEvidenceSet.ResolvedEvidence ordinary =
                evidenceSet.byNpcUuid().get(planned);
        return ordinary == null || ordinary.physical()
                && !ordinary.deathObserved()
                && ordinary.ownerObserved()
                && Objects.equals(expectedOwner, ordinary.observedOwnerUuid())
                && Objects.equals(location, ordinary.physicalLocation());
    }

    private CompletableFuture<Adoption> preparePopulation(
            SpawnReady claim,
            ResidentRecord resident,
            Resolution projection) {
        CompletableFuture<Preparation> preparation;
        try {
            preparation = populations.prepareAsync(
                    claim, resident, projection.worldName(),
                    projection.chunkX(), projection.chunkZ());
        } catch (RuntimeException exception) {
            return completed(blockAdoption("managed_coop_persisted_projection_prepare_failed"));
        }
        if (preparation == null) {
            return completed(blockAdoption("managed_coop_persisted_projection_prepare_missing"));
        }
        return preparation.thenCompose(result -> commitPrepared(claim, projection, result));
    }

    private CompletableFuture<Adoption> commitPrepared(
            SpawnReady claim,
            Resolution projection,
            @Nullable Preparation preparation) {
        if (preparation == null || !preparation.preparedSuccessfully()
                || preparation.prepared() == null) {
            return completed(blockAdoption(preparation != null
                    ? "managed_coop_persisted_projection_prepare_"
                        + preparation.status().name().toLowerCase(Locale.ROOT)
                    : "managed_coop_persisted_projection_prepare_result_missing"));
        }
        PreparedRelease prepared = preparation.prepared();
        if (!current(projection)) {
            return cancelStalePreparedPopulation(prepared);
        }
        if (!populations.claimForSpawn(prepared, claim)) {
            return completed(blockAdoption("managed_coop_persisted_projection_claim_failed"));
        }
        return populations.commitAsync(prepared, claim, claim.plannedTargetUuid())
                .thenApply(result -> result != null
                        && result.status() != FinalizationStatus.FAILED
                        ? Adoption.adopted("managed_coop_persisted_projection_adopted")
                        : blockAdoption(result != null && result.detail() != null
                                ? result.detail()
                                : "managed_coop_persisted_projection_commit_failed"));
    }

    @Nonnull
    private CompletableFuture<Adoption> cancelStalePreparedPopulation(
            @Nonnull PreparedRelease prepared) {
        String reason = "managed_coop_persisted_projection_evidence_changed_before_claim";
        populations.markReadinessDegraded(reason);
        final CompletableFuture<Boolean> cancellation;
        try {
            cancellation = populations.cancelPreparedPopulationOnlyAsync(prepared, reason);
        } catch (RuntimeException | LinkageError failure) {
            populations.markReadinessDegraded(
                    "managed_coop_persisted_projection_population_cancel_start_failed");
            return completed(blockAdoption(
                    "managed_coop_persisted_projection_population_cancel_failed"));
        }
        if (cancellation == null) {
            populations.markReadinessDegraded(
                    "managed_coop_persisted_projection_population_cancel_missing");
            return completed(blockAdoption(
                    "managed_coop_persisted_projection_population_cancel_failed"));
        }
        return cancellation.handle((cancelled, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(cancelled)) {
                populations.markReadinessDegraded(
                        "managed_coop_persisted_projection_population_cancel_failed");
                return blockAdoption(
                        "managed_coop_persisted_projection_population_cancel_failed");
            }
            return blockAdoption(reason);
        });
    }

    private boolean legacyPopulationAlreadyCommitted(
            OperationRecord operation,
            SpawnReady claim,
            ResidentRecord resident,
            Resolution projection) {
        if (operation.state() != OperationState.PROJECTION_CREATED
                || operation.generation() != 2L
                || !Objects.equals(operation.actualTargetUuid(), claim.plannedTargetUuid())
                || owners.readiness() != OwnerPopulationReadiness.READY
                || claims.readiness() != ClaimOccupancyReadiness.READY
                || !claim.plannedTargetUuid().equals(
                        identities.currentNpcUuid(claim.profileId()).orElse(null))) {
            return false;
        }
        UUID expectedOwner = snapshotOwner(snapshots, resident);
        OwnerPopulationEntry owner = owners.entry(claim.profileId()).orElse(null);
        ClaimOccupancyEntry occupancy = claims.entry(claim.profileId()).orElse(null);
        ClaimChunkCoordinate chunk = occupancy != null ? occupancy.physicalChunk() : null;
        return owner != null && occupancy != null && chunk != null
                && owner.lifecycleState() == CompanionLifecycleState.ACTIVE
                && occupancy.lifecycleState() == CompanionLifecycleState.ACTIVE
                && owner.revision() == occupancy.revision()
                && Objects.equals(expectedOwner, owner.ownerId())
                && Objects.equals(expectedOwner, occupancy.ownerId())
                && Objects.equals(expectedOwner == null ? null : projection.worldName(),
                        owner.ownershipWorldName())
                && normalize(projection.worldName()).equals(normalize(chunk.worldName()))
                && projection.chunkX() == chunk.chunkX()
                && projection.chunkZ() == chunk.chunkZ();
    }

    private CompletableFuture<Adoption> finalizeLegacy(
            OperationRecord operation,
            SpawnReady claim,
            Resolution projection) {
        if (!current(projection)) {
            String reason =
                    "managed_coop_persisted_projection_evidence_changed_before_finalize";
            populations.markReadinessDegraded(reason);
            return completed(blockAdoption(reason));
        }
        PersistenceWriteQueue.WriteSubmission<MutationResult> submission;
        try {
            submission = lifecycle.finalizeRelease(
                    operation.operationId(), operation.generation(), clock.getAsLong());
        } catch (RuntimeException exception) {
            return completed(blockAdoption("managed_coop_persisted_projection_finalize_failed"));
        }
        if (submission == null || submission.completion() == null) {
            return completed(blockAdoption("managed_coop_persisted_projection_finalize_missing"));
        }
        return submission.completion().thenApply(outcome -> {
            MutationResult result = outcome != null ? outcome.value() : null;
            boolean finalized = outcome != null && outcome.isCommitted()
                    && result != null && result.succeeded() && result.operation() != null
                    && result.operation().state() == OperationState.FINALIZED
                    && !result.operation().active()
                    && claim.plannedTargetUuid().equals(result.operation().actualTargetUuid());
            if (!finalized) {
                return blockAdoption("managed_coop_persisted_projection_finalize_rejected");
            }
            ManagedCoopCompositeIndexRefreshService.RefreshResult refreshed =
                    managedIndexes.refresh();
            return refreshed != null && refreshed.refreshed()
                    ? Adoption.adopted("managed_coop_persisted_projection_legacy_finalized")
                    : blockAdoption("managed_coop_persisted_projection_refresh_failed");
        });
    }

    @Nullable
    private static UUID snapshotOwner(
            CoopResidentStateSnapshotCodec snapshots,
            ResidentRecord resident) {
        CoopResidentStateSnapshotCodec.DecodeResult decoded = snapshots.decode(
                resident.snapshotJson());
        if (decoded.status() != CoopResidentStateSnapshotCodec.Status.FOUND
                || decoded.snapshot() == null) {
            throw new IllegalArgumentException("resident snapshot owner is unavailable");
        }
        TameworkOwnerComponent owner = decoded.snapshot().owner();
        return owner == null ? null : owner.getOwnerId();
    }

    @Nullable
    private static CompanionPopulationEvidenceSet.PhysicalLocation location(
            CompanionPopulationEvidence marker) {
        return marker.physicalWorldName() == null
                || marker.physicalChunkX() == null
                || marker.physicalChunkZ() == null
                ? null : new CompanionPopulationEvidenceSet.PhysicalLocation(
                        marker.physicalWorldName(), marker.physicalChunkX(),
                        marker.physicalChunkZ());
    }

    private static Resolution denyResolution(String detail) {
        return Resolution.blocked(detail);
    }

    private Adoption blockAdoption(@Nullable String detail) {
        String reason = detail == null || detail.isBlank()
                ? "managed_coop_persisted_projection_blocked" : detail;
        populations.markReadinessDegraded(reason);
        return Adoption.blocked(reason);
    }

    private CompletableFuture<Adoption> completed(Adoption result) {
        return CompletableFuture.completedFuture(result);
    }

    private static String normalize(String worldName) {
        return Objects.requireNonNull(worldName, "worldName").trim().toLowerCase(Locale.ROOT);
    }
}
