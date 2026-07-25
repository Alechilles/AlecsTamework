package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTime;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.facade.ReplacementCommandTimedSummoningApi;
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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authors timed summon/store evidence from canonical joins plus one exact
 * world-thread placement or live-state snapshot.
 */
public final class TimedSummonEvidenceAuthor
        implements ReplacementCommandTimedSummoningApi.TransitionAuthor {
    private static final String IDS = "timed-summon-api:v1";

    private final ReplacementFeatureEvidenceQueries queries;
    private final PopulationGroupConfigRegistry groups;
    private final ReplacementFeatureLiveEvidenceSource live;

    public TimedSummonEvidenceAuthor(
            @Nonnull ReplacementFeatureEvidenceQueries queries,
            @Nonnull PopulationGroupConfigRegistry groups,
            @Nonnull ReplacementFeatureLiveEvidenceSource live
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.live = Objects.requireNonNull(live, "live");
    }

    @Override
    public CompletionStage<
            ReplacementCommandTimedSummoningApi.PreparedTransition> prepare(
            CommandTimedSummoningRequest request,
            TimedSummonTransitionRequest.Action action
    ) {
        if (request == null || action == null) {
            return CompletableFuture.completedFuture(null);
        }
        final ProfileId profileId;
        final IdempotencyKey key;
        final OperationId operationId;
        try {
            profileId = ProfileId.parse(request.profileId());
            String[] parts = identity(request);
            key = StablePersistenceIds.idempotencyKey(IDS, parts);
            operationId = StablePersistenceIds.operationId(IDS, parts);
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(null);
        }
        return queries.findOperation(
                TimedSummonTransitionDefinition.KIND, key
        ).thenCompose(read -> existing(
                read, request, action, key
        )).thenCompose(replay -> replay != null
                ? CompletableFuture.completedFuture(replay)
                : canonical(
                        request, action, profileId, key, operationId
                ));
    }

    private CompletionStage<
            ReplacementCommandTimedSummoningApi.PreparedTransition> existing(
            PersistenceReadResult<PublicOperationEvidence> read,
            CommandTimedSummoningRequest request,
            TimedSummonTransitionRequest.Action action,
            IdempotencyKey key
    ) {
        if (read instanceof PersistenceReadResult.Failed<?>) {
            return failed("timed_summon_operation_read_failed");
        }
        if (!(read instanceof PersistenceReadResult.Found<
                PublicOperationEvidence> found)) {
            return CompletableFuture.completedFuture(null);
        }
        TimedSummonTransitionRequest durable =
                TimedSummonTransitionDefinition.INSTANCE.decode(
                        found.value().operation().payloadJson()
                );
        if (durable.action() != action
                || !durable.familyKey().ownerId().value().equals(
                request.ownerUuid()
        )
                || !durable.familyKey().familyId().equals(
                request.commandFamilyId()
        )
                || !durable.beforeLease().profileId().toString().equals(
                request.profileId()
        )) {
            return failed("timed_summon_idempotency_conflict");
        }
        return CompletableFuture.completedFuture(
                new ReplacementCommandTimedSummoningApi.PreparedTransition(
                        found.value().operation().operationId(),
                        key,
                        durable,
                        true
                )
        );
    }

    private CompletionStage<
            ReplacementCommandTimedSummoningApi.PreparedTransition> canonical(
            CommandTimedSummoningRequest request,
            TimedSummonTransitionRequest.Action action,
            ProfileId profileId,
            IdempotencyKey key,
            OperationId operationId
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
                                    TimedSummonLease lease = found(leaseRead);
                                    if (!validLease(action, lease)) {
                                        return CompletableFuture
                                                .completedFuture(null);
                                    }
                                    return assignments(
                                            request,
                                            action,
                                            operationId,
                                            key,
                                            profile,
                                            membership,
                                            lease
                                    );
                                });
                    });
        });
    }

    private CompletionStage<
            ReplacementCommandTimedSummoningApi.PreparedTransition>
    assignments(
            CommandTimedSummoningRequest request,
            TimedSummonTransitionRequest.Action action,
            OperationId operationId,
            IdempotencyKey key,
            CompanionProfileReadModel profile,
            CommandRosterMembership membership,
            TimedSummonLease lease
    ) {
        return queries.findPopulationAssignments().thenCompose(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    List<PopulationGroupAssignment>> found)) {
                return read instanceof PersistenceReadResult.Failed<?>
                        ? failed("timed_summon_group_read_failed")
                        : CompletableFuture.completedFuture(null);
            }
            PopulationGroupAssignment assignment = found.value().stream()
                    .filter(value -> value.profileId().equals(
                            profile.identity().profileId()
                    ))
                    .findFirst()
                    .orElse(null);
            PopulationGroupConfigIndex policy = groups.snapshot();
            if (!validAssignment(profile, assignment, policy)) {
                return CompletableFuture.completedFuture(null);
            }
            NpcAlias expectedAlias = expectedAlias(
                    action, operationId, profile
            );
            if (expectedAlias == null) {
                return CompletableFuture.completedFuture(null);
            }
            CompletionStage<
                    ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence>
                    worldStage = live.freezeTimedWorld(
                    new ReplacementFeatureLiveEvidenceSource.TimedWorldIntent(
                            request,
                            action,
                            profile,
                            expectedAlias,
                            null
                    )
            );
            if (worldStage == null) {
                return CompletableFuture.completedFuture(null);
            }
            return worldStage.thenApply(world -> author(
                    request,
                    action,
                    operationId,
                    key,
                    profile,
                    membership,
                    lease,
                    assignment,
                    policy,
                    expectedAlias,
                    world
            ));
        });
    }

    @Nullable
    private ReplacementCommandTimedSummoningApi.PreparedTransition author(
            CommandTimedSummoningRequest request,
            TimedSummonTransitionRequest.Action action,
            OperationId operationId,
            IdempotencyKey key,
            CompanionProfileReadModel profile,
            CommandRosterMembership membership,
            TimedSummonLease beforeLease,
            PopulationGroupAssignment assignment,
            PopulationGroupConfigIndex policy,
            NpcAlias expectedAlias,
            @Nullable ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence
                    world
    ) {
        if (!validWorld(
                request, action, profile, expectedAlias, world
        )) {
            return null;
        }
        CompanionLifecycle before = profile.lifecycle();
        CompanionLifecycle after = after(
                action, before, membership, world
        );
        TimedSummonLease afterLease = afterLease(
                action, operationId, beforeLease, world.observedAtMs()
        );
        PopulationGroupTransitionAdmissionRequest admission =
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        assignment.assignmentRevision(),
                        policy.revision(),
                        policy.resolvePoliciesForRole(
                                profile.identity().roleId()
                        ),
                        world.observedAtMs()
                );
        TimedSummonTransitionRequest durable =
                new TimedSummonTransitionRequest(
                        action,
                        membership.familyKey(),
                        membership.slotId(),
                        membership.membershipRevision(),
                        beforeLease,
                        afterLease,
                        admission,
                        world.liveAlias(),
                        world.worldKey(),
                        action == TimedSummonTransitionRequest.Action.START
                                ? world.placement()
                                : null,
                        world.snapshot(),
                        StablePersistenceIds.receipt(
                                IDS, identity(request)
                        ),
                        world.observedAtMs()
                );
        return new ReplacementCommandTimedSummoningApi.PreparedTransition(
                operationId, key, durable, false
        );
    }

    private CompanionLifecycle after(
            TimedSummonTransitionRequest.Action action,
            CompanionLifecycle before,
            CommandRosterMembership membership,
            ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence world
    ) {
        LifecycleState state =
                action == TimedSummonTransitionRequest.Action.START
                        ? LifecycleState.ACTIVE
                        : LifecycleState.ROSTER_STORED;
        LifecycleLocation location =
                action == TimedSummonTransitionRequest.Action.START
                        ? LifecycleLocation.liveEntity(
                        world.liveAlias().toString(), world.worldKey()
                )
                        : LifecycleLocation.keyed(
                        LifecycleLocationKind.COMMAND_ROSTER,
                        membership.slotId().toString()
                );
        return new CompanionLifecycle(
                before.profileId(),
                before.ownerId(),
                state,
                location,
                before.revision().next(),
                null,
                world.observedAtMs(),
                before.lastReconciledGeneration(),
                null,
                action == TimedSummonTransitionRequest.Action.START
                        ? world.worldKey()
                        : before.ownerWorldKey()
        );
    }

    private TimedSummonLease afterLease(
            TimedSummonTransitionRequest.Action action,
            OperationId operationId,
            TimedSummonLease before,
            long now
    ) {
        boolean starting =
                action == TimedSummonTransitionRequest.Action.START;
        return new TimedSummonLease(
                before.profileId(),
                Math.addExact(before.leaseRevision(), 1),
                starting
                        ? new TimedSummonSessionId(operationId.value())
                        : null,
                starting && !before.policy().unlimited()
                        ? before.policy().activeDurationMs()
                        : null,
                starting
                        ? null
                        : TimedSummonTime.saturatingAdd(
                        now, before.policy().resummonCooldownMs()
                ),
                before.policy(),
                Set.of(),
                starting ? now : null,
                before.createdAtMs(),
                now
        );
    }

    private boolean validProfile(
            CommandTimedSummoningRequest request,
            @Nullable CompanionProfileReadModel profile
    ) {
        return profile != null
                && profile.identity().roleId() != null
                && profile.lifecycle().ownerId() != null
                && profile.lifecycle().ownerId().value().equals(
                request.ownerUuid()
        )
                && !profile.lifecycle().quarantined()
                && profile.lifecycle().activeOperationId() == null;
    }

    private boolean validMembership(
            CommandTimedSummoningRequest request,
            @Nullable CommandRosterMembership membership
    ) {
        return membership != null
                && membership.familyKey().equals(new CommandFamilyKey(
                new OwnerId(request.ownerUuid()),
                request.commandFamilyId()
        ));
    }

    private boolean validLease(
            TimedSummonTransitionRequest.Action action,
            @Nullable TimedSummonLease lease
    ) {
        if (lease == null) {
            return false;
        }
        return action == TimedSummonTransitionRequest.Action.START
                ? !lease.activeSession()
                : lease.activeSession();
    }

    private boolean validAssignment(
            CompanionProfileReadModel profile,
            @Nullable PopulationGroupAssignment assignment,
            PopulationGroupConfigIndex policy
    ) {
        return assignment != null
                && assignment.roleId() != null
                && assignment.roleId().equals(profile.identity().roleId())
                && assignment.policyRevision() == policy.revision();
    }

    private boolean validWorld(
            CommandTimedSummoningRequest request,
            TimedSummonTransitionRequest.Action action,
            CompanionProfileReadModel profile,
            NpcAlias expectedAlias,
            @Nullable ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence
                    world
    ) {
        if (world == null
                || !world.ownerUuid().equals(request.ownerUuid())
                || !world.liveAlias().equals(expectedAlias)
                || action == TimedSummonTransitionRequest.Action.START
                && (world.placement() == null
                || !world.worldKey().equals(
                world.placement().worldKey()
        ))
                || action == TimedSummonTransitionRequest.Action.STORE
                && world.placement() != null) {
            return false;
        }
        CompanionSnapshot snapshot = world.snapshot();
        if (action == TimedSummonTransitionRequest.Action.START) {
            return profile.currentSnapshots().contains(snapshot)
                    && TimedSummonTransitionRequest.SNAPSHOT_KIND.equals(
                    snapshot.kind()
            );
        }
        return TimedSummonTransitionRequest.SNAPSHOT_KIND.equals(
                snapshot.kind()
        )
                && snapshot.profileId().equals(
                profile.identity().profileId()
        )
                && snapshot.current()
                && snapshot.sourceLifecycleRevision().equals(
                profile.lifecycle().revision().next()
        );
    }

    @Nullable
    private NpcAlias expectedAlias(
            TimedSummonTransitionRequest.Action action,
            OperationId operationId,
            CompanionProfileReadModel profile
    ) {
        if (action == TimedSummonTransitionRequest.Action.START) {
            return StablePersistenceIds.targetAlias(
                    IDS, operationId.toString()
            );
        }
        return profile.currentAlias() == null
                ? null
                : profile.currentAlias().alias();
    }

    private String[] identity(CommandTimedSummoningRequest request) {
        return new String[] {
                request.ownerUuid().toString(),
                request.commandFamilyId(),
                request.profileId(),
                request.idempotencyKey()
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
