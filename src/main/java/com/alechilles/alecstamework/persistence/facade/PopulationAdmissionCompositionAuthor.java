package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlanner;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.population.domain.ManagedAdmissionEvidenceAuthor;
import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionComposition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
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
        if (groups == null) {
            return unavailable("population-admission-group-authority-unavailable");
        }
        if (source == null) {
            if (request.request().request().expectedProfileRevision()
                    != PopulationAdmissionRequest.NEW_PROFILE_REVISION) {
                return unavailable("population-admission-source-required-for-composition");
            }
            if (payload.ownerId() == null) {
                return unavailable("population-admission-new-owner-required");
            }
            return CompletableFuture.completedFuture(
                    newProfileComposition(request, payload, operationId)
            );
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
                    if (assignment == null && capturedRelease(source, payload)) {
                        return CompletableFuture.completedFuture(
                                composition(
                                        request, source, payload, operationId, null
                                )
                        );
                    }
                    if (!currentAssignment(
                            request, source, assignment
                    )) {
                        return unavailable("population-admission-group-source-stale");
                    }
                    return CompletableFuture.completedFuture(
                            composition(request, source, payload, operationId, assignment)
                    );
                });
    }

    private boolean currentAssignment(
            PopulationAdmissionRequestV3 request,
            CompanionLifecycle source,
            PopulationGroupAssignment assignment
    ) {
        if (assignment == null
                || !assignment.profileId().equals(source.profileId())
                || assignment.sourceLifecycleRevision().value()
                > source.revision().value()
                || !Objects.equals(
                assignment.roleId(), request.request().targetRoleId()
        )) {
            return false;
        }
        List<PopulationGroupPolicy> policies = groups.snapshot()
                .resolvePoliciesForRole(request.request().targetRoleId());
        TreeSet<PopulationGroupMembership> memberships = new TreeSet<>();
        for (PopulationGroupPolicy policy : policies) {
            if (policy.policyRevision() != assignment.policyRevision()) {
                return false;
            }
            memberships.add(new PopulationGroupMembership(
                    policy.groupId(), policy.scope()
            ));
        }
        return memberships.equals(new TreeSet<>(assignment.memberships()));
    }

    private boolean capturedRelease(
            CompanionLifecycle source,
            PopulationDomainAdmissionOperation.Payload payload
    ) {
        return source.state() == LifecycleState.CAPTURED
                && payload.sourceLifecycle() == LifecycleState.CAPTURED
                && payload.targetLifecycle() == LifecycleState.ACTIVE
                && source.profileId().equals(payload.profileId())
                && source.revision().equals(
                payload.expectedLifecycleRevision()
        );
    }

    private PopulationAdmissionComposition newProfileComposition(
            PopulationAdmissionRequestV3 request,
            PopulationDomainAdmissionOperation.Payload payload,
            OperationId operationId
    ) {
        List<PopulationGroupPolicy> policies = groups.snapshot()
                .resolvePoliciesForRole(request.request().targetRoleId());
        if (policies.isEmpty()) {
            throw new IllegalStateException("population-admission-group-policy-missing");
        }
        if (adminOverride(request)) {
            policies = policies.stream().map(policy ->
                    new PopulationGroupPolicy(
                            policy.groupId(),
                            policy.scope(),
                            0,
                            0,
                            policy.policyRevision()
                    )
            ).toList();
        }
        CompanionLifecycle before = new CompanionLifecycle(
                payload.profileId(),
                null,
                LifecycleState.RELEASED,
                LifecycleLocation.none(),
                LifecycleRevision.INITIAL,
                operationId,
                payload.createdAtMs(),
                ReconciliationGeneration.INITIAL,
                null,
                null
        );
        CompanionLifecycle after = new CompanionLifecycle(
                payload.profileId(),
                payload.ownerId(),
                payload.targetLifecycle(),
                targetLocation(request, payload, operationId),
                LifecycleRevision.INITIAL.next(),
                operationId,
                payload.createdAtMs(),
                ReconciliationGeneration.INITIAL,
                null,
                payload.ownerWorldKey()
        );
        PopulationGroupTransitionAdmissionRequest groupRequest =
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        groups.snapshot().revision(),
                        policies.get(0).policyRevision(),
                        policies,
                        payload.createdAtMs()
                );
        return new PopulationAdmissionComposition(
                newProfileOwnerPlan(request, payload), groupRequest
        );
    }

    private OwnerPopulationAdmissionPlan newProfileOwnerPlan(
            PopulationAdmissionRequestV3 request,
            PopulationDomainAdmissionOperation.Payload payload
    ) {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        if (config == null) {
            config = TwGlobalConfig.defaultConfig();
        }
        int limit = adminOverride(request)
                ? 0 : config.getPopulationLimitPerPlayerOwnedTotal();
        TwGlobalConfig.PerPlayerLimitScope configuredScope =
                config.getPopulationPerPlayerLimitScope();
        OwnerPopulationScope scope = configuredScope
                == TwGlobalConfig.PerPlayerLimitScope.GLOBAL
                ? OwnerPopulationScope.global(payload.ownerId())
                : OwnerPopulationScope.perWorld(
                        payload.ownerId(), requireOwnerWorld(payload)
                );
        return new OwnerPopulationAdmissionPlan(
                payload.profileId(),
                null,
                List.of(new OwnerPopulationAdmissionPlan.LimitIncrease(
                        scope, 1, limit
                ))
        );
    }

    private boolean adminOverride(PopulationAdmissionRequestV3 request) {
        PopulationAdmissionRequest admission = request.request().request();
        return admission.operation() == PopulationAdmissionOperation.ADMIN_FORCE
                && admission.forcePolicy()
                == PopulationAdmissionForcePolicy.ADMIN_OVERRIDE;
    }

    private String requireOwnerWorld(
            PopulationDomainAdmissionOperation.Payload payload
    ) {
        if (payload.ownerWorldKey() == null) {
            throw new IllegalStateException(
                    "population-admission-new-owner-world-required"
            );
        }
        return payload.ownerWorldKey();
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
        if (policies.isEmpty() && (assignment == null
                || !assignment.memberships().isEmpty())) {
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
                        assignment == null
                                ? 0
                                : assignment.assignmentRevision(),
                        assignment == null
                                ? policies.getFirst().policyRevision()
                                : assignment.policyRevision(),
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
