package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.CaptureOutcome;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.OutcomeStatus;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.ReleaseAttempt;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.ReleaseOutcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
                              int blockX,
                              int blockY,
                              int blockZ,
                              int blockRotationIndex,
                              double offsetX,
                              double offsetY,
                              double offsetZ) {
        public ReleaseSite {
            if (worldName == null || worldName.isBlank()) {
                throw new IllegalArgumentException("worldName must not be blank");
            }
            if (!Double.isFinite(offsetX) || !Double.isFinite(offsetY)
                    || !Double.isFinite(offsetZ)) {
                throw new IllegalArgumentException("release offsets must be finite");
            }
        }

        @Nonnull
        static ReleaseSite copyOf(@Nonnull ManagedCoopContext context) {
            var offset = context.config().getLifecycleRules().getResidentSpawnOffset();
            return new ReleaseSite(
                    context.worldName(),
                    context.authorityKey().x(),
                    context.authorityKey().y(),
                    context.authorityKey().z(),
                    context.blockRotationIndex(),
                    offset.getX(), offset.getY(), offset.getZ());
        }
    }

    /** Stable release projection command safe to queue by world name. */
    public record ReleaseProjectionCommand(@Nonnull SpawnReady claim,
                                           @Nonnull ResidentRecord resident,
                                           @Nonnull ReleaseSite site) {
        public ReleaseProjectionCommand {
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(resident, "resident");
            Objects.requireNonNull(site, "site");
        }
    }

    private final CaptureGateway captures;
    private final RetirementGateway retirements;
    private final ReleaseClaimGateway releases;
    private final ReleaseProjectionGateway projections;
    private final Supplier<UUID> plannedUuidSource;

    public ManagedCoopRuntimeOperationDispatcher(
            @Nonnull ManagedCoopCaptureRuntimeAdapter captureAdapter,
            @Nonnull ManagedCoopCaptureSourceRetirementService retirementService,
            @Nonnull ManagedCoopReleaseCoordinator releaseCoordinator,
            @Nonnull ManagedCoopReleaseRuntimeAdapter releaseAdapter) {
        this(
                captureAdapter::capture,
                retirementService::retire,
                releaseCoordinator::coordinate,
                new HytaleManagedCoopReleaseProjectionGateway(releaseAdapter),
                UUID::randomUUID);
    }

    ManagedCoopRuntimeOperationDispatcher(@Nonnull CaptureGateway captures,
                                          @Nonnull RetirementGateway retirements,
                                          @Nonnull ReleaseClaimGateway releases,
                                          @Nonnull ReleaseProjectionGateway projections,
                                          @Nonnull Supplier<UUID> plannedUuidSource) {
        this.captures = Objects.requireNonNull(captures, "captures");
        this.retirements = Objects.requireNonNull(retirements, "retirements");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.plannedUuidSource = Objects.requireNonNull(plannedUuidSource, "plannedUuidSource");
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
        Objects.requireNonNull(resident, "resident");
        final ReleaseSite site;
        final UUID plannedUuid;
        final CompletableFuture<ReleaseOutcome> release;
        try {
            site = ReleaseSite.copyOf(context);
            plannedUuid = Objects.requireNonNull(plannedUuidSource.get(), "planned release UUID");
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
