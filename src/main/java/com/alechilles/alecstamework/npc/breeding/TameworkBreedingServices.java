package com.alechilles.alecstamework.npc.breeding;

import java.util.Objects;
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
    }

    /** Returns the shared plugin-wide seam consumed by both breeding entrypoints. */
    @Nonnull
    public static TameworkBreedingServices shared() {
        return SharedHolder.INSTANCE;
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

    /** Permanently closes one world/store scope and releases its reservations. */
    public void clearScope(@Nonnull Object storeScope) {
        jobRegistry.clearScope(storeScope);
    }

    /** Permanently closes this service bundle and releases every active reservation. */
    @Override
    public void close() {
        jobRegistry.clearAll();
    }

    private static final class SharedHolder {
        private static final TameworkBreedingServices INSTANCE = new TameworkBreedingServices();

        private SharedHolder() {
        }
    }
}
