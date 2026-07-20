package com.alechilles.alecstamework.persistence.health;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Applies pair- and attempt-scoped admission before breeding reserves parents or capacity. */
public final class BreedingPersistenceMutationGate {
    private static final Set<String> REQUIRED_COVERAGE = Set.of(
            PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG.key(),
            PersistenceEvidenceDimension.OWNER_POPULATION_CATALOG.key(),
            PersistenceEvidenceDimension.BREEDING_REPLAY_JOURNAL.key(),
            PersistenceEvidenceDimension.OPERATION_JOURNAL.key());

    private final PersistenceMutationAvailabilityService availability;
    private final PersistenceScopeFactory scopes;

    public BreedingPersistenceMutationGate(
            @Nonnull PersistenceMutationAvailabilityService availability,
            @Nonnull PersistenceScopeFactory scopes) {
        this.availability = availability;
        this.scopes = scopes;
    }

    @Nonnull
    public PersistenceMutationAvailabilityDecision decide(@Nonnull String parentA,
                                                          @Nonnull String parentB,
                                                          @Nonnull UUID attemptId,
                                                          @Nonnull String worldName) {
        return availability.decide(new PersistenceMutationContext(
                PersistenceDomain.BREEDING_PAIRING,
                "pairing",
                List.of(scopes.breedingAttempt(attemptId.toString()),
                        scopes.breedingParent(parentA), scopes.breedingParent(parentB),
                        scopes.world(worldName)),
                REQUIRED_COVERAGE,
                PersistenceMutationDelta.POSITIVE,
                null,
                attemptId.toString(),
                false,
                false));
    }
}
