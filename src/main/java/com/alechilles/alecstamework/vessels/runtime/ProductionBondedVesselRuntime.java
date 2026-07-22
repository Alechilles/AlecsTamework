package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.internal.BondedVesselsApiDelegate;
import com.alechilles.alecstamework.api.internal.TameworkApiImpl;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.assets.TwSpawnerVesselConfigResolver;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import com.alechilles.alecstamework.vessels.BondedVesselEventSink;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Fully composed production vessel facade and its fail-closed activation boundary. */
public final class ProductionBondedVesselRuntime {
    private static final long TOKEN_LIFETIME_MS = 30_000L;
    private static final int RECOVERY_LIMIT = 128;

    private final ProductionBondedVesselEvidenceAuthority evidenceAuthority;
    private final ProductionBondedVesselMutationAuthority mutationAuthority;
    private final BondedVesselsApiDelegate apiDelegate;
    private final BondedVesselRuntimeBootstrap bootstrap;
    private final BondedVesselInitialBindingService initialBindings;
    private final BondedVesselLifecycleObserver lifecycleObserver;
    private final BondedVesselItemProjectionReconciler itemProjectionReconciler;

    private ProductionBondedVesselRuntime(
            ProductionBondedVesselEvidenceAuthority evidenceAuthority,
            ProductionBondedVesselMutationAuthority mutationAuthority,
            BondedVesselsApiDelegate apiDelegate,
            BondedVesselRuntimeBootstrap bootstrap,
            BondedVesselInitialBindingService initialBindings,
            BondedVesselLifecycleObserver lifecycleObserver,
            BondedVesselItemProjectionReconciler itemProjectionReconciler) {
        this.evidenceAuthority = evidenceAuthority;
        this.mutationAuthority = mutationAuthority;
        this.apiDelegate = apiDelegate;
        this.bootstrap = bootstrap;
        this.initialBindings = initialBindings;
        this.lifecycleObserver = lifecycleObserver;
        this.itemProjectionReconciler = itemProjectionReconciler;
    }

    @Nonnull
    public static ProductionBondedVesselRuntime compose(
            @Nonnull TameworkApiImpl api,
            @Nonnull BondedVesselRepository repository,
            @Nonnull Universe universe,
            @Nonnull ItemFeatureRegistry itemConfigs,
            @Nonnull ProductionBondedVesselEvidenceAuthority.ProjectionEvidencePort projections,
            @Nonnull ProductionBondedVesselMutationAuthority.CanonicalProfilePort profiles,
            @Nonnull ProductionBondedVesselMutationAuthority.UnifiedPopulationPort populations,
            @Nonnull ProductionBondedVesselMutationAuthority.WorldProjectionPort world,
            @Nullable BondedVesselEventSink events,
            @Nonnull Executor executor,
            @Nonnull LongSupplier wallClockMs,
            @Nonnull LongSupplier monotonicNanos) {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(universe, "universe");
        Objects.requireNonNull(itemConfigs, "itemConfigs");
        Objects.requireNonNull(executor, "executor");
        ProductionBondedVesselEvidenceAuthority evidence =
                new ProductionBondedVesselEvidenceAuthority(
                        new HytaleBondedVesselExactInventoryPort(universe), projections);
        ProductionBondedVesselMutationAuthority mutation =
                new ProductionBondedVesselMutationAuthority(
                        profiles, populations, world, executor);
        ProductionBondedVesselTransitionPlanner planner =
                new ProductionBondedVesselTransitionPlanner(
                        new TwSpawnerVesselConfigResolver(itemConfigs),
                        new BondedVesselItemFingerprintCodec());
        BondedVesselsApiDelegate delegate = BondedVesselsApiDelegate.journalBacked(
                repository, planner, evidence, mutation, events, executor,
                wallClockMs, monotonicNanos, TOKEN_LIFETIME_MS, RECOVERY_LIMIT);
        BondedVesselInitialBindingService initialBindings =
                new BondedVesselInitialBindingService(
                        repository, new HytaleBondedVesselInitialSourceFinalizer(universe),
                        events, executor, wallClockMs);
        BondedVesselLifecycleObserver lifecycleObserver =
                new BondedVesselLifecycleObserver(
                        repository, new TwSpawnerVesselConfigResolver(itemConfigs),
                        evidence, events, executor, wallClockMs);
        BondedVesselItemProjectionReconciler itemProjectionReconciler =
                new BondedVesselItemProjectionReconciler(
                        repository, events, executor, wallClockMs);
        BondedVesselRuntimeBootstrap bootstrap = new BondedVesselRuntimeBootstrap(
                api, delegate, initialBindings,
                evidence::isCapabilityReady, mutation::isCapabilityReady);
        return new ProductionBondedVesselRuntime(
                evidence, mutation, delegate, bootstrap, initialBindings, lifecycleObserver,
                itemProjectionReconciler);
    }

    @Nonnull
    public ProductionBondedVesselEvidenceAuthority evidenceAuthority() {
        return evidenceAuthority;
    }

    @Nonnull
    public ProductionBondedVesselMutationAuthority mutationAuthority() {
        return mutationAuthority;
    }

    @Nonnull
    public BondedVesselsApiDelegate apiDelegate() {
        return apiDelegate;
    }

    @Nonnull
    public BondedVesselRuntimeBootstrap bootstrap() {
        return bootstrap;
    }

    @Nonnull
    public BondedVesselInitialBindingService initialBindings() {
        return initialBindings;
    }

    @Nonnull
    public BondedVesselLifecycleObserver lifecycleObserver() {
        return lifecycleObserver;
    }

    /** Sealed all-inventory observer installed into population startup reconciliation. */
    @Nonnull
    public BondedVesselItemProjectionReconciler itemProjectionReconciler() {
        return itemProjectionReconciler;
    }
}
