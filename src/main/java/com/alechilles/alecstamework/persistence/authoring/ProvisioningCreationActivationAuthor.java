package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.CompanionProvisioningRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.facade.ReplacementCompanionProvisioningApi;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nullable;

/** Authors the optional first live activation of a provisioned companion. */
final class ProvisioningCreationActivationAuthor {
    private static final String IDS = "provisioning-activation-api:v1";

    private final ReplacementFeatureEvidenceQueries queries;
    private final PopulationGroupConfigRegistry groups;
    private final ReplacementFeaturePolicySource policies;
    private final ReplacementFeatureLiveEvidenceSource live;
    private final ProvisioningActivationEvidenceFactory activations =
            new ProvisioningActivationEvidenceFactory();

    ProvisioningCreationActivationAuthor(
            ReplacementFeatureEvidenceQueries queries,
            PopulationGroupConfigRegistry groups,
            ReplacementFeaturePolicySource policies,
            ReplacementFeatureLiveEvidenceSource live
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.live = Objects.requireNonNull(live, "live");
    }

    ReplacementCompanionProvisioningApi.PreparedActivation createInitial(
            CompanionProvisioningRequest request,
            ProvisioningOrigin origin,
            CompanionLifecycle lifecycle,
            PopulationGroupAssignment assignment,
            List<PopulationGroupPolicy> groupPolicies,
            @Nullable CommandRosterMembershipDraft membership,
            ReplacementFeaturePolicySource.RolePolicySnapshot rolePolicy,
            ReplacementFeatureLiveEvidenceSource.ProvisioningWorldEvidence world
    ) {
        OperationId operationId = operationId(origin);
        NpcAlias targetAlias = targetAlias(origin);
        String receipt = receipt(origin);
        return new ReplacementCompanionProvisioningApi.PreparedActivation(
                operationId,
                activations.create(
                        origin,
                        lifecycle,
                        request.roleId(),
                        assignment,
                        groupPolicies,
                        membership,
                        null,
                        rolePolicy.timedSummoningEnabled(),
                        rolePolicy.timedSummonPolicy(),
                        operationId,
                        targetAlias,
                        receipt,
                        request.destination(),
                        world
                )
        );
    }

    CompletionStage<ReplacementCompanionProvisioningApi.PreparedProvisioning>
    prepareExisting(
            CompanionProvisioningRequest publicRequest,
            com.alechilles.alecstamework.companion.provisioning
                    .CompanionProvisioningRequest durable,
            OperationId creationId,
            boolean replay,
            boolean wantsActivation
    ) {
        if (!wantsActivation) {
            return CompletableFuture.completedFuture(
                    new ReplacementCompanionProvisioningApi
                            .PreparedProvisioning(
                            creationId, durable, null, replay
                    )
            );
        }
        ProvisioningOrigin origin = durable.origin();
        String receipt = receipt(origin);
        return queries.findOperation(
                ProvisioningActivationDefinition.KIND,
                origin.activationKey(receipt)
        ).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Failed<?>) {
                return failed("provisioning_activation_read_failed");
            }
            if (read instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found) {
                ProvisioningActivationRequest activation =
                        ProvisioningActivationDefinition.INSTANCE.decode(
                                found.value().operation().payloadJson()
                        );
                return CompletableFuture.completedFuture(
                        new ReplacementCompanionProvisioningApi
                                .PreparedProvisioning(
                                creationId,
                                durable,
                                new ReplacementCompanionProvisioningApi
                                        .PreparedActivation(
                                        found.value().operation()
                                                .operationId(),
                                        activation
                                ),
                                replay
                        )
                );
            }
            return authorNew(
                    publicRequest, durable, creationId, replay
            );
        });
    }

    private CompletionStage<
            ReplacementCompanionProvisioningApi.PreparedProvisioning>
    authorNew(
            CompanionProvisioningRequest publicRequest,
            com.alechilles.alecstamework.companion.provisioning
                    .CompanionProvisioningRequest durable,
            OperationId creationId,
            boolean replay
    ) {
        ProvisioningOrigin origin = durable.origin();
        OperationId operationId = operationId(origin);
        NpcAlias alias = targetAlias(origin);
        String receipt = receipt(origin);
        CompletionStage<ReplacementFeatureLiveEvidenceSource
                .ProvisioningWorldEvidence> stage =
                live.freezeProvisioningWorld(
                        new ReplacementFeatureLiveEvidenceSource
                                .ProvisioningWorldIntent(
                                origin,
                                publicRequest.ownerUuid(),
                                publicRequest.ownershipWorldName(),
                                publicRequest.roleId(),
                                publicRequest.destination(),
                                alias,
                                receipt
                        )
                );
        if (stage == null) {
            return CompletableFuture.completedFuture(null);
        }
        return stage.thenApply(world -> {
            PopulationGroupConfigIndex policy = groups.snapshot();
            var rolePolicy = policies.resolve(publicRequest.roleId());
            if (!validWorld(publicRequest, world)
                    || rolePolicy == null
                    || durable.groupAssignment().policyRevision()
                    != policy.revision()) {
                return null;
            }
            ProvisioningActivationRequest activation = activations.create(
                    origin,
                    durable.lifecycle(),
                    publicRequest.roleId(),
                    durable.groupAssignment(),
                    policy.resolvePoliciesForRole(
                            publicRequest.roleId()
                    ),
                    durable.commandMembership(),
                    null,
                    rolePolicy.timedSummoningEnabled(),
                    rolePolicy.timedSummonPolicy(),
                    operationId,
                    alias,
                    receipt,
                    publicRequest.destination(),
                    world
            );
            return new ReplacementCompanionProvisioningApi
                    .PreparedProvisioning(
                    creationId,
                    durable,
                    new ReplacementCompanionProvisioningApi
                            .PreparedActivation(operationId, activation),
                    replay
            );
        });
    }

    OperationId operationId(ProvisioningOrigin origin) {
        return StablePersistenceIds.operationId(
                IDS, origin.stableKey()
        );
    }

    NpcAlias targetAlias(ProvisioningOrigin origin) {
        return StablePersistenceIds.targetAlias(
                IDS, origin.stableKey()
        );
    }

    String receipt(ProvisioningOrigin origin) {
        return StablePersistenceIds.receipt(
                IDS, origin.stableKey()
        );
    }

    private boolean validWorld(
            CompanionProvisioningRequest request,
            ReplacementFeatureLiveEvidenceSource
                    .ProvisioningWorldEvidence world
    ) {
        return world != null
                && world.ownerUuid().equals(request.ownerUuid())
                && world.placement() != null
                && world.fullState() != null
                && world.placement().worldKey().equals(
                request.ownershipWorldName()
        )
                && (request.destination() == null
                || request.destination().equals(world.admittedLocation()));
    }

    private <T> CompletionStage<T> failed(String code) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(code)
        );
    }
}
