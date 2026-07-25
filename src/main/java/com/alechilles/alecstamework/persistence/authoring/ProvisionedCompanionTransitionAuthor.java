package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.ProvisionedCompanionTransition;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransitionRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.items.persistence.TameworkRestorationSnapshotResolver;
import com.alechilles.alecstamework.persistence.facade.ReplacementCompanionProvisioningApi;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nullable;

/** Authors supported existing-profile provisioning transitions. */
final class ProvisionedCompanionTransitionAuthor {
    private static final String IDS = "provisioned-transition-api:v1";

    private final ReplacementFeatureEvidenceQueries queries;
    private final PopulationGroupConfigRegistry groups;
    private final ReplacementFeaturePolicySource policies;
    private final ReplacementFeatureLiveEvidenceSource live;
    private final TameworkRestorationSnapshotResolver snapshots;
    private final ProvisioningActivationEvidenceFactory activations =
            new ProvisioningActivationEvidenceFactory();

    ProvisionedCompanionTransitionAuthor(
            ReplacementFeatureEvidenceQueries queries,
            PopulationGroupConfigRegistry groups,
            ReplacementFeaturePolicySource policies,
            ReplacementFeatureLiveEvidenceSource live,
            TameworkRestorationSnapshotResolver snapshots
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.live = Objects.requireNonNull(live, "live");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    CompletionStage<ReplacementCompanionProvisioningApi.PreparedTransition>
    prepare(ProvisionedCompanionTransitionRequest request) {
        if (request == null
                || request.transition()
                == ProvisionedCompanionTransition.REVIVE_DORMANT) {
            return CompletableFuture.completedFuture(null);
        }
        final ProfileId profileId;
        try {
            profileId = ProfileId.parse(request.profileId());
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(null);
        }
        return queries.findProfile(profileId).thenCompose(read -> {
            CompanionProfileReadModel profile = found(read);
            if (!validProfile(request, profile)) {
                return CompletableFuture.completedFuture(null);
            }
            return request.transition()
                    == ProvisionedCompanionTransition.ACTIVATE
                    ? activation(request, profile)
                    : restoration(request, profile);
        });
    }

    private CompletionStage<
            ReplacementCompanionProvisioningApi.PreparedTransition> activation(
            ProvisionedCompanionTransitionRequest request,
            CompanionProfileReadModel profile
    ) {
        if (profile.lifecycle().state()
                != LifecycleState.PROVISIONED_DORMANT) {
            return CompletableFuture.completedFuture(null);
        }
        return queries.findProvisioning(
                profile.identity().profileId()
        ).thenCompose(recordRead -> {
            ProvisioningRecord record = found(recordRead);
            if (record == null) {
                return CompletableFuture.completedFuture(null);
            }
            String receipt = receipt(request);
            IdempotencyKey key = record.origin().activationKey(receipt);
            return queries.findOperation(
                    ProvisioningActivationDefinition.KIND, key
            ).thenCompose(operationRead -> {
                if (operationRead instanceof PersistenceReadResult.Failed<?>) {
                    return failed(
                            "provisioning_activation_operation_read_failed"
                    );
                }
                if (operationRead instanceof PersistenceReadResult.Found<
                        PublicOperationEvidence> found) {
                    ProvisioningActivationRequest durable =
                            ProvisioningActivationDefinition.INSTANCE.decode(
                                    found.value().operation().payloadJson()
                            );
                    return CompletableFuture.completedFuture(
                            new ReplacementCompanionProvisioningApi
                                    .PreparedTransition.Activation(
                                    found.value().operation().operationId(),
                                    durable
                            )
                    );
                }
                return activationCanonical(
                        request, profile, record.origin(), receipt
                );
            });
        });
    }

    private CompletionStage<
            ReplacementCompanionProvisioningApi.PreparedTransition>
    activationCanonical(
            ProvisionedCompanionTransitionRequest request,
            CompanionProfileReadModel profile,
            ProvisioningOrigin origin,
            String receipt
    ) {
        return queries.findMembership(
                profile.identity().profileId()
        ).thenCompose(membershipRead -> {
            if (membershipRead instanceof PersistenceReadResult.Failed<?>) {
                return failed("provisioning_membership_read_failed");
            }
            CommandRosterMembership membership = found(membershipRead);
            return queries.findTimedLease(
                    profile.identity().profileId()
            ).thenCompose(leaseRead -> {
                if (leaseRead instanceof PersistenceReadResult.Failed<?>) {
                    return failed("provisioning_timed_lease_read_failed");
                }
                TimedSummonLease lease = found(leaseRead);
                if (lease != null) {
                    return CompletableFuture.completedFuture(null);
                }
                return activationAssignment(
                        request, profile, origin, receipt, membership
                );
            });
        });
    }

    private CompletionStage<
            ReplacementCompanionProvisioningApi.PreparedTransition>
    activationAssignment(
            ProvisionedCompanionTransitionRequest request,
            CompanionProfileReadModel profile,
            ProvisioningOrigin origin,
            String receipt,
            @Nullable CommandRosterMembership membership
    ) {
        return queries.findPopulationAssignments().thenCompose(read -> {
            PopulationGroupAssignment assignment = assignment(
                    read, profile.identity().profileId()
            );
            PopulationGroupConfigIndex policy = groups.snapshot();
            var rolePolicy = policies.resolve(profile.identity().roleId());
            if (assignment == null || rolePolicy == null
                    || assignment.policyRevision() != policy.revision()) {
                return CompletableFuture.completedFuture(null);
            }
            OperationId operationId = operationId(request);
            NpcAlias targetAlias = targetAlias(request);
            CompletionStage<ReplacementFeatureLiveEvidenceSource
                    .ProvisioningWorldEvidence> stage =
                    live.freezeProvisioningWorld(
                            worldIntent(
                                    request,
                                    profile,
                                    origin,
                                    targetAlias,
                                    receipt
                            )
                    );
            if (stage == null) {
                return CompletableFuture.completedFuture(null);
            }
            return stage.thenApply(world -> {
                if (!validWorld(request, world)) {
                    return null;
                }
                ProvisioningActivationRequest durable =
                        activations.create(
                                origin,
                                profile.lifecycle(),
                                profile.identity().roleId(),
                                assignment,
                                policy.resolvePoliciesForRole(
                                        profile.identity().roleId()
                                ),
                                null,
                                membership,
                                rolePolicy.timedSummoningEnabled(),
                                rolePolicy.timedSummonPolicy(),
                                operationId,
                                targetAlias,
                                receipt,
                                request.destination(),
                                world
                        );
                return new ReplacementCompanionProvisioningApi
                        .PreparedTransition.Activation(
                        operationId, durable
                );
            });
        });
    }

    private CompletionStage<
            ReplacementCompanionProvisioningApi.PreparedTransition>
    restoration(
            ProvisionedCompanionTransitionRequest request,
            CompanionProfileReadModel profile
    ) {
        if (profile.lifecycle().state() != LifecycleState.DEAD_REVIVABLE) {
            return CompletableFuture.completedFuture(null);
        }
        IdempotencyKey key = StablePersistenceIds.idempotencyKey(
                IDS, identity(request)
        );
        return queries.findOperation(
                CompanionRestorationDefinition.KIND, key
        ).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Failed<?>) {
                return failed("provisioning_restoration_operation_read_failed");
            }
            if (read instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found) {
                CompanionRestorationRequest durable =
                        CompanionRestorationDefinition.INSTANCE.decode(
                                found.value().operation().payloadJson()
                        );
                return CompletableFuture.completedFuture(
                        new ReplacementCompanionProvisioningApi
                                .PreparedTransition.Restoration(
                                found.value().operation().operationId(),
                                key,
                                durable
                        )
                );
            }
            return authorRestoration(request, profile, key);
        });
    }

    private CompletionStage<
            ReplacementCompanionProvisioningApi.PreparedTransition>
    authorRestoration(
            ProvisionedCompanionTransitionRequest request,
            CompanionProfileReadModel profile,
            IdempotencyKey key
    ) {
        CompanionSnapshot source = profile.currentSnapshots().stream()
                .filter(snapshot -> snapshot.kind().equals(
                        DormantSourceEvidence.Kind.DEATH_COMPONENT
                                .snapshotKind()
                ))
                .findFirst()
                .orElse(null);
        if (source == null) {
            return CompletableFuture.completedFuture(null);
        }
        TameworkRestorationSnapshotResolver.Resolution resolution =
                snapshots.resolve(profile, source);
        if (!(resolution instanceof TameworkRestorationSnapshotResolver
                .Resolution.Resolved resolved)) {
            return CompletableFuture.completedFuture(null);
        }
        OperationId operationId = operationId(request);
        NpcAlias targetAlias = targetAlias(request);
        String receipt = receipt(request);
        CompletionStage<ReplacementFeatureLiveEvidenceSource
                .ProvisioningWorldEvidence> stage =
                live.freezeProvisioningWorld(
                        new ReplacementFeatureLiveEvidenceSource
                                .ProvisioningWorldIntent(
                                new ProvisioningOrigin(
                                        request.callerNamespace(),
                                        request.idempotencyKey()
                                ),
                                request.actorUuid(),
                                request.ownershipWorldName(),
                                profile.identity().roleId(),
                                request.destination(),
                                targetAlias,
                                receipt
                        )
                );
        if (stage == null) {
            return CompletableFuture.completedFuture(null);
        }
        return stage.thenApply(world -> {
            if (!validWorld(request, world) || world.placement() == null) {
                return null;
            }
            CompanionRestorationRequest durable =
                    new CompanionRestorationRequest(
                            profile.identity().profileId(),
                            profile.lifecycle().revision(),
                            LifecycleState.DEAD_REVIVABLE,
                            source,
                            resolved.projection(),
                            targetAlias,
                            world.placement(),
                            receipt,
                            world.observedAtMs()
                    );
            return new ReplacementCompanionProvisioningApi
                    .PreparedTransition.Restoration(
                    operationId, key, durable
            );
        });
    }

    private boolean validProfile(
            ProvisionedCompanionTransitionRequest request,
            @Nullable CompanionProfileReadModel profile
    ) {
        return profile != null
                && profile.identity().roleId() != null
                && profile.lifecycle().ownerId() != null
                && profile.lifecycle().ownerId().value().equals(
                request.actorUuid()
        )
                && profile.lifecycle().revision().value()
                == request.expectedProfileRevision()
                && !profile.lifecycle().quarantined()
                && profile.lifecycle().activeOperationId() == null;
    }

    private boolean validWorld(
            ProvisionedCompanionTransitionRequest request,
            @Nullable ReplacementFeatureLiveEvidenceSource
                    .ProvisioningWorldEvidence world
    ) {
        return world != null
                && world.ownerUuid().equals(request.actorUuid())
                && request.destination() != null
                && request.destination().equals(world.admittedLocation())
                && world.placement() != null
                && world.placement().worldKey().equals(
                request.destination().worldName()
        );
    }

    private ReplacementFeatureLiveEvidenceSource.ProvisioningWorldIntent
    worldIntent(
            ProvisionedCompanionTransitionRequest request,
            CompanionProfileReadModel profile,
            ProvisioningOrigin origin,
            NpcAlias targetAlias,
            String receipt
    ) {
        return new ReplacementFeatureLiveEvidenceSource
                .ProvisioningWorldIntent(
                origin,
                request.actorUuid(),
                request.ownershipWorldName(),
                profile.identity().roleId(),
                request.destination(),
                targetAlias,
                receipt
        );
    }

    @Nullable
    private PopulationGroupAssignment assignment(
            PersistenceReadResult<List<PopulationGroupAssignment>> read,
            ProfileId profileId
    ) {
        if (!(read instanceof PersistenceReadResult.Found<
                List<PopulationGroupAssignment>> found)) {
            return null;
        }
        return found.value().stream()
                .filter(value -> value.profileId().equals(profileId))
                .findFirst()
                .orElse(null);
    }

    private OperationId operationId(
            ProvisionedCompanionTransitionRequest request
    ) {
        return StablePersistenceIds.operationId(IDS, identity(request));
    }

    private NpcAlias targetAlias(
            ProvisionedCompanionTransitionRequest request
    ) {
        return StablePersistenceIds.targetAlias(IDS, identity(request));
    }

    private String receipt(
            ProvisionedCompanionTransitionRequest request
    ) {
        return StablePersistenceIds.receipt(IDS, identity(request));
    }

    private String[] identity(
            ProvisionedCompanionTransitionRequest request
    ) {
        return new String[] {
                request.callerNamespace(),
                request.idempotencyKey(),
                request.profileId()
        };
    }

    @Nullable
    private <T> T found(PersistenceReadResult<T> read) {
        return read instanceof PersistenceReadResult.Found<T> found
                ? found.value()
                : null;
    }

    private <T> CompletionStage<T> failed(String code) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(code)
        );
    }
}
