package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlanner;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.population.domain.ManagedAdmissionEvidenceAuthor;
import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionComposition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves existing owner and family-group evidence for one atomic admission. */
final class PopulationAdmissionCompositionAuthor {
    private final PersistenceBootstrap persistence;
    @Nullable
    private final PopulationGroupConfigRegistry groups;

    PopulationAdmissionCompositionAuthor(
            @Nonnull PersistenceBootstrap persistence,
            @Nullable PopulationGroupConfigRegistry groups
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.groups = groups;
    }

    CompletionStage<PopulationAdmissionComposition> compose(
            PopulationAdmissionRequestV3 request,
            CompanionLifecycle source,
            PopulationDomainAdmissionOperation.Payload payload,
            OperationId operationId
    ) {
        if (payload.domains().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (source == null) {
            return unavailable("population-admission-source-required-for-composition");
        }
        if (groups == null) {
            return unavailable("population-admission-group-authority-unavailable");
        }
        return persistence.facades().queries().findAllPopulationGroupAssignments()
                .thenCompose(read -> {
                    if (!(read instanceof PersistenceReadResult.Found<
                            List<PopulationGroupAssignment>> found)) {
                        return unavailable("population-admission-group-source-unavailable");
                    }
                    PopulationGroupAssignment assignment = found.value().stream()
                            .filter(value -> value.profileId().equals(source.profileId()))
                            .findFirst().orElse(null);
                    if (assignment == null
                            || !assignment.sourceLifecycleRevision().equals(source.revision())) {
                        return unavailable("population-admission-group-source-stale");
                    }
                    return CompletableFuture.completedFuture(
                            composition(request, source, payload, operationId, assignment)
                    );
                });
    }

    private PopulationAdmissionComposition composition(
            PopulationAdmissionRequestV3 request,
            CompanionLifecycle source,
            PopulationDomainAdmissionOperation.Payload payload,
            OperationId operationId,
            PopulationGroupAssignment assignment
    ) {
        List<PopulationGroupPolicy> policies = groups.snapshot()
                .resolvePoliciesForRole(request.request().targetRoleId());
        if (policies.isEmpty() && !assignment.memberships().isEmpty()) {
            throw new IllegalStateException("population-admission-group-policy-missing");
        }
        CompanionLifecycle after = new CompanionLifecycle(
                source.profileId(),
                payload.ownerId(),
                payload.targetLifecycle(),
                targetLocation(request, payload, operationId),
                source.revision().next(),
                operationId,
                payload.createdAtMs(),
                source.lastReconciledGeneration(),
                null,
                payload.ownerWorldKey()
        );
        PopulationGroupTransitionAdmissionRequest groupRequest =
                new PopulationGroupTransitionAdmissionRequest(
                        source,
                        after,
                        assignment.assignmentRevision(),
                        assignment.policyRevision(),
                        policies,
                        payload.createdAtMs()
                );
        return new PopulationAdmissionComposition(
                ownerPlan(source, payload), groupRequest
        );
    }

    @Nullable
    private OwnerPopulationAdmissionPlan ownerPlan(
            CompanionLifecycle source,
            PopulationDomainAdmissionOperation.Payload payload
    ) {
        if (payload.ownerId() == null
                || (Objects.equals(source.ownerId(), payload.ownerId())
                && Objects.equals(source.ownerWorldKey(), payload.ownerWorldKey()))) {
            return null;
        }
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        if (config == null) {
            config = TwGlobalConfig.defaultConfig();
        }
        int limit = config.getPopulationLimitPerPlayerOwnedTotal();
        TwGlobalConfig.PerPlayerLimitScope scope =
                config.getPopulationPerPlayerLimitScope();
        OwnerPopulationTransitionRequest request =
                new OwnerPopulationTransitionRequest(
                        source.profileId(),
                        source.revision(),
                        source.ownerId(),
                        source.ownerWorldKey(),
                        payload.ownerId(),
                        payload.ownerWorldKey(),
                        scope == TwGlobalConfig.PerPlayerLimitScope.GLOBAL ? limit : 0,
                        scope == TwGlobalConfig.PerPlayerLimitScope.PER_WORLD ? limit : 0,
                        payload.createdAtMs()
                );
        return OwnerPopulationAdmissionPlanner.plan(request).orElse(null);
    }

    private LifecycleLocation targetLocation(
            PopulationAdmissionRequestV3 request,
            PopulationDomainAdmissionOperation.Payload payload,
            OperationId operationId
    ) {
        String key = operationId.value().toString();
        LifecycleLocationKind kind = payload.targetLifecycle().requiredLocation();
        if (kind == LifecycleLocationKind.LIVE_ENTITY) {
            String world = payload.ownerWorldKey() == null
                    ? request.request().ownershipWorldName()
                    : payload.ownerWorldKey();
            String entity = request.request().request().currentNpcUuid() == null
                    ? key
                    : request.request().request().currentNpcUuid().toString();
            return LifecycleLocation.liveEntity(entity, world);
        }
        if (kind == LifecycleLocationKind.NONE) {
            return LifecycleLocation.none();
        }
        if (kind == LifecycleLocationKind.UNRESOLVED) {
            return LifecycleLocation.unresolved();
        }
        return LifecycleLocation.keyed(kind, key);
    }

    private <T> CompletionStage<T> unavailable(String reason) {
        return CompletableFuture.failedFuture(new IllegalStateException(reason));
    }
}
