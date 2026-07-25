package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.CompanionProvisioningDisposition;
import com.alechilles.alecstamework.api.CompanionProvisioningLinkRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningRequest;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterHome;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.facade.ReplacementCompanionProvisioningApi;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nullable;

/** Authors dormant provisioning and its optional first live activation. */
final class CompanionProvisioningCreationAuthor {
    private static final String CREATION_IDS = "provisioning-api:v1";
    private final ReplacementFeatureEvidenceQueries queries;
    private final PopulationGroupConfigRegistry groups;
    private final ReplacementFeaturePolicySource policies;
    private final ReplacementFeatureLiveEvidenceSource live;
    private final ProvisioningCreationActivationAuthor activations;

    CompanionProvisioningCreationAuthor(
            ReplacementFeatureEvidenceQueries queries,
            PopulationGroupConfigRegistry groups,
            ReplacementFeaturePolicySource policies,
            ReplacementFeatureLiveEvidenceSource live
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.live = Objects.requireNonNull(live, "live");
        this.activations = new ProvisioningCreationActivationAuthor(
                queries, groups, policies, live
        );
    }

    CompletionStage<ReplacementCompanionProvisioningApi
            .PreparedProvisioning> prepare(
            CompanionProvisioningRequest request
    ) {
        return prepare(request, null);
    }

    CompletionStage<ReplacementCompanionProvisioningApi
            .PreparedProvisioning> prepare(
            CompanionProvisioningLinkRequest request
    ) {
        return request == null
                ? CompletableFuture.completedFuture(null)
                : prepare(request.provisioning(), request);
    }

    private CompletionStage<ReplacementCompanionProvisioningApi
            .PreparedProvisioning> prepare(
            CompanionProvisioningRequest request,
            @Nullable CompanionProvisioningLinkRequest link
    ) {
        if (request == null) {
            return CompletableFuture.completedFuture(null);
        }
        final ProvisioningOrigin origin;
        try {
            origin = new ProvisioningOrigin(
                    request.callerNamespace(), request.idempotencyKey()
            );
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(null);
        }
        return queries.findOperation(
                CompanionProvisioningDefinition.KIND,
                origin.operationKey()
        ).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Failed<?>) {
                return failed("provisioning_operation_read_failed");
            }
            if (read instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found) {
                var durable =
                        CompanionProvisioningDefinition.INSTANCE.decode(
                                found.value().operation().payloadJson()
                        );
                if (!matches(request, link, durable)) {
                    return failed("provisioning_idempotency_conflict");
                }
                return activations.prepareExisting(
                        request,
                        durable,
                        found.value().operation().operationId(),
                        true,
                        wantsActivation(request, link)
                );
            }
            return authorNew(request, link, origin);
        });
    }

    private CompletionStage<ReplacementCompanionProvisioningApi
            .PreparedProvisioning> authorNew(
            CompanionProvisioningRequest request,
            @Nullable CompanionProvisioningLinkRequest link,
            ProvisioningOrigin origin
    ) {
        PopulationGroupConfigIndex groupPolicy = groups.snapshot();
        if (request.expectedPolicyRevision()
                != CompanionProvisioningRequest.CURRENT_POLICY_REVISION
                && request.expectedPolicyRevision()
                != groupPolicy.revision()) {
            return CompletableFuture.completedFuture(null);
        }
        ReplacementFeaturePolicySource.RolePolicySnapshot rolePolicy =
                policies.resolve(request.roleId());
        if (rolePolicy == null
                || !rolePolicy.roleId().equals(request.roleId())) {
            return CompletableFuture.completedFuture(null);
        }
        boolean wantsActivation = wantsActivation(request, link);
        var targetAlias = wantsActivation
                ? activations.targetAlias(origin)
                : null;
        String receipt = wantsActivation
                ? activations.receipt(origin)
                : null;
        CompletionStage<ReplacementFeatureLiveEvidenceSource
                .ProvisioningWorldEvidence> worldStage =
                live.freezeProvisioningWorld(
                        new ReplacementFeatureLiveEvidenceSource
                                .ProvisioningWorldIntent(
                                origin,
                                request.ownerUuid(),
                                request.ownershipWorldName(),
                                request.roleId(),
                                request.destination(),
                                targetAlias,
                                receipt
                        )
                );
        if (worldStage == null) {
            return CompletableFuture.completedFuture(null);
        }
        return worldStage.thenCompose(world -> roster(
                request,
                link,
                origin,
                groupPolicy,
                rolePolicy,
                targetAlias,
                world
        ));
    }

    private CompletionStage<ReplacementCompanionProvisioningApi
            .PreparedProvisioning> roster(
            CompanionProvisioningRequest request,
            @Nullable CompanionProvisioningLinkRequest link,
            ProvisioningOrigin origin,
            PopulationGroupConfigIndex groupPolicy,
            ReplacementFeaturePolicySource.RolePolicySnapshot rolePolicy,
            @Nullable NpcAlias targetAlias,
            @Nullable ReplacementFeatureLiveEvidenceSource
                    .ProvisioningWorldEvidence world
    ) {
        if (!validWorld(request, world)) {
            return CompletableFuture.completedFuture(null);
        }
        if (link == null) {
            return CompletableFuture.completedFuture(build(
                    request,
                    null,
                    0L,
                    origin,
                    groupPolicy,
                    rolePolicy,
                    targetAlias,
                    world
            ));
        }
        CommandFamilyKey family = new CommandFamilyKey(
                new OwnerId(request.ownerUuid()), link.commandFamilyId()
        );
        return queries.findRoster(family).thenApply(read -> {
            if (read instanceof PersistenceReadResult.Failed<?>) {
                throw new IllegalStateException(
                        "provisioning_roster_read_failed"
                );
            }
            CommandRoster roster = found(read);
            long revision = roster == null ? 0 : roster.rosterRevision();
            if (roster != null && roster.memberships().stream().anyMatch(
                    membership -> membership.slotId().equals(
                            origin.commandSlotId()
                    )
            )) {
                return null;
            }
            CommandRosterMembershipDraft membership =
                    new CommandRosterMembershipDraft(
                            origin.commandSlotId(),
                            family,
                            origin.profileId(),
                            link.groupId(),
                            link.activeForBulkCommands(),
                            home(
                                    request.ownershipWorldName(),
                                    request.homePosition()
                            ),
                            world.observedAtMs()
                    );
            return build(
                    request,
                    membership,
                    revision,
                    origin,
                    groupPolicy,
                    rolePolicy,
                    targetAlias,
                    world
            );
        });
    }

    private ReplacementCompanionProvisioningApi.PreparedProvisioning build(
            CompanionProvisioningRequest request,
            @Nullable CommandRosterMembershipDraft membership,
            long rosterRevision,
            ProvisioningOrigin origin,
            PopulationGroupConfigIndex groupPolicy,
            ReplacementFeaturePolicySource.RolePolicySnapshot rolePolicy,
            @Nullable NpcAlias targetAlias,
            ReplacementFeatureLiveEvidenceSource.ProvisioningWorldEvidence
                    world
    ) {
        long now = world.observedAtMs();
        OwnerId owner = new OwnerId(request.ownerUuid());
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                origin.profileId(),
                owner,
                LifecycleState.PROVISIONED_DORMANT,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        origin.stableKey()
                ),
                LifecycleRevision.INITIAL,
                null,
                now,
                ReconciliationGeneration.INITIAL,
                null,
                request.ownershipWorldName()
        );
        List<PopulationGroupPolicy> groupPolicies =
                groupPolicy.resolvePoliciesForRole(request.roleId());
        PopulationGroupAssignment assignment =
                new PopulationGroupAssignment(
                        origin.profileId(),
                        request.roleId(),
                        groupPolicies.stream()
                                .map(policy ->
                                        new PopulationGroupMembership(
                                                policy.groupId(),
                                                policy.scope()
                                        ))
                                .toList(),
                        groupPolicy.revision(),
                        0,
                        LifecycleRevision.INITIAL,
                        1,
                        now
                );
        String metadata = world.metadataJson();
        CompanionIdentity identity = new CompanionIdentity(
                origin.profileId(),
                request.displayName(),
                request.roleId(),
                metadata,
                metadata == null ? null : Sha256Hash.ofUtf8(metadata),
                request.ownershipWorldName(),
                now,
                now,
                now,
                0
        );
        var durable = new com.alechilles.alecstamework.companion.provisioning
                .CompanionProvisioningRequest(
                origin,
                request.correlationId(),
                identity,
                lifecycle,
                assignment,
                groupPolicies,
                rolePolicy.globalOwnerLimit(),
                rolePolicy.perWorldOwnerLimit(),
                membership,
                membership == null ? null : rosterRevision,
                now
        );
        ReplacementCompanionProvisioningApi.PreparedActivation activation =
                targetAlias == null ? null : activations.createInitial(
                        request,
                        origin,
                        lifecycle,
                        assignment,
                        groupPolicies,
                        membership,
                        rolePolicy,
                        world
                );
        return new ReplacementCompanionProvisioningApi.PreparedProvisioning(
                StablePersistenceIds.operationId(
                        CREATION_IDS, origin.stableKey()
                ),
                durable,
                activation,
                false
        );
    }

    private boolean wantsActivation(
            CompanionProvisioningRequest request,
            @Nullable CompanionProvisioningLinkRequest link
    ) {
        return request.disposition()
                == CompanionProvisioningDisposition.ACTIVE
                || link != null && link.requestInitialProjection();
    }

    private boolean validWorld(
            CompanionProvisioningRequest request,
            @Nullable ReplacementFeatureLiveEvidenceSource
                    .ProvisioningWorldEvidence world
    ) {
        return world != null
                && world.ownerUuid().equals(request.ownerUuid())
                && world.metadataJson() != null
                && (request.destination() == null
                || request.destination().equals(world.admittedLocation()));
    }

    private boolean matches(
            CompanionProvisioningRequest request,
            @Nullable CompanionProvisioningLinkRequest link,
            com.alechilles.alecstamework.companion.provisioning
                    .CompanionProvisioningRequest durable
    ) {
        boolean command = link == null
                ? durable.commandMembership() == null
                : durable.commandMembership() != null
                && durable.commandMembership().familyKey().familyId()
                .equals(link.commandFamilyId())
                && Objects.equals(
                durable.commandMembership().groupId(), link.groupId()
        )
                && durable.commandMembership().activeForBulkCommands()
                == link.activeForBulkCommands();
        return durable.origin().equals(new ProvisioningOrigin(
                request.callerNamespace(), request.idempotencyKey()
        ))
                && durable.lifecycle().ownerId().value().equals(
                request.ownerUuid()
        )
                && durable.identity().roleId().equals(request.roleId())
                && durable.lifecycle().ownerWorldKey().equals(
                request.ownershipWorldName()
        )
                && Objects.equals(
                durable.identity().displayName(), request.displayName()
        )
                && Objects.equals(
                durable.correlationId(), request.correlationId()
        )
                && command;
    }

    @Nullable
    private CommandRosterHome home(
            String worldKey,
            @Nullable Vector3View position
    ) {
        return position == null
                ? null
                : new CommandRosterHome(
                        worldKey,
                        position.x(),
                        position.y(),
                        position.z()
                );
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
