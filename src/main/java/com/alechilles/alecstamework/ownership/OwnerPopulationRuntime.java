package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Groups the owner population index, identity cache, durable coordinator, and bootstrap result.
 */
public final class OwnerPopulationRuntime {
    private final OwnerPopulationIndex index;
    private final CompanionIdentityResolver identityResolver;
    private final OwnerPopulationAdmissionCoordinator admissionCoordinator;
    private final OwnerComponentMutationService mutationService;
    private final OwnerMutationScheduler mutationScheduler;
    private final CompanionPopulationBootstrapService.BootstrapResult bootstrapResult;

    private OwnerPopulationRuntime(
            @Nonnull OwnerPopulationIndex index,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull OwnerPopulationAdmissionCoordinator admissionCoordinator,
            @Nonnull OwnerComponentMutationService mutationService,
            @Nonnull OwnerMutationScheduler mutationScheduler,
            @Nonnull CompanionPopulationBootstrapService.BootstrapResult bootstrapResult
    ) {
        this.index = index;
        this.identityResolver = identityResolver;
        this.admissionCoordinator = admissionCoordinator;
        this.mutationService = mutationService;
        this.mutationScheduler = mutationScheduler;
        this.bootstrapResult = bootstrapResult;
    }

    @Nonnull
    public static OwnerPopulationRuntime initialize(@Nonnull TameworkPersistenceRuntime persistence) {
        Objects.requireNonNull(persistence, "persistence");
        OwnerPopulationIndex index = new OwnerPopulationIndex();
        CompanionIdentityResolver identityResolver = new CompanionIdentityResolver();
        CompanionPopulationBootstrapService.BootstrapResult bootstrap =
                new CompanionPopulationBootstrapService(
                        persistence.getCompanionPopulationRepository(),
                        persistence.getCompanionPopulationCoverageRepository(),
                        persistence.getCompanionIdentityRepository(),
                        persistence.getHealthService(),
                        index,
                        identityResolver
                ).load();
        OwnerPopulationAdmissionCoordinator coordinator = new OwnerPopulationAdmissionCoordinator(
                index,
                persistence.getCompanionPopulationRepository(),
                persistence.getHealthService()
        );
        OwnerComponentMutationService mutationService = new OwnerComponentMutationService(coordinator);
        OwnerMutationScheduler mutationScheduler = new OwnerMutationScheduler(
                index,
                identityResolver,
                coordinator,
                mutationService
        );
        return new OwnerPopulationRuntime(
                index,
                identityResolver,
                coordinator,
                mutationService,
                mutationScheduler,
                bootstrap
        );
    }

    @Nonnull
    public OwnerPopulationIndex index() {
        return index;
    }

    @Nonnull
    public CompanionIdentityResolver identityResolver() {
        return identityResolver;
    }

    @Nonnull
    public OwnerPopulationAdmissionCoordinator admissionCoordinator() {
        return admissionCoordinator;
    }

    @Nonnull
    public OwnerComponentMutationService mutationService() {
        return mutationService;
    }

    @Nonnull
    public OwnerMutationScheduler mutationScheduler() {
        return mutationScheduler;
    }

    @Nonnull
    public CompanionPopulationBootstrapService.BootstrapResult bootstrapResult() {
        return bootstrapResult;
    }
}
