package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionPopulationBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Owns startup recovery, live observation reconciliation, and custom-container declarations. */
public final class CompanionPopulationReconciliationRuntime implements AutoCloseable {
    private static final long SHUTDOWN_FLUSH_TIMEOUT_SECONDS = 2L;

    private final CustomContainerReconciliationRegistry customContainers;
    private final LoadedNpcIdentityIndex loadedNpcIdentityIndex;
    private final CompanionLiveEvidenceRevision liveEvidenceRevision;
    private final CoalescedCompanionPopulationWriter writer;
    private final CompanionPopulationRuntimeReconciler runtimeReconciler;
    private final CompanionPopulationStartupReconciler startupReconciler;

    public CompanionPopulationReconciliationRuntime(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull CompanionPopulationBootstrapService bootstrapService,
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull ClaimOccupancyIndex claimIndex
    ) {
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(bootstrapService, "bootstrapService");
        Objects.requireNonNull(ownerIndex, "ownerIndex");
        Objects.requireNonNull(identityResolver, "identityResolver");
        Objects.requireNonNull(claimIndex, "claimIndex");
        this.customContainers = new CustomContainerReconciliationRegistry();
        this.loadedNpcIdentityIndex = new LoadedNpcIdentityIndex();
        persistence.getCompanionPersistedProjectionEvidenceRegistry()
                .bindLoadedIdentityIndex(loadedNpcIdentityIndex);
        this.liveEvidenceRevision = new CompanionLiveEvidenceRevision();
        persistence.getCompanionPersistedProjectionEvidenceRegistry()
                .bindLiveEvidenceRevision(liveEvidenceRevision);
        this.writer = new CoalescedCompanionPopulationWriter(
                persistence.getCompanionPopulationObservationRepository(),
                (observation, result) -> { }
        );
        this.runtimeReconciler = new CompanionPopulationRuntimeReconciler(
                ownerIndex,
                claimIndex,
                identityResolver,
                writer,
                persistence.getHealthService(),
                liveEvidenceRevision,
                persistence.getIncidentReporter(),
                persistence.getPersistenceScopeFactory()
        );
        writer.setListener(runtimeReconciler);
        this.startupReconciler = new CompanionPopulationStartupReconciler(
                persistence,
                bootstrapService,
                writer,
                runtimeReconciler,
                ownerIndex,
                claimIndex,
                loadedNpcIdentityIndex,
                liveEvidenceRevision
        );
        if (persistence.getHealthService().isHealthy()) {
            ownerIndex.setReadiness(OwnerPopulationReadiness.RECONCILING);
            claimIndex.setReadiness(ClaimOccupancyReadiness.RECONCILING);
        }
    }

    @Nonnull
    public CompanionPopulationRuntimeReconciler runtimeReconciler() {
        return runtimeReconciler;
    }

    @Nonnull
    public CustomContainerReconciliationRegistry customContainers() {
        return customContainers;
    }

    /** Shared all-world loaded identity authority used by runtime probes and startup recovery. */
    @Nonnull
    public LoadedNpcIdentityIndex loadedNpcIdentityIndex() {
        return loadedNpcIdentityIndex;
    }

    /** Shared live inventory/NPC mutation fence used by startup scan validation. */
    @Nonnull
    public CompanionLiveEvidenceRevision liveEvidenceRevision() {
        return liveEvidenceRevision;
    }

    @Nonnull
    public CompletableFuture<CompanionPopulationReconciliationProgress> start(
            @Nonnull Universe universe,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull Supplier<CompletableFuture<LoadedNpcIdentitySnapshot>> loadedIdentitiesReady
    ) {
        return startupReconciler.start(
                universe, ownerType, itemFeatures, customContainers, loadedIdentitiesReady
        );
    }

    @Nonnull
    public CompanionPopulationReconciliationProgress progress() {
        return startupReconciler.progress();
    }

    /** Re-runs startup reconciliation after a quarantined source journal is repaired exactly. */
    @Nonnull
    public CompletableFuture<CompanionPopulationReconciliationProgress>
    restartAfterExternalRepair() {
        return startupReconciler.restartAfterExternalRepair();
    }

    @Override
    public void close() {
        startupReconciler.close();
        try {
            writer.flushPendingNow().get(SHUTDOWN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception exception) {
            // Shutdown remains bounded; persistence health already captures failed submissions.
        } finally {
            writer.close();
        }
    }
}
