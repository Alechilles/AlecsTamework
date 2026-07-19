package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityDecision;
import com.alechilles.alecstamework.persistence.health.PersistenceEvidenceDimension;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityService;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationContext;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationDelta;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureContext;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentReporter;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import com.alechilles.alecstamework.persistence.incidents.PersistenceTransactionOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Applies exact v7 availability and incident containment to owner-population operations. */
final class OwnerPopulationPersistenceGuard {
    private static final Set<String> REQUIRED_COVERAGE = Set.of(
            PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG.key(),
            PersistenceEvidenceDimension.OWNER_POPULATION_CATALOG.key());

    private final PersistenceMutationAvailabilityService availability;
    private final PersistenceIncidentReporter incidents;
    private final PersistenceScopeFactory scopes;

    OwnerPopulationPersistenceGuard(@Nonnull PersistenceMutationAvailabilityService availability,
                                    @Nonnull PersistenceIncidentReporter incidents,
                                    @Nonnull PersistenceScopeFactory scopes) {
        this.availability = availability;
        this.incidents = incidents;
        this.scopes = scopes;
    }

    @Nonnull
    PersistenceMutationAvailabilityDecision decide(@Nonnull OwnerPopulationAdmissionPlan plan) {
        OwnerPopulationTransitionRequest transition = plan.transition();
        List<PersistenceScope> exactScopes = exactScopes(plan);
        return availability.decide(new PersistenceMutationContext(
                PersistenceDomain.OWNER_MUTATION, transition.operation().name(), exactScopes,
                REQUIRED_COVERAGE, delta(transition), null, null,
                plan.source() != null, plan.finalNpcUuid() != null));
    }

    void reportFeatureAmbiguity(@Nonnull String reason) {
        PersistenceScope domain = scopes.scope(
                PersistenceScopeType.FEATURE_DOMAIN, PersistenceDomain.OWNER_MUTATION.name(),
                "owner_population_catalog");
        incidents.report(new PersistenceFailureContext(
                normalize(reason), PersistenceDomain.OWNER_MUTATION,
                PersistenceOperationPhase.PUBLICATION, PersistenceTransactionOutcome.COMMITTED,
                List.of(domain), true, true, false, false, false,
                false, false, true, null, null));
    }

    void reportAmbiguity(@Nonnull OwnerPopulationAdmissionPlan plan,
                         @Nonnull String operationId,
                         @Nonnull String reason,
                         @Nonnull PersistenceOperationPhase phase,
                         @Nonnull PersistenceTransactionOutcome outcome,
                         boolean liveMutationMayBeVisible,
                         Throwable failure) {
        incidents.report(new PersistenceFailureContext(
                normalize(reason), PersistenceDomain.OWNER_MUTATION, phase, outcome,
                exactScopes(plan), true, true, false, false, false,
                false, false, liveMutationMayBeVisible, operationId, failure));
    }

    @Nonnull
    private List<PersistenceScope> exactScopes(OwnerPopulationAdmissionPlan plan) {
        OwnerPopulationTransitionRequest transition = plan.transition();
        List<PersistenceScope> result = new ArrayList<>();
        result.add(scopes.profile(transition.profileId()));
        addOwner(result, transition.expectedOwnerId(), transition.sourceWorldName());
        addOwner(result, transition.newOwnerId(), transition.destinationWorldName());
        return List.copyOf(result);
    }

    private void addOwner(List<PersistenceScope> result, UUID ownerId, String worldName) {
        if (ownerId == null) return;
        result.add(scopes.ownerGlobal(ownerId));
        result.add(scopes.ownerWorld(ownerId, worldName));
    }

    private PersistenceMutationDelta delta(OwnerPopulationTransitionRequest transition) {
        if (transition.newOwnerId() == null) return PersistenceMutationDelta.NEGATIVE;
        return transition.newOwnerId().equals(transition.expectedOwnerId())
                ? PersistenceMutationDelta.ZERO : PersistenceMutationDelta.POSITIVE;
    }

    private String normalize(String reason) {
        if (reason == null || reason.isBlank()) return "owner_population_unresolved";
        return reason.trim().replace('-', '_');
    }
}
