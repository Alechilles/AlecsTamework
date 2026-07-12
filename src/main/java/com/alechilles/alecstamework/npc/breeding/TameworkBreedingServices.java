package com.alechilles.alecstamework.npc.breeding;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import javax.annotation.Nonnull;

/**
 * Plugin-owned seam for the one shared breeding registry, planner, and capacity policy.
 *
 * <p>Hytale constructs manual NPC actions and passive systems through different lifecycles. The
 * lazily initialized shared instance is therefore deliberate: both entrypoints must observe the
 * same active reservations. Tests and future dependency-injected coordinators may construct an
 * isolated instance instead.
 */
public final class TameworkBreedingServices implements AutoCloseable {
    private final BreedingBirthJobRegistry jobRegistry;
    private final BreedingBirthPlanService birthPlanService;
    private final BreedingPopulationAdmissionService populationAdmissionService;
    private final BreedingJobDiagnosticsService jobDiagnosticsService;
    private final BreedingPreparedPopulationRegistry preparedPopulationRegistry;

    /** Creates isolated production services. Prefer {@link #shared()} from runtime entrypoints. */
    public TameworkBreedingServices() {
        this(new BreedingBirthJobRegistry(), new BreedingBirthPlanService());
    }

    /** Creates isolated services with an injected fertility source for deterministic tests. */
    public TameworkBreedingServices(@Nonnull DoubleSupplier fertilityRollSource) {
        this(
                new BreedingBirthJobRegistry(),
                new BreedingBirthPlanService(
                        Objects.requireNonNull(fertilityRollSource, "fertilityRollSource")
                )
        );
    }

    TameworkBreedingServices(BreedingBirthJobRegistry jobRegistry,
                              BreedingBirthPlanService birthPlanService) {
        this.jobRegistry = Objects.requireNonNull(jobRegistry, "jobRegistry");
        this.birthPlanService = Objects.requireNonNull(birthPlanService, "birthPlanService");
        this.populationAdmissionService = new BreedingPopulationAdmissionService(jobRegistry);
        this.jobDiagnosticsService = new BreedingJobDiagnosticsService();
        this.preparedPopulationRegistry = new BreedingPreparedPopulationRegistry();
    }

    /** Returns the shared plugin-wide seam consumed by both breeding entrypoints. */
    @Nonnull
    public static TameworkBreedingServices shared() {
        return SharedHolder.INSTANCE.get();
    }

    /**
     * Closes the current plugin-owned bundle and atomically installs a fresh bundle for reload.
     *
     * <p>Delayed callbacks that still hold the retired bundle observe its permanently closed
     * registry. Runtime entrypoints resolved after this call observe only the fresh bundle, so an
     * in-process plugin reload cannot inherit jobs or the closed state from the prior lifecycle.</p>
     */
    public static void shutdownShared() {
        TameworkBreedingServices retired = SharedHolder.INSTANCE.getAndSet(
                new TameworkBreedingServices()
        );
        retired.close();
    }

    @Nonnull
    public BreedingBirthJobRegistry jobRegistry() {
        return jobRegistry;
    }

    @Nonnull
    public BreedingBirthPlanService birthPlanService() {
        return birthPlanService;
    }

    @Nonnull
    public BreedingPopulationAdmissionService populationAdmissionService() {
        return populationAdmissionService;
    }

    /** Returns process-local birth outcome and capacity diagnostics for operator commands. */
    @Nonnull
    public BreedingJobDiagnosticsService jobDiagnostics() {
        return jobDiagnosticsService;
    }

    /** Owns durable owner/claim capabilities attached to active job units. */
    @Nonnull
    public BreedingPreparedPopulationRegistry preparedPopulationRegistry() {
        return preparedPopulationRegistry;
    }

    /** Permanently closes one world/store scope and releases its reservations. */
    public void clearScope(@Nonnull Object storeScope) {
        preparedPopulationRegistry.clearScope(storeScope, "breeding-world-scope-closed");
        jobRegistry.clearScope(storeScope);
        jobDiagnosticsService.clearScope(storeScope);
    }

    /** Permanently closes this service bundle and releases every active reservation. */
    @Override
    public void close() {
        preparedPopulationRegistry.clearAll("breeding-runtime-closed");
        jobRegistry.clearAll();
        jobDiagnosticsService.clearAll();
    }

    private static final class SharedHolder {
        private static final AtomicReference<TameworkBreedingServices> INSTANCE =
                new AtomicReference<>(new TameworkBreedingServices());

        private SharedHolder() {
        }
    }
}
