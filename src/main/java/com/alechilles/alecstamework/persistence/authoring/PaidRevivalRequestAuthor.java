package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.PaidCommandRevivalRequest;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.items.persistence.TameworkDormantSnapshotFactsReader;
import com.alechilles.alecstamework.items.persistence.TameworkRestorationSnapshotResolver;
import com.alechilles.alecstamework.persistence.facade.ReplacementPaidCommandRevivalApi;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nullable;

/** Authors a complete exact-cost paid revival request. */
final class PaidRevivalRequestAuthor {
    private static final String IDS = "paid-revival-api:v1";

    private final ReplacementFeatureEvidenceQueries queries;
    private final PopulationGroupConfigRegistry groups;
    private final ReplacementFeaturePolicySource policies;
    private final ReplacementFeatureLiveEvidenceSource live;
    private final TameworkDormantSnapshotFactsReader facts;
    private final TameworkRestorationSnapshotResolver snapshots;

    PaidRevivalRequestAuthor(
            ReplacementFeatureEvidenceQueries queries,
            PopulationGroupConfigRegistry groups,
            ReplacementFeaturePolicySource policies,
            ReplacementFeatureLiveEvidenceSource live,
            TameworkDormantSnapshotFactsReader facts,
            TameworkRestorationSnapshotResolver snapshots
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.live = Objects.requireNonNull(live, "live");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    IdempotencyKey operationKey(
            String callerNamespace,
            String idempotencyKey
    ) {
        return StablePersistenceIds.idempotencyKey(
                IDS, callerNamespace, idempotencyKey
        );
    }

    CompletionStage<ReplacementPaidCommandRevivalApi.PreparedRevival>
    prepare(PaidCommandRevivalRequest request) {
        if (request == null) {
            return CompletableFuture.completedFuture(null);
        }
        final ProfileId profileId;
        final IdempotencyKey key;
        try {
            profileId = ProfileId.parse(request.profileId());
            key = operationKey(
                    request.callerNamespace(), request.idempotencyKey()
            );
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(null);
        }
        return queries.findOperation(
                PaidRevivalDefinition.KIND, key
        ).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Failed<?>) {
                return failed("paid_revival_operation_read_failed");
            }
            if (read instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found) {
                var durable = PaidRevivalDefinition.INSTANCE.decode(
                        found.value().operation().payloadJson()
                );
                if (!matches(request, durable)) {
                    return failed("paid_revival_idempotency_conflict");
                }
                return CompletableFuture.completedFuture(
                        new ReplacementPaidCommandRevivalApi.PreparedRevival(
                                found.value().operation().operationId(),
                                key,
                                durable,
                                true
                        )
                );
            }
            return canonical(request, profileId, key);
        });
    }

    private CompletionStage<
            ReplacementPaidCommandRevivalApi.PreparedRevival> canonical(
            PaidCommandRevivalRequest request,
            ProfileId profileId,
            IdempotencyKey key
    ) {
        return queries.findProfile(profileId).thenCompose(profileRead -> {
            CompanionProfileReadModel profile = found(profileRead);
            if (!validProfile(request, profile)) {
                return CompletableFuture.completedFuture(null);
            }
            return queries.findMembership(profileId)
                    .thenCompose(membershipRead -> {
                        CommandRosterMembership membership =
                                found(membershipRead);
                        if (!validMembership(request, membership)) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return queries.findTimedLease(profileId)
                                .thenCompose(leaseRead -> {
                                    if (leaseRead instanceof
                                            PersistenceReadResult.Failed<?>) {
                                        return failed(
                                                "paid_revival_lease_read_failed"
                                        );
                                    }
                                    return assignment(
                                            request,
                                            key,
                                            profile,
                                            membership,
                                            found(leaseRead)
                                    );
                                });
                    });
        });
    }

    private CompletionStage<
            ReplacementPaidCommandRevivalApi.PreparedRevival> assignment(
            PaidCommandRevivalRequest request,
            IdempotencyKey key,
            CompanionProfileReadModel profile,
            CommandRosterMembership membership,
            @Nullable TimedSummonLease previousLease
    ) {
        return queries.findPopulationAssignments().thenCompose(read -> {
            PopulationGroupAssignment assignment = assignment(
                    read, profile.identity().profileId()
            );
            PopulationGroupConfigIndex policy = groups.snapshot();
            var rolePolicy = policies.resolve(profile.identity().roleId());
            CompanionSnapshot source = PaidRevivalDormantSource.exact(profile);
            TameworkDormantSnapshotFactsReader.ReadResult factsRead =
                    source == null ? null : facts.read(source);
            TameworkRestorationSnapshotResolver.Resolution projection =
                    source == null ? null : snapshots.resolve(
                    profile, source
            );
            if (assignment == null || rolePolicy == null
                    || !rolePolicy.paidRevivalEnabled()
                    || assignment.policyRevision() != policy.revision()
                    || factsRead == null || !factsRead.successful()
                    || !(projection instanceof
                    TameworkRestorationSnapshotResolver.Resolution.Resolved)) {
                return CompletableFuture.completedFuture(null);
            }
            if (previousLease != null && previousLease.activeSession()) {
                return CompletableFuture.completedFuture(null);
            }
            return live(
                    request,
                    key,
                    profile,
                    membership,
                    previousLease,
                    assignment,
                    policy,
                    rolePolicy,
                    source,
                    factsRead.facts(),
                    (TameworkRestorationSnapshotResolver.Resolution.Resolved)
                            projection
            );
        });
    }

    private CompletionStage<
            ReplacementPaidCommandRevivalApi.PreparedRevival> live(
            PaidCommandRevivalRequest request,
            IdempotencyKey key,
            CompanionProfileReadModel profile,
            CommandRosterMembership membership,
            @Nullable TimedSummonLease previousLease,
            PopulationGroupAssignment assignment,
            PopulationGroupConfigIndex groupPolicy,
            ReplacementFeaturePolicySource.RolePolicySnapshot rolePolicy,
            CompanionSnapshot source,
            TameworkDormantSnapshotFactsReader.Facts sourceFacts,
            TameworkRestorationSnapshotResolver.Resolution.Resolved projection
    ) {
        OperationId operationId = StablePersistenceIds.operationId(
                IDS, request.callerNamespace(), request.idempotencyKey()
        );
        NpcAlias targetAlias = StablePersistenceIds.targetAlias(
                IDS, request.callerNamespace(), request.idempotencyKey()
        );
        CompletionStage<ReplacementFeatureLiveEvidenceSource
                .PaidInventoryEvidence> stage = live.freezePaidInventory(
                new ReplacementFeatureLiveEvidenceSource.PaidInventoryIntent(
                        request.ownerUuid(),
                        profile,
                        rolePolicy.revivalCost(),
                        targetAlias,
                        false
                )
        );
        if (stage == null) {
            return CompletableFuture.completedFuture(null);
        }
        return stage.thenApply(inventory -> author(
                request,
                key,
                operationId,
                targetAlias,
                profile,
                membership,
                previousLease,
                assignment,
                groupPolicy,
                rolePolicy,
                source,
                sourceFacts,
                projection,
                inventory
        ));
    }

    @Nullable
    private ReplacementPaidCommandRevivalApi.PreparedRevival author(
            PaidCommandRevivalRequest request,
            IdempotencyKey key,
            OperationId operationId,
            NpcAlias targetAlias,
            CompanionProfileReadModel profile,
            CommandRosterMembership membership,
            @Nullable TimedSummonLease previousLease,
            PopulationGroupAssignment assignment,
            PopulationGroupConfigIndex groupPolicy,
            ReplacementFeaturePolicySource.RolePolicySnapshot rolePolicy,
            CompanionSnapshot source,
            TameworkDormantSnapshotFactsReader.Facts sourceFacts,
            TameworkRestorationSnapshotResolver.Resolution.Resolved projection,
            @Nullable ReplacementFeatureLiveEvidenceSource
                    .PaidInventoryEvidence inventory
    ) {
        if (!validInventory(
                request, rolePolicy.revivalCost(), inventory
        )) {
            return null;
        }
        Long availableAt = PaidRevivalDormantSource.availableAt(
                sourceFacts, rolePolicy.revivalCooldownMs()
        );
        if (availableAt != null
                && inventory.observedAtMs() < availableAt) {
            return null;
        }
        CompanionLifecycle before = profile.lifecycle();
        CompanionLifecycle after = new CompanionLifecycle(
                before.profileId(),
                before.ownerId(),
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        targetAlias.toString(),
                        inventory.placement().worldKey()
                ),
                before.revision().next(),
                null,
                inventory.observedAtMs(),
                before.lastReconciledGeneration(),
                null,
                inventory.placement().worldKey()
        );
        PopulationGroupTransitionAdmissionRequest admission =
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        assignment.assignmentRevision(),
                        groupPolicy.revision(),
                        groupPolicy.resolvePoliciesForRole(
                                profile.identity().roleId()
                        ),
                        inventory.observedAtMs()
                );
        var durable = new com.alechilles.alecstamework.companion.revival
                .PaidRevivalRequest(
                request.callerNamespace(),
                request.idempotencyKey(),
                membership.familyKey(),
                membership.slotId(),
                membership.membershipRevision(),
                profile.identity().metadataRevision(),
                admission,
                source,
                projection.projection(),
                targetAlias,
                inventory.placement(),
                rolePolicy.configId(),
                rolePolicy.configRevision(),
                rolePolicy.revivalCost(),
                inventory.reservations(),
                StablePersistenceIds.receipt(
                        IDS,
                        request.callerNamespace(),
                        request.idempotencyKey(),
                        "charge"
                ),
                StablePersistenceIds.receipt(
                        IDS,
                        request.callerNamespace(),
                        request.idempotencyKey(),
                        "spawn"
                ),
                timed(
                        membership,
                        previousLease,
                        rolePolicy,
                        operationId,
                        inventory.observedAtMs()
                ),
                inventory.observedAtMs()
        );
        return new ReplacementPaidCommandRevivalApi.PreparedRevival(
                operationId, key, durable, false
        );
    }

    @Nullable
    private TimedSummonActivation timed(
            CommandRosterMembership membership,
            @Nullable TimedSummonLease previous,
            ReplacementFeaturePolicySource.RolePolicySnapshot policy,
            OperationId operationId,
            long now
    ) {
        if (!policy.timedSummoningEnabled()) {
            return null;
        }
        long revision = previous == null
                ? 1
                : Math.addExact(previous.leaseRevision(), 1);
        long createdAt = previous == null ? now : previous.createdAtMs();
        TimedSummonLease lease = new TimedSummonLease(
                membership.profileId(),
                revision,
                new TimedSummonSessionId(operationId.value()),
                policy.timedSummonPolicy().unlimited()
                        ? null
                        : policy.timedSummonPolicy().activeDurationMs(),
                null,
                policy.timedSummonPolicy(),
                Set.of(),
                now,
                createdAt,
                now
        );
        return new TimedSummonActivation(
                membership.familyKey(),
                membership.slotId(),
                membership.membershipRevision(),
                previous,
                lease
        );
    }

    private boolean validProfile(
            PaidCommandRevivalRequest request,
            @Nullable CompanionProfileReadModel profile
    ) {
        return profile != null
                && profile.identity().roleId() != null
                && PaidRevivalDormantSource.supports(
                profile.lifecycle().state()
        )
                && profile.lifecycle().ownerId() != null
                && profile.lifecycle().ownerId().value().equals(
                request.ownerUuid()
        )
                && !profile.lifecycle().quarantined()
                && profile.lifecycle().activeOperationId() == null;
    }

    private boolean validMembership(
            PaidCommandRevivalRequest request,
            @Nullable CommandRosterMembership membership
    ) {
        return membership != null
                && membership.familyKey().equals(new CommandFamilyKey(
                new OwnerId(request.ownerUuid()),
                request.commandFamilyId()
        ));
    }

    private boolean validInventory(
            PaidCommandRevivalRequest request,
            List<RevivalCostItem> costs,
            @Nullable ReplacementFeatureLiveEvidenceSource
                    .PaidInventoryEvidence inventory
    ) {
        if (inventory == null
                || !inventory.ownerUuid().equals(request.ownerUuid())
                || inventory.placement() == null) {
            return false;
        }
        for (RevivalCostItem cost : costs) {
            List<ReplacementFeatureLiveEvidenceSource.PaidCostAvailability>
                    matches = inventory.costs().stream()
                    .filter(value -> value.itemId().equals(cost.itemId()))
                    .toList();
            if (matches.size() != 1
                    || matches.get(0).ownedQuantity()
                    < cost.quantity()) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(
            PaidCommandRevivalRequest request,
            com.alechilles.alecstamework.companion.revival
                    .PaidRevivalRequest durable
    ) {
        return durable.callerNamespace().equals(request.callerNamespace())
                && durable.callerIdempotencyKey().equals(
                request.idempotencyKey()
        )
                && durable.familyKey().ownerId().value().equals(
                request.ownerUuid()
        )
                && durable.familyKey().familyId().equals(
                request.commandFamilyId()
        )
                && durable.groupAdmission().before().profileId().toString()
                .equals(request.profileId());
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

    @Nullable
    private <T> T found(PersistenceReadResult<T> read) {
        return read instanceof PersistenceReadResult.Found<T> found
                ? found.value()
                : null;
    }

    private <T> CompletionStage<T> failed(String code) {
        return CompletableFuture.failedFuture(new IllegalStateException(code));
    }
}
