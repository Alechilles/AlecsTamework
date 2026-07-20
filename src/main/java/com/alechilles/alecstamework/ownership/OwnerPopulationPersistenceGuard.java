package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityDecision;
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
import java.util.List;
import javax.annotation.Nonnull;

/** Applies exact v7 availability and incident containment to owner-population operations. */
final class OwnerPopulationPersistenceGuard {
    private final PersistenceMutationAvailabilityService availability;
    private final PersistenceIncidentReporter incidents;
    private final PersistenceScopeFactory scopes;
    private final OwnerPopulationPersistenceContextFactory contexts;

    OwnerPopulationPersistenceGuard(@Nonnull PersistenceMutationAvailabilityService availability,
                                    @Nonnull PersistenceIncidentReporter incidents,
                                    @Nonnull PersistenceScopeFactory scopes) {
        this.availability = availability;
        this.incidents = incidents;
        this.scopes = scopes;
        this.contexts = new OwnerPopulationPersistenceContextFactory(scopes);
    }

    @Nonnull
    PersistenceMutationAvailabilityDecision decide(@Nonnull OwnerPopulationAdmissionPlan plan) {
        OwnerPopulationTransitionRequest transition = plan.transition();
        OwnerPopulationPersistenceContextFactory.Context context = contexts.create(plan);
        return availability.decide(new PersistenceMutationContext(
                context.domain(), transition.operation().name(), context.scopes(),
                context.requiredCoverage(), delta(transition), null, operationId(plan),
                plan.source() != null, plan.finalNpcUuid() != null));
    }

    void reportFeatureAmbiguity(@Nonnull String reason) {
        PersistenceDomain domain = featureDomain(reason);
        PersistenceScope domainScope = scopes.scope(
                PersistenceScopeType.FEATURE_DOMAIN, domain.name(),
                "owner_population_catalog");
        incidents.report(new PersistenceFailureContext(
                normalize(reason), domain,
                PersistenceOperationPhase.PUBLICATION, PersistenceTransactionOutcome.COMMITTED,
                List.of(domainScope), true, true, false, false, false,
                false, false, true, null, null));
    }

    @Nonnull
    static PersistenceDomain featureDomain(@Nonnull String reason) {
        String normalized = normalizeReason(reason);
        if (normalized.startsWith("breeding_")) return PersistenceDomain.BREEDING_BIRTH;
        if (normalized.startsWith("managed_coop_") || normalized.startsWith("coop_")) {
            return PersistenceDomain.MANAGED_COOP_RELEASE;
        }
        if (normalized.startsWith("capture_")) return PersistenceDomain.CAPTURE_RELEASE;
        if (normalized.startsWith("spawn_")) return PersistenceDomain.TAMED_SPAWN;
        if (normalized.startsWith("recall_") || normalized.startsWith("relocation_")) {
            return PersistenceDomain.RECALL_RELOCATION;
        }
        if (normalized.startsWith("death_") || normalized.startsWith("lost_")) {
            return PersistenceDomain.DEATH_LOST_RECOVERY;
        }
        return PersistenceDomain.OWNER_MUTATION;
    }

    void reportAmbiguity(@Nonnull OwnerPopulationAdmissionPlan plan,
                         @Nonnull String operationId,
                         @Nonnull String reason,
                         @Nonnull PersistenceOperationPhase phase,
                         @Nonnull PersistenceTransactionOutcome outcome,
                         boolean liveMutationMayBeVisible,
                         Throwable failure) {
        OwnerPopulationPersistenceContextFactory.Context context = contexts.create(plan);
        incidents.report(new PersistenceFailureContext(
                normalize(reason), context.domain(), phase, outcome,
                context.scopes(), true, true, false, false, false,
                false, false, liveMutationMayBeVisible, operationId, failure));
    }

    @javax.annotation.Nullable
    private String operationId(OwnerPopulationAdmissionPlan plan) {
        String context = plan.targetContextJson();
        if (context == null || context.isBlank()) return null;
        try {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(context).getAsJsonObject();
            for (String key : List.of("operationId", "idempotencyKey")) {
                if (json.has(key) && json.get(key).isJsonPrimitive()) return json.get(key).getAsString();
            }
        } catch (RuntimeException ignored) {
            // Invalid target context is classified by the durable admission transaction.
        }
        return null;
    }

    private PersistenceMutationDelta delta(OwnerPopulationTransitionRequest transition) {
        if (transition.newOwnerId() == null) return PersistenceMutationDelta.NEGATIVE;
        return transition.newOwnerId().equals(transition.expectedOwnerId())
                ? PersistenceMutationDelta.ZERO : PersistenceMutationDelta.POSITIVE;
    }

    private String normalize(String reason) {
        return normalizeReason(reason);
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return "owner_population_unresolved";
        return reason.trim().replace('-', '_').toLowerCase(java.util.Locale.ROOT);
    }
}
