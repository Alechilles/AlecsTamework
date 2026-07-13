package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.CaptureOutcome;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.OutcomeStatus;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.ReleaseAttempt;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.ReleaseOutcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRecoveryService.ProjectionToken;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Connects synchronous managed-coop sweep decisions to durable v5 lifecycle services.
 *
 * <p>Capture calls consume their live store/reference before the first continuation. Release calls
 * copy the physical site before persistence and delegate projection to a world-name gateway that
 * re-resolves the owning store. No vanilla admission, legacy ledger, or pre-claim spawn exists on
 * this boundary.</p>
 */
public final class ManagedCoopRuntimeOperationDispatcher {
    public enum ReleaseSitePolicy {
        EXACT_MANAGED_COOP,
        EXACT_MANAGED_OR_DISABLED_REMOVAL
    }

    public enum DispatchStatus {
        CAPTURED,
        CAPTURE_DEDUPLICATED,
        CAPTURE_FAILED,
        RELEASED,
        RELEASE_DEDUPLICATED,
        RELEASE_FAILED
    }

    public record DispatchOutcome(@Nonnull DispatchStatus status,
                                  @Nullable String operationId,
                                  @Nullable String detail) {
        public DispatchOutcome {
            Objects.requireNonNull(status, "status");
        }
    }

    /** Immutable physical release inputs copied before any persistence continuation. */
    public record ReleaseSite(@Nonnull String worldName,
                              @Nonnull String expectedCoopId,
                              int blockX,
                              int blockY,
                              int blockZ,
                              int blockRotationIndex,
                              double offsetX,
                              double offsetY,
                              double offsetZ,
                              @Nonnull ReleaseSitePolicy policy) {
        public ReleaseSite {
            if (worldName == null || worldName.isBlank()) {
                throw new IllegalArgumentException("worldName must not be blank");
            }
            worldName = worldName.trim().toLowerCase(Locale.ROOT);
            if (expectedCoopId == null || expectedCoopId.isBlank()) {
                throw new IllegalArgumentException("expectedCoopId must not be blank");
            }
            expectedCoopId = expectedCoopId.trim().toLowerCase(Locale.ROOT);
            if (!Double.isFinite(offsetX) || !Double.isFinite(offsetY)
                    || !Double.isFinite(offsetZ)) {
                throw new IllegalArgumentException("release offsets must be finite");
            }
            Objects.requireNonNull(policy, "policy");
        }

        @Nonnull
        static ReleaseSite copyOf(@Nonnull ManagedCoopContext context) {
            var offset = context.config().getLifecycleRules().getResidentSpawnOffset();
            return new ReleaseSite(
                    context.worldName(),
                    context.coopId(),
                    context.authorityKey().x(),
                    context.authorityKey().y(),
                    context.authorityKey().z(),
                    context.blockRotationIndex(),
                    offset.getX(), offset.getY(), offset.getZ(),
                    ReleaseSitePolicy.EXACT_MANAGED_COOP);
        }

        /** Reconstructs the immutable site for an authority durably disabled after block removal. */
        @Nonnull
        static ReleaseSite copyOfDisabled(@Nonnull AuthorityRecord authority) {
            Objects.requireNonNull(authority, "authority");
            if (!authority.active() || authority.state() != AuthorityState.DISABLED
                    || !authority.authorityId().equals(authority.authorityKey().authorityId())) {
                throw new IllegalArgumentException("authority is not a durable disabled marker");
            }
            TwCoopConfig config = null;
            try {
                config = TwCoopConfig.resolveForCoop(authority.coopId());
            } catch (RuntimeException ignored) {
                // The durable removal path remains recoverable if config assets are unavailable.
            }
            double offsetX = 0.0;
            double offsetY = 0.0;
            double offsetZ = 3.0;
            if (config != null) {
                var offset = config.getLifecycleRules().getResidentSpawnOffset();
                offsetX = offset.getX();
                offsetY = offset.getY();
                offsetZ = offset.getZ();
            }
            ManagedCoopAuthorityKey key = authority.authorityKey();
            return new ReleaseSite(
                    key.worldName(), authority.coopId(), key.x(), key.y(), key.z(), 0,
                    offsetX, offsetY, offsetZ,
                    ReleaseSitePolicy.EXACT_MANAGED_OR_DISABLED_REMOVAL);
        }

        @Nonnull
        ManagedCoopAuthorityKey authorityKey() {
            return new ManagedCoopAuthorityKey(worldName, blockX, blockY, blockZ);
        }
    }

    /** Stable release projection command safe to queue by world name. */
    public record ReleaseProjectionCommand(@Nonnull SpawnReady claim,
                                           @Nonnull ResidentRecord resident,
                                           @Nonnull ReleaseSite site,
                                           @Nullable ProjectionToken recoveryToken) {
        public ReleaseProjectionCommand(
                SpawnReady claim,
                ResidentRecord resident,
                ReleaseSite site) {
            this(claim, resident, site, null);
        }

        public ReleaseProjectionCommand {
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(resident, "resident");
            Objects.requireNonNull(site, "site");
            if (!resident.authorityKey().equals(site.authorityKey())
                    || !resident.coopId().equalsIgnoreCase(site.expectedCoopId())) {
                throw new IllegalArgumentException(
                        "release resident does not match immutable physical site");
            }
            if (!claim.authorityKey().equals(site.authorityKey())
                    || !claim.coopId().equalsIgnoreCase(site.expectedCoopId())
                    || !claim.residentId().equals(resident.residentId())
                    || claim.residentSlot() != resident.residentSlot()
                    || !claim.profileId().equals(resident.profileId())) {
                throw new IllegalArgumentException(
                        "release claim does not match immutable resident site");
            }
        }
    }

    private final CaptureGateway captures;
    private final RetirementGateway retirements;
    private final ReleaseClaimGateway releases;
    private final ReleaseProjectionGateway projections;
    private final Supplier<UUID> plannedUuidSource;
    private final ManagedCoopLifecycleMutationGate lifecycleGate;
    private final Set<String> releaseInFlightProfiles = ConcurrentHashMap.newKeySet();

    public ManagedCoopRuntimeOperationDispatcher(
            @Nonnull ManagedCoopCaptureRuntimeAdapter captureAdapter,
            @Nonnull ManagedCoopCaptureSourceRetirementService retirementService,
            @Nonnull ManagedCoopReleaseCoordinator releaseCoordinator,
            @Nonnull ManagedCoopReleaseRuntimeAdapter releaseAdapter,
            @Nonnull ManagedCoopReleasePopulationCoordinator populations) {
        this(
                captureAdapter::capture,
                retirementService::retire,
                releaseCoordinator::coordinate,
                new HytaleManagedCoopReleaseProjectionGateway(releaseAdapter, populations),
                UUID::randomUUID);
    }

    /** Uses paired v5 authority evidence so disabled removed-coop sites can be revalidated. */
    public ManagedCoopRuntimeOperationDispatcher(
            @Nonnull ManagedCoopCaptureRuntimeAdapter captureAdapter,
            @Nonnull ManagedCoopCaptureSourceRetirementService retirementService,
            @Nonnull ManagedCoopReleaseCoordinator releaseCoordinator,
            @Nonnull ManagedCoopReleaseRuntimeAdapter releaseAdapter,
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull ManagedCoopReleasePopulationCoordinator populations) {
        this(
                captureAdapter::capture,
                retirementService::retire,
                releaseCoordinator::coordinate,
                new HytaleManagedCoopReleaseProjectionGateway(
                        releaseAdapter, residentIndex, compositeIndexes, populations),
                UUID::randomUUID);
    }

    ManagedCoopRuntimeOperationDispatcher(
            @Nonnull ManagedCoopCaptureRuntimeAdapter captureAdapter,
            @Nonnull ManagedCoopCaptureSourceRetirementService retirementService,
            @Nonnull ManagedCoopReleaseCoordinator releaseCoordinator,
            @Nonnull ManagedCoopReleaseRuntimeAdapter releaseAdapter,
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull ManagedCoopReleasePopulationCoordinator populations,
            @Nonnull ManagedCoopLifecycleMutationGate lifecycleGate) {
        this(
                captureAdapter::capture,
                retirementService::retire,
                releaseCoordinator::coordinate,
                new HytaleManagedCoopReleaseProjectionGateway(
                        releaseAdapter, residentIndex, compositeIndexes, populations),
                UUID::randomUUID,
                lifecycleGate);
    }

    ManagedCoopRuntimeOperationDispatcher(@Nonnull CaptureGateway captures,
                                          @Nonnull RetirementGateway retirements,
                                          @Nonnull ReleaseClaimGateway releases,
                                          @Nonnull ReleaseProjectionGateway projections,
                                          @Nonnull Supplier<UUID> plannedUuidSource) {
        this(captures, retirements, releases, projections, plannedUuidSource,
                new ManagedCoopLifecycleMutationGate());
    }

    ManagedCoopRuntimeOperationDispatcher(@Nonnull CaptureGateway captures,
                                          @Nonnull RetirementGateway retirements,
                                          @Nonnull ReleaseClaimGateway releases,
                                          @Nonnull ReleaseProjectionGateway projections,
                                          @Nonnull Supplier<UUID> plannedUuidSource,
                                          @Nonnull ManagedCoopLifecycleMutationGate lifecycleGate) {
        this.captures = Objects.requireNonNull(captures, "captures");
        this.retirements = Objects.requireNonNull(retirements, "retirements");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.plannedUuidSource = Objects.requireNonNull(plannedUuidSource, "plannedUuidSource");
        this.lifecycleGate = Objects.requireNonNull(lifecycleGate, "lifecycleGate");
    }

    /** Starts capture on the current owning thread, then retains immutable lifecycle values only. */
    @Nonnull
    public CompletableFuture<DispatchOutcome> capture(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> sourceRef,
            @Nonnull ManagedCoopContext context,
            @Nonnull ManagedCoopCaptureCandidate candidate) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(sourceRef, "sourceRef");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(candidate, "candidate");
        if (!lifecycleGate.runtimeReady()) {
            return completed(DispatchStatus.CAPTURE_DEDUPLICATED, null,
                    "managed_coop_runtime_authority_not_ready");
        }
        ManagedCoopLifecycleMutationGate.Lease lease = lifecycleGate.tryAcquire(
                "runtime-capture:" + candidate.npcUuid());
        if (lease == null) {
            return completed(DispatchStatus.CAPTURE_DEDUPLICATED, null,
                    "managed_coop_lifecycle_operation_in_flight");
        }
        return startCapture(store, sourceRef, context, candidate)
                .whenComplete((ignored, failure) -> lifecycleGate.release(lease));
    }

    @Nonnull
    private CompletableFuture<DispatchOutcome> startCapture(
            Store<EntityStore> store,
            Ref<EntityStore> sourceRef,
            ManagedCoopContext context,
            ManagedCoopCaptureCandidate candidate) {
        final CompletableFuture<CaptureOutcome> capture;
        try {
            store.assertThread();
            capture = captures.capture(
                    store, sourceRef, context, candidate.runtimeCandidate());
        } catch (RuntimeException exception) {
            return completed(DispatchStatus.CAPTURE_FAILED, null,
                    failureDetail("managed_coop_capture_dispatch", exception));
        }
        if (capture == null) {
            return completed(DispatchStatus.CAPTURE_FAILED, null,
                    "managed_coop_capture_future_missing");
        }
        return capture.thenCompose(this::afterCapture).exceptionally(failure ->
                new DispatchOutcome(
                        DispatchStatus.CAPTURE_FAILED, null,
                        failureDetail("managed_coop_capture_completion", failure)));
    }

    /** Starts a durable release claim and queues world-thread projection only for SPAWN_READY. */
    @Nonnull
    public CompletableFuture<DispatchOutcome> release(
            @Nonnull ManagedCoopContext context,
            @Nonnull ResidentRecord resident,
            long requestedAtMs) {
        Objects.requireNonNull(context, "context");
        final ReleaseSite site;
        try {
            site = ReleaseSite.copyOf(context);
        } catch (RuntimeException exception) {
            return completed(DispatchStatus.RELEASE_FAILED, null,
                    failureDetail("managed_coop_release_site", exception));
        }
        return release(site, resident, requestedAtMs);
    }

    /** Starts a durable release from a copied site, including confirmed removed-coop recovery. */
    @Nonnull
    public CompletableFuture<DispatchOutcome> release(
            @Nonnull ReleaseSite site,
            @Nonnull ResidentRecord resident,
            long requestedAtMs) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(resident, "resident");
        if (!lifecycleGate.runtimeReady()) {
            return completed(DispatchStatus.RELEASE_DEDUPLICATED, null,
                    "managed_coop_runtime_authority_not_ready");
        }
        final UUID plannedUuid;
        try {
            if (!resident.authorityKey().equals(site.authorityKey())
                    || !resident.coopId().equalsIgnoreCase(site.expectedCoopId())) {
                throw new IllegalArgumentException("release resident does not match release site");
            }
            plannedUuid = Objects.requireNonNull(plannedUuidSource.get(), "planned release UUID");
        } catch (RuntimeException exception) {
            return completed(DispatchStatus.RELEASE_FAILED, null,
                    failureDetail("managed_coop_release_dispatch", exception));
        }
        if (!releaseInFlightProfiles.add(resident.profileId())) {
            return completed(DispatchStatus.RELEASE_DEDUPLICATED, null,
                    "managed_coop_release_profile_already_in_flight");
        }
        ManagedCoopLifecycleMutationGate.Lease lease = lifecycleGate.tryAcquire(
                "runtime-release:" + resident.profileId());
        if (lease == null) {
            releaseInFlightProfiles.remove(resident.profileId());
            return completed(DispatchStatus.RELEASE_DEDUPLICATED, null,
                    "managed_coop_lifecycle_operation_in_flight");
        }
        CompletableFuture<DispatchOutcome> pipeline = startRelease(
                site, resident, plannedUuid, requestedAtMs);
        return pipeline.whenComplete((ignored, failure) -> {
            releaseInFlightProfiles.remove(resident.profileId());
            lifecycleGate.release(lease);
        });
    }

    /** True while this process owns a release claim or its resulting live projection. */
    boolean releaseInFlight(@Nonnull String profileId) {
        return releaseInFlightProfiles.contains(Objects.requireNonNull(profileId, "profileId"));
    }

    @Nonnull
    private CompletableFuture<DispatchOutcome> startRelease(
            ReleaseSite site,
            ResidentRecord resident,
            UUID plannedUuid,
            long requestedAtMs) {
        final CompletableFuture<ReleaseOutcome> release;
        try {
            release = releases.coordinate(new ReleaseAttempt(resident, plannedUuid, requestedAtMs));
        } catch (RuntimeException exception) {
            return completed(DispatchStatus.RELEASE_FAILED, null,
                    failureDetail("managed_coop_release_dispatch", exception));
        }
        if (release == null) {
            return completed(DispatchStatus.RELEASE_FAILED, null,
                    "managed_coop_release_future_missing");
        }
        return release.thenCompose(outcome -> afterRelease(outcome, resident, site))
                .exceptionally(failure -> new DispatchOutcome(
                        DispatchStatus.RELEASE_FAILED, null,
                        failureDetail("managed_coop_release_completion", failure)));
    }

    @Nonnull
    CompletableFuture<DispatchOutcome> afterCapture(@Nullable CaptureOutcome capture) {
        if (capture == null) {
            return completed(DispatchStatus.CAPTURE_FAILED, null,
                    "managed_coop_capture_outcome_missing");
        }
        if (capture.status() == OutcomeStatus.DEDUPLICATED) {
            return completed(DispatchStatus.CAPTURE_DEDUPLICATED, null, capture.detail());
        }
        if (!capture.isRetirementReady()) {
            return completed(DispatchStatus.CAPTURE_FAILED, null, capture.detail());
        }
        RetirementReady ready = capture.retirementReady();
        CompletableFuture<Outcome> retirement = retirements.retire(ready);
        if (retirement == null) {
            return completed(DispatchStatus.CAPTURE_FAILED, ready.operationId(),
                    "managed_coop_retirement_future_missing");
        }
        return retirement.thenApply(outcome -> mapRetirement(ready, outcome));
    }

    @Nonnull
    private DispatchOutcome mapRetirement(RetirementReady ready, @Nullable Outcome outcome) {
        if (outcome == null) {
            return new DispatchOutcome(
                    DispatchStatus.CAPTURE_FAILED, ready.operationId(),
                    "managed_coop_retirement_outcome_missing");
        }
        return switch (outcome.status()) {
            case COMPLETED, ALREADY_COMPLETE -> new DispatchOutcome(
                    DispatchStatus.CAPTURED, ready.operationId(), outcome.detail());
            case BLOCKED, FAILED -> new DispatchOutcome(
                    DispatchStatus.CAPTURE_FAILED, ready.operationId(), outcome.detail());
        };
    }

    @Nonnull
    private CompletableFuture<DispatchOutcome> afterRelease(
            @Nullable ReleaseOutcome release,
            ResidentRecord resident,
            ReleaseSite site) {
        if (release == null) {
            return completed(DispatchStatus.RELEASE_FAILED, null,
                    "managed_coop_release_outcome_missing");
        }
        if (release.status() == ManagedCoopReleaseCoordinator.OutcomeStatus.DEDUPLICATED) {
            return completed(DispatchStatus.RELEASE_DEDUPLICATED, null, release.detail());
        }
        if (!release.isSpawnReady() || release.spawnReady() == null) {
            DispatchStatus status = release.status()
                    == ManagedCoopReleaseCoordinator.OutcomeStatus.ALREADY_PROJECTED
                    ? DispatchStatus.RELEASE_DEDUPLICATED
                    : DispatchStatus.RELEASE_FAILED;
            String operationId = release.spawnReady() != null
                    ? release.spawnReady().operationId() : null;
            return completed(status, operationId, release.detail());
        }
        SpawnReady ready = release.spawnReady();
        CompletableFuture<ManagedCoopReleaseSpawnOrchestrator.Outcome> projection =
                projections.project(new ReleaseProjectionCommand(ready, resident, site));
        if (projection == null) {
            return completed(DispatchStatus.RELEASE_FAILED, ready.operationId(),
                    "managed_coop_projection_future_missing");
        }
        return projection.thenApply(outcome -> outcome != null && outcome.finalized()
                ? new DispatchOutcome(
                        DispatchStatus.RELEASED, ready.operationId(), outcome.detail())
                : new DispatchOutcome(
                        DispatchStatus.RELEASE_FAILED, ready.operationId(),
                        outcome != null ? outcome.detail() : "managed_coop_projection_outcome_missing"));
    }

    private CompletableFuture<DispatchOutcome> completed(
            DispatchStatus status, @Nullable String operationId, @Nullable String detail) {
        return CompletableFuture.completedFuture(new DispatchOutcome(status, operationId, detail));
    }

    private static String failureDetail(String stage, Throwable failure) {
        Throwable cause = failure != null && failure.getCause() != null ? failure.getCause() : failure;
        String message = cause != null ? cause.getMessage() : null;
        return stage + (message == null || message.isBlank()
                ? ":" + (cause != null ? cause.getClass().getSimpleName() : "unknown")
                : ":" + message);
    }

    @FunctionalInterface
    interface CaptureGateway {
        CompletableFuture<CaptureOutcome> capture(
                Store<EntityStore> store,
                Ref<EntityStore> sourceRef,
                ManagedCoopContext context,
                ManagedCoopCaptureRuntimeAdapter.Candidate candidate);
    }

    @FunctionalInterface
    interface RetirementGateway {
        CompletableFuture<Outcome> retire(RetirementReady ready);
    }

    @FunctionalInterface
    interface ReleaseClaimGateway {
        CompletableFuture<ReleaseOutcome> coordinate(ReleaseAttempt attempt);
    }

    @FunctionalInterface
    interface ReleaseProjectionGateway {
        CompletableFuture<ManagedCoopReleaseSpawnOrchestrator.Outcome> project(
                ReleaseProjectionCommand command);
    }
}
