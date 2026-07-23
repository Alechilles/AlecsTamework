package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcCoopService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionPopulationBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.persistence.sqlite.LegacyProfileSnapshotSink;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/** Focused plugin-level construction, registration, and startup for population reconciliation. */
public final class TameworkPopulationRuntimeLifecycle {
    private TameworkPopulationRuntimeLifecycle() {
    }

    @Nonnull
    public static OwnerPopulationRuntime initialize(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull HytaleLogger logger
    ) {
        OwnerPopulationRuntime runtime = OwnerPopulationRuntime.initialize(persistence);
        CompanionPopulationBootstrapService.BootstrapResult bootstrap = runtime.bootstrapResult();
        logger.at(Level.INFO).log(
                "Companion population ledger loaded: profiles=" + bootstrap.profileCount()
                        + ", operations=" + bootstrap.nonterminalOperationCount()
                        + ", global=" + bootstrap.globalReadiness()
                        + ", perWorld=" + bootstrap.perWorldReadiness()
        );
        return runtime;
    }

    @Nonnull
    public static LinkedServices createLinkedServices(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull HytaleLogger logger,
            @Nonnull CommandLinkedNpcStateSnapshotService stateSnapshots
    ) {
        CommandLinkedNpcCaptureService capture = new CommandLinkedNpcCaptureService(
                persistence.getCaptureRepository(),
                persistence.getHealthService(),
                persistence.getNpcProfileRepository()
        );
        CommandLinkedNpcCoopService coop = new CommandLinkedNpcCoopService(
                persistence.getCoopLedgerRepository(),
                persistence.getHealthService(),
                persistence.getNpcProfileRepository()
        );
        CommandLinkedNpcDeathService death = new CommandLinkedNpcDeathService(
                stateSnapshots,
                persistence.getDeathRepository(),
                persistence.getHealthService(),
                persistence.getNpcProfileRepository()
        );
        CommandLinkedNpcLostService lost = new CommandLinkedNpcLostService(
                logger,
                stateSnapshots,
                capture,
                coop,
                persistence.getLostRepository(),
                persistence.getHealthService()
        );
        return new LinkedServices(capture, coop, death, lost);
    }

    /** Builds the snapshot service against the canonical all-world loaded identity index. */
    @Nonnull
    public static CommandLinkedNpcStateSnapshotService createStateSnapshotService(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull OwnerPopulationRuntime runtime
    ) {
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(runtime, "runtime");
        return new CommandLinkedNpcStateSnapshotService(
                new LegacyProfileSnapshotSink(
                        persistence.getNpcProfileRepository()
                ),
                runtime.loadedNpcIdentityIndex()
        );
    }

    public static void registerSystems(
            @Nonnull ComponentRegistryProxy<EntityStore> registry,
            @Nonnull OwnerPopulationRuntime runtime,
            @Nonnull LinkedServices services,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull CompanionPopulationRuntimeReconciler.WarningSink warningSink
    ) {
        registry.registerSystem(new CompanionLiveInventoryEvidenceSystem(
                runtime.liveEvidenceRevision()
        ));
        CompanionPopulationSystemRegistration.register(
                registry,
                runtime,
                services.capture(),
                services.coop(),
                services.death(),
                services.lost(),
                ownerType,
                warningSink
        );
    }

    public static CompletableFuture<CompanionPopulationReconciliationProgress> start(
            @Nonnull OwnerPopulationRuntime runtime,
            @Nonnull Universe universe,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull Supplier<CompletableFuture<LoadedNpcIdentitySnapshot>> loadedIdentitiesReady) {
        runtime.customContainerReconciliationRegistry().seal(
                "tamework-builtins:no-additional-custom-persisted-item-containers:v1"
        );
        return runtime.startReconciliation(
                universe, ownerType, itemFeatures, loadedIdentitiesReady
        );
    }

    public record LinkedServices(
            @Nonnull CommandLinkedNpcCaptureService capture,
            @Nonnull CommandLinkedNpcCoopService coop,
            @Nonnull CommandLinkedNpcDeathService death,
            @Nonnull CommandLinkedNpcLostService lost
    ) {
        public LinkedServices {
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(coop, "coop");
            Objects.requireNonNull(death, "death");
            Objects.requireNonNull(lost, "lost");
        }
    }
}
