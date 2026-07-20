package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionIdentityRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Performs bounded canonical owner-population readback and runtime-index republication. */
public final class OwnerPopulationCanonicalRecoveryService {
    private final CompanionPopulationBootstrapService bootstrapService;
    private final CompanionPopulationRepository populationRepository;
    private final CompanionPopulationCoverageRepository coverageRepository;
    private final CompanionIdentityRepository identityRepository;

    OwnerPopulationCanonicalRecoveryService(
            @Nonnull CompanionPopulationBootstrapService bootstrapService,
            @Nonnull CompanionPopulationRepository populationRepository,
            @Nonnull CompanionPopulationCoverageRepository coverageRepository,
            @Nonnull CompanionIdentityRepository identityRepository) {
        this.bootstrapService = Objects.requireNonNull(bootstrapService, "bootstrapService");
        this.populationRepository = Objects.requireNonNull(populationRepository, "populationRepository");
        this.coverageRepository = Objects.requireNonNull(coverageRepository, "coverageRepository");
        this.identityRepository = Objects.requireNonNull(identityRepository, "identityRepository");
    }

    /** Proves each canonical catalog needed to rebuild the owner and identity indexes is readable. */
    public void verifyReadable() throws Exception {
        populationRepository.loadAllStates();
        populationRepository.loadNonterminalOperations();
        coverageRepository.loadAll();
        identityRepository.loadAllAliases();
    }

    /** Replaces runtime indexes from canonical rows and rejects a degraded rebuild. */
    public void republish() {
        CompanionPopulationBootstrapService.BootstrapResult result = bootstrapService.load();
        if (result.globalReadiness() == OwnerPopulationReadiness.DEGRADED) {
            throw new IllegalStateException("owner_population_canonical_republish_failed:" + result.reason());
        }
    }
}
