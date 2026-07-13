package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Finalization;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.CoopPopulationReleaseAdmissionService;
import com.alechilles.alecstamework.ownership.CoopPopulationReleaseAdmissionService.ReleaseRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.PopulationReleaseCommitRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopPopulationMutationContext;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Adapts managed-coop release evidence to the canonical owner/claim population admission.
 *
 * <p>The exact planned NPC UUID and resolved destination chunk are journaled before any live
 * spawn. Committing that journal is also the sole schema-v5 release finalizer, so callers must not
 * invoke the legacy projection coordinator for an admission produced here.</p>
 */
public final class ManagedCoopReleasePopulationCoordinator {
    public enum PreparationStatus { PREPARED, DENIED, AMBIGUOUS, FAILED }
    /** Immutable result of acquiring or reacquiring one exact release admission. */
    public record Preparation(@Nonnull PreparationStatus status,
                              @Nullable PreparedRelease prepared,
                              @Nonnull String detail) {
        public Preparation {
            Objects.requireNonNull(status, "status");
            detail = requireText(detail, "detail");
            if ((status == PreparationStatus.PREPARED) != (prepared != null)) {
                throw new IllegalArgumentException("prepared status and handle must agree");
            }
        }
        public boolean preparedSuccessfully() {
            return status == PreparationStatus.PREPARED && prepared != null;
        }
    }
    /** Stable population capability paired with the exact durable release mutation. */
    public static final class PreparedRelease {
        private final ReleaseFingerprint fingerprint;
        private final PopulationReleaseCommitRequest durableRequest;
        private final String destinationWorldName;
        private final int destinationChunkX;
        private final int destinationChunkZ;
        private final Object backendHandle;

        private PreparedRelease(@Nonnull ReleaseFingerprint fingerprint,
                                @Nonnull PopulationReleaseCommitRequest durableRequest,
                                @Nonnull String destinationWorldName,
                                int destinationChunkX,
                                int destinationChunkZ,
                                @Nonnull Object backendHandle) {
            this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
            this.durableRequest = Objects.requireNonNull(durableRequest, "durableRequest");
            this.destinationWorldName = requireText(
                    destinationWorldName, "destinationWorldName");
            this.destinationChunkX = destinationChunkX;
            this.destinationChunkZ = destinationChunkZ;
            this.backendHandle = Objects.requireNonNull(backendHandle, "backendHandle");
        }
        @Nonnull
        public UUID plannedTargetUuid() {
            return fingerprint.plannedTargetUuid();
        }
        @Nonnull
        public String operationId() {
            return fingerprint.operationId();
        }
        @Nonnull
        PopulationReleaseCommitRequest durableRequest() {
            return durableRequest;
        }
        @Nonnull
        String destinationWorldName() {
            return destinationWorldName;
        }
        int destinationChunkX() {
            return destinationChunkX;
        }
        int destinationChunkZ() {
            return destinationChunkZ;
        }
    }
    private final ManagedCoopReleasePopulationInputFactory inputs;
    private final AdmissionBackend backend;
    private final ManagedCoopReleaseLifecycleRollbackService lifecycleRollbacks;
    @Nullable
    private final ManagedCoopCompositeIndexRefreshService finalizedIndexRefresh;
    private final LongSupplier clock;

    public ManagedCoopReleasePopulationCoordinator(
            @Nonnull CoopPopulationReleaseAdmissionService admissions,
            @Nonnull CoopLifecycleOperationRepository lifecycleRepository) {
        this(new CoopResidentPopulationReleaseAdmissionBackend(admissions),
                new CoopLifecycleReleaseRollbackGateway(lifecycleRepository),
                null, System::currentTimeMillis);
    }

    /**
     * Creates the live coordinator with the paired index publication required after finalization.
     */
    public ManagedCoopReleasePopulationCoordinator(
            @Nonnull CoopPopulationReleaseAdmissionService admissions,
            @Nonnull CoopLifecycleOperationRepository lifecycleRepository,
            @Nonnull ManagedCoopCompositeIndexRefreshService finalizedIndexRefresh) {
        this(new CoopResidentPopulationReleaseAdmissionBackend(admissions),
                new CoopLifecycleReleaseRollbackGateway(lifecycleRepository),
                Objects.requireNonNull(finalizedIndexRefresh, "finalizedIndexRefresh"),
                System::currentTimeMillis);
    }

    private ManagedCoopReleasePopulationCoordinator(
            @Nonnull AdmissionBackend backend,
            @Nonnull LifecycleRollbackGateway lifecycleRollback,
            @Nullable ManagedCoopCompositeIndexRefreshService finalizedIndexRefresh,
            @Nonnull LongSupplier clock) {
        this(new CoopResidentStateSnapshotCodec(), backend, lifecycleRollback,
                finalizedIndexRefresh, clock);
    }

    ManagedCoopReleasePopulationCoordinator(
            @Nonnull CoopResidentStateSnapshotCodec snapshotCodec,
            @Nonnull AdmissionBackend backend,
            @Nonnull LifecycleRollbackGateway lifecycleRollback,
            @Nonnull LongSupplier clock) {
        this(snapshotCodec, backend, lifecycleRollback, null, clock);
    }

    ManagedCoopReleasePopulationCoordinator(
            @Nonnull CoopResidentStateSnapshotCodec snapshotCodec,
            @Nonnull AdmissionBackend backend,
            @Nonnull LifecycleRollbackGateway lifecycleRollback,
            @Nullable ManagedCoopCompositeIndexRefreshService finalizedIndexRefresh,
            @Nonnull LongSupplier clock) {
        this.inputs = new ManagedCoopReleasePopulationInputFactory(snapshotCodec, clock);
        this.backend = Objects.requireNonNull(backend, "backend");
        this.finalizedIndexRefresh = finalizedIndexRefresh;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifecycleRollbacks = new ManagedCoopReleaseLifecycleRollbackService(
                lifecycleRollback, clock);
    }
    /** Prepares only after the caller has resolved the exact physical placement and chunk. */
    @Nonnull
    public CompletableFuture<Preparation> prepareAsync(
            @Nonnull SpawnReady claim,
            @Nonnull ResidentRecord resident,
            @Nonnull String worldName,
            int chunkX,
            int chunkZ) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(resident, "resident");
        final PreparedInput input;
        try {
            ManagedCoopReleasePopulationInputFactory.Input created =
                    inputs.create(claim, resident, worldName, chunkX, chunkZ);
            input = new PreparedInput(
                    created.request(), created.durableRequest(), ReleaseFingerprint.from(claim));
        } catch (RuntimeException exception) {
            return completed(PreparationStatus.DENIED, null,
                    failureDetail("population_release_evidence", exception));
        }
        final CompletableFuture<BackendPreparation> completion;
        try {
            completion = backend.prepare(input.request(), plannedUuid -> {
                if (!input.fingerprint().plannedTargetUuid().equals(plannedUuid)) {
                    throw new IllegalArgumentException("population release planned UUID changed");
                }
                return ManagedCoopPopulationMutationContext.releaseExtensionJson(
                        input.durableRequest());
            });
        } catch (RuntimeException exception) {
            markReadinessDegraded("managed_coop_population_prepare_start_failed");
            return completed(PreparationStatus.FAILED, null,
                    failureDetail("population_release_prepare", exception));
        }
        if (completion == null) {
            markReadinessDegraded("managed_coop_population_prepare_stage_missing");
            return completed(PreparationStatus.FAILED, null,
                    "population_release_prepare_stage_missing");
        }
        return completion.handle((result, failure) -> {
            if (failure != null) {
                markReadinessDegraded("managed_coop_population_prepare_failed");
                return new Preparation(PreparationStatus.FAILED, null,
                        failureDetail("population_release_prepare", unwrap(failure)));
            }
            if (result == null) {
                markReadinessDegraded("managed_coop_population_prepare_result_missing");
                return new Preparation(PreparationStatus.AMBIGUOUS, null,
                        "population_release_prepare_result_missing");
            }
            if (result.status() != PreparationStatus.PREPARED) {
                String detail = result.detail() != null
                        ? result.detail() : "population_release_prepare_denied";
                if (result.status() != PreparationStatus.DENIED) {
                    markReadinessDegraded("managed_coop_population_prepare_ambiguous");
                }
                return new Preparation(result.status(), null, detail);
            }
            if (result.handle() == null) {
                markReadinessDegraded("managed_coop_population_prepare_handle_missing");
                return new Preparation(PreparationStatus.AMBIGUOUS, null,
                        "population_release_prepare_handle_missing");
            }
            if (!claim.profileId().equals(result.profileId())
                    || !claim.plannedTargetUuid().equals(result.plannedTargetUuid())) {
                markReadinessDegraded("managed_coop_population_prepare_identity_mismatch");
                return new Preparation(PreparationStatus.FAILED, null,
                        "population_release_prepare_identity_mismatch");
            }
            return new Preparation(
                    PreparationStatus.PREPARED,
                    new PreparedRelease(
                            input.fingerprint(), input.durableRequest(),
                            input.request().worldName(), input.request().chunkX(),
                            input.request().chunkZ(), result.handle()),
                    result.detail() != null ? result.detail()
                            : "population_release_prepared");
        });
    }

    /** Revalidates and claims the population capability immediately before adoption or spawn. */
    public boolean claimForSpawn(@Nonnull PreparedRelease prepared,
                                 @Nonnull SpawnReady claim) {
        if (!matches(prepared, claim)) {
            return false;
        }
        try {
            return backend.claim(prepared.backendHandle);
        } catch (RuntimeException exception) {
            markReadinessDegraded("managed_coop_population_claim_failed");
            return false;
        }
    }

    /** Installs the claimed planned identity/owner capability into the pre-add spawn holder. */
    public boolean writeSpawnHolder(@Nonnull PreparedRelease prepared,
                                    @Nonnull Holder<EntityStore> holder) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(holder, "holder");
        try {
            return backend.writeSpawnHolder(prepared.backendHandle, holder);
        } catch (RuntimeException exception) {
            markReadinessDegraded("managed_coop_population_holder_write_failed");
            return false;
        }
    }

    /** Atomically commits population state and the schema-v5 release finalization. */
    @Nonnull
    public CompletableFuture<Finalization> commitAsync(
            @Nonnull PreparedRelease prepared,
            @Nonnull SpawnReady claim,
            @Nonnull UUID actualTargetUuid) {
        if (!matches(prepared, claim)
                || !prepared.plannedTargetUuid().equals(actualTargetUuid)) {
            markReadinessDegraded("managed_coop_population_commit_identity_mismatch");
            return CompletableFuture.completedFuture(
                    Finalization.failed("population_release_commit_identity_mismatch"));
        }
        final CompletableFuture<CompanionPopulationCommitResult> completion;
        try {
            completion = backend.commit(prepared.backendHandle);
        } catch (RuntimeException exception) {
            markReadinessDegraded("managed_coop_population_commit_start_failed");
            return CompletableFuture.completedFuture(Finalization.failed(
                    failureDetail("population_release_commit", exception)));
        }
        if (completion == null) {
            markReadinessDegraded("managed_coop_population_commit_stage_missing");
            return CompletableFuture.completedFuture(
                    Finalization.failed("population_release_commit_stage_missing"));
        }
        return completion.handle((result, failure) -> {
            if (failure != null || result == null || !result.committed()) {
                markReadinessDegraded("managed_coop_population_commit_failed");
                String detail = failure != null
                        ? failureDetail("population_release_commit", unwrap(failure))
                        : result != null ? result.reason() : "population_release_commit_missing";
                return Finalization.failed(detail);
            }
            return publishFinalizedIndexes(result.reason());
        });
    }

    /**
     * Publishes DEPLOYED resident and inactive-operation evidence before consumers may treat the
     * committed projection as complete. Without this paired refresh, the runtime scheduler keeps
     * the authority blocked on its stale SPAWN_CLAIMED epoch and command queries keep reporting
     * the released companion as housed.
     */
    @Nonnull
    private Finalization publishFinalizedIndexes(@Nullable String commitDetail) {
        if (finalizedIndexRefresh == null) {
            return Finalization.finalized(commitDetail);
        }
        final ManagedCoopCompositeIndexRefreshService.RefreshResult refreshed;
        try {
            refreshed = finalizedIndexRefresh.refresh();
        } catch (RuntimeException exception) {
            markReadinessDegraded("managed_coop_release_finalized_index_refresh_failed");
            return Finalization.failed(
                    failureDetail("population_release_finalized_index_refresh", exception));
        }
        if (refreshed == null || !refreshed.refreshed()
                || !finalizedIndexRefresh.isTrusted()) {
            markReadinessDegraded("managed_coop_release_finalized_index_refresh_rejected");
            String detail = refreshed != null ? refreshed.detail() : null;
            return Finalization.failed("population_release_finalized_index_refresh_rejected"
                    + (detail == null || detail.isBlank() ? "" : ":" + detail));
        }
        return Finalization.finalized(commitDetail);
    }

    /** Cancels only a definitively pre-spawn admission. */
    @Nonnull
    public CompletableFuture<Boolean> cancelAsync(@Nonnull PreparedRelease prepared,
                                                   @Nonnull String reason) {
        Objects.requireNonNull(prepared, "prepared");
        String requiredReason = requireText(reason, "reason");
        return cancelPreparedPopulationOnlyAsync(prepared, requiredReason).thenCompose(cancelled -> {
            if (!Boolean.TRUE.equals(cancelled)) {
                return CompletableFuture.completedFuture(false);
            }
            return rollbackLifecycle(
                    prepared.fingerprint.operationId(),
                    prepared.fingerprint.operationGeneration(),
                    requiredReason);
        });
    }

    /** Restores v5 HOUSED/FAILED state when no population admission was created and no spawn ran. */
    @Nonnull
    public CompletableFuture<Boolean> rollbackBeforePreparationAsync(
            @Nonnull SpawnReady claim,
            @Nonnull String reason) {
        Objects.requireNonNull(claim, "claim");
        if (!ManagedCoopReleaseClaimRules.isUnconsumedSpawnClaim(claim)) {
            markReadinessDegraded("managed_coop_release_preparation_rollback_claim_invalid");
            return CompletableFuture.completedFuture(false);
        }
        return rollbackLifecycle(
                claim.operationId(), claim.operationGeneration(),
                requireText(reason, "reason"));
    }
    /** Cancels population preparation without rolling a possibly projected resident back to HOUSED. */
    @Nonnull
    public CompletableFuture<Boolean> cancelPreparedPopulationOnlyAsync(
            @Nonnull PreparedRelease prepared, @Nonnull String reason) {
        Objects.requireNonNull(prepared, "prepared");
        final CompletableFuture<Boolean> completion;
        try {
            completion = backend.cancel(prepared.backendHandle, requireText(reason, "reason"));
        } catch (RuntimeException exception) {
            markReadinessDegraded("managed_coop_population_cancel_start_failed");
            return CompletableFuture.completedFuture(false);
        }
        if (completion == null) {
            markReadinessDegraded("managed_coop_population_cancel_stage_missing");
            return CompletableFuture.completedFuture(false);
        }
        return completion.handle((cancelled, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(cancelled)) {
                markReadinessDegraded("managed_coop_population_cancel_failed");
                return false;
            }
            return true;
        });
    }

    @Nonnull
    private CompletableFuture<Boolean> rollbackLifecycle(
            String operationId,
            long operationGeneration,
            String reason) {
        return lifecycleRollbacks.rollback(
                operationId, operationGeneration, reason).thenApply(rolledBack -> {
            if (!rolledBack) {
                markReadinessDegraded("managed_coop_release_lifecycle_rollback_failed");
            }
            return rolledBack;
        });
    }

    public void markReadinessDegraded(@Nonnull String reason) {
        try {
            backend.markReadinessDegraded(requireText(reason, "reason"));
        } catch (RuntimeException ignored) {
            // The retained admission remains conservative if diagnostics also fail.
        }
    }

    private static boolean matches(PreparedRelease prepared, SpawnReady claim) {
        return prepared != null && claim != null
                && prepared.fingerprint.equals(ReleaseFingerprint.from(claim));
    }

    private static CompletableFuture<Preparation> completed(
            PreparationStatus status,
            @Nullable PreparedRelease prepared,
            String detail) {
        return CompletableFuture.completedFuture(new Preparation(status, prepared, detail));
    }

    private static String failureDetail(String stage, Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return stage + "_failed:" + (message != null && !message.isBlank()
                ? message : failure != null ? failure.getClass().getSimpleName() : "unknown");
    }

    private static Throwable unwrap(Throwable failure) {
        return failure.getCause() != null ? failure.getCause() : failure;
    }

    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record PreparedInput(ReleaseRequest request, PopulationReleaseCommitRequest durableRequest,
                                 ReleaseFingerprint fingerprint) {
    }

    private record ReleaseFingerprint(String operationId, String profileId, String residentId,
                                      UUID sourceNpcUuid, UUID plannedTargetUuid, String snapshotHash,
                                      long expectedResidentGeneration, long operationGeneration) {
        static ReleaseFingerprint from(SpawnReady claim) {
            return new ReleaseFingerprint(
                    claim.operationId(), claim.profileId(), claim.residentId(),
                    claim.sourceNpcUuid(), claim.plannedTargetUuid(), claim.snapshotHash(),
                    claim.expectedResidentGeneration(), claim.operationGeneration());
        }
    }

    record BackendPreparation(@Nonnull PreparationStatus status, @Nullable String profileId,
                              @Nullable UUID plannedTargetUuid, @Nullable Object handle,
                              @Nullable String detail) {
    }

    interface AdmissionBackend {
        @Nullable
        CompletableFuture<BackendPreparation> prepare(
                @Nonnull ReleaseRequest request,
                @Nonnull Function<UUID, String> durableContextFactory);
        boolean claim(@Nonnull Object handle);
        boolean writeSpawnHolder(@Nonnull Object handle, @Nonnull Holder<EntityStore> holder);

        @Nullable
        CompletableFuture<CompanionPopulationCommitResult> commit(@Nonnull Object handle);

        @Nullable
        CompletableFuture<Boolean> cancel(@Nonnull Object handle, @Nonnull String reason);

        void markReadinessDegraded(@Nonnull String reason);
    }

    @FunctionalInterface
    interface LifecycleRollbackGateway {
        @Nullable
        CompletableFuture<MutationResult> failBeforeProjection(
                @Nonnull String operationId,
                long expectedOperationGeneration,
                @Nonnull String reason,
                long nowMs);
    }
}
