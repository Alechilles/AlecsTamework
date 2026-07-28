package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command
        .CommandRosterActionView;
import com.alechilles.alecstamework.companion.command
        .CommandRosterMembership;
import com.alechilles.alecstamework.companion.command
        .CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed
        .TimedSummonProjectionView;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.population
        .OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group
        .PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group
        .PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group
        .PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group
        .PopulationGroupScope;
import com.alechilles.alecstamework.companion.profile
        .CompanionProfileProjectionState;
import com.alechilles.alecstamework.items.persistence
        .SpawnerTameAndLinkIntentEvidence.OwnerPopulationCountEvidence;
import com.alechilles.alecstamework.items.persistence
        .SpawnerTameAndLinkIntentEvidence.OwnerPopulationEvidence;
import com.alechilles.alecstamework.items.persistence
        .SpawnerTameAndLinkIntentEvidence.PopulationGroupCountEvidence;
import com.alechilles.alecstamework.persistence.runtime
        .PublicPersistenceQueries;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Copies the rebuildable persistence projections needed for one tame/link
 * submission without issuing blocking canonical reads.
 */
final class SpawnerTameAndLinkProjectionSource
        implements TameworkSpawnerTameAndLinkEvidenceSource.ProjectionSource {
    private static final String SLOT_NAMESPACE =
            "tamework:capture-tame-command-slot:v1:";

    private final Supplier<ProjectionValues> snapshots;
    private final Function<
            PopulationGroupBucket,
            com.alechilles.alecstamework.companion.population.group
                    .PopulationGroupCounts> groupCounts;

    SpawnerTameAndLinkProjectionSource(PublicPersistenceQueries queries) {
        Objects.requireNonNull(queries, "queries");
        this.snapshots = () -> snapshot(queries);
        this.groupCounts = queries::projectedPopulationGroupCounts;
    }

    SpawnerTameAndLinkProjectionSource(
            Supplier<ProjectionValues> snapshots,
            Function<
                    PopulationGroupBucket,
                    com.alechilles.alecstamework.companion.population.group
                            .PopulationGroupCounts> groupCounts
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.groupCounts = Objects.requireNonNull(
                groupCounts, "groupCounts"
        );
    }

    @Override
    @Nullable
    public TameworkSpawnerTameAndLinkEvidenceSource.ProjectionSnapshot freeze(
            SpawnerTameAndLinkIntentFactory.Input input,
            TameworkSpawnerTameAndLinkEvidenceSource.ConfigSnapshot config
    ) {
        ProjectionValues values = snapshots.get();
        if (values == null) {
            return null;
        }
        if (!values.consistent(input, config.familyKey())) {
            return null;
        }
        CommandRosterSlotId slotId = slotId(input.profileId());
        List<CommandRosterMembership> familyMembers =
                familyMembers(values.actions(), config.familyKey());
        Long rosterRevision = values.rosterRevisions().get(
                config.familyKey()
        );
        if (rosterRevision == null && !familyMembers.isEmpty()) {
            return null;
        }
        long revision = rosterRevision == null ? 0L : rosterRevision;
        CommandRoster roster = rosterRevision == null
                ? null
                : roster(config.familyKey(), revision, familyMembers);
        OwnerPopulationEvidence population =
                ownerPopulation(input, config, values.lifecycles());
        List<PopulationGroupCountEvidence> counts =
                groupCounts(input, config);
        CommandRosterMembership profileMember =
                membership(values.actions().get(input.profileId()));
        CommandRosterMembership slotMember =
                slotMembership(values.actions(), slotId);
        TimedSummonProjectionView timed =
                values.timed().get(input.profileId());
        if (profileMember != null || slotMember != null || timed != null
                || ownerCapacityReached(population)
                || groupCapacityReached(config.groupPolicies(), counts)) {
            return null;
        }
        return new TameworkSpawnerTameAndLinkEvidenceSource
                .ProjectionSnapshot(
                population,
                values.assignments().get(input.profileId()),
                counts,
                revision,
                roster,
                null,
                null,
                null,
                slotId
        );
    }

    private static ProjectionValues snapshot(
            PublicPersistenceQueries queries
    ) {
        return new ProjectionValues(
                Map.copyOf(queries.projectedProfileSnapshot()),
                Map.copyOf(queries.projectedOwnerPopulationSnapshot()),
                Map.copyOf(
                        queries.projectedPopulationGroupAssignments()
                ),
                Set.copyOf(
                        queries.projectedLaggingPopulationGroupProfiles()
                ),
                Map.copyOf(queries.projectedCommandRosterActions()),
                Set.copyOf(queries.projectedLaggingCommandRosterProfiles()),
                Map.copyOf(queries.projectedCommandRosterRevisions()),
                Map.copyOf(queries.projectedTimedSummons()),
                Set.copyOf(
                        queries.projectedLaggingTimedSummonProfiles()
                )
        );
    }

    private OwnerPopulationEvidence ownerPopulation(
            SpawnerTameAndLinkIntentFactory.Input input,
            TameworkSpawnerTameAndLinkEvidenceSource.ConfigSnapshot config,
            Map<ProfileId, CompanionLifecycle> lifecycles
    ) {
        OwnerId owner = config.familyKey().ownerId();
        OwnerPopulationScope global =
                OwnerPopulationScope.global(owner);
        OwnerPopulationScope world =
                OwnerPopulationScope.perWorld(owner, input.worldKey());
        return new OwnerPopulationEvidence(
                config.globalOwnerLimit(),
                config.perWorldOwnerLimit(),
                List.of(
                        ownerCount(global, lifecycles),
                        ownerCount(world, lifecycles)
                )
        );
    }

    private OwnerPopulationCountEvidence ownerCount(
            OwnerPopulationScope scope,
            Map<ProfileId, CompanionLifecycle> lifecycles
    ) {
        long committed = lifecycles.values().stream()
                .filter(lifecycle -> scope.ownerId().equals(
                        lifecycle.ownerId()
                ))
                .filter(lifecycle -> scope.kind()
                        == OwnerPopulationScope.Kind.GLOBAL
                        || Objects.equals(
                        scope.ownerWorldKey(),
                        lifecycle.ownerWorldKey()
                ))
                .count();
        // The rebuildable owner index projects committed lifecycle state.
        // Positive in-flight reservations are rechecked by SQLite prepare.
        return new OwnerPopulationCountEvidence(scope, committed, 0L);
    }

    private List<PopulationGroupCountEvidence> groupCounts(
            SpawnerTameAndLinkIntentFactory.Input input,
            TameworkSpawnerTameAndLinkEvidenceSource.ConfigSnapshot config
    ) {
        ArrayList<PopulationGroupCountEvidence> result = new ArrayList<>();
        for (PopulationGroupPolicy policy : config.groupPolicies().stream()
                .sorted()
                .toList()) {
            PopulationGroupBucket bucket = new PopulationGroupBucket(
                    config.familyKey().ownerId(),
                    policy.groupId(),
                    policy.scope(),
                    policy.scope() == PopulationGroupScope.PER_WORLD
                            ? input.worldKey()
                            : null
            );
            result.add(new PopulationGroupCountEvidence(
                    bucket,
                        groupCounts.apply(bucket)
            ));
        }
        return List.copyOf(result);
    }

    private boolean ownerCapacityReached(OwnerPopulationEvidence evidence) {
        for (OwnerPopulationCountEvidence count : evidence.counts()) {
            int limit = count.scope().kind()
                    == OwnerPopulationScope.Kind.GLOBAL
                    ? evidence.globalLimit()
                    : evidence.perWorldLimit();
            if (exceeds(limit, count.committedCount(),
                    count.pendingCount())) {
                return true;
            }
        }
        return false;
    }

    private boolean groupCapacityReached(
            List<PopulationGroupPolicy> policies,
            List<PopulationGroupCountEvidence> counts
    ) {
        Map<String, PopulationGroupPolicy> byGroup =
                policies.stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                PopulationGroupPolicy::groupId,
                                policy -> policy
                        )
                );
        for (PopulationGroupCountEvidence count : counts) {
            PopulationGroupPolicy policy = byGroup.get(
                    count.bucket().groupId()
            );
            if (policy == null
                    || exceeds(
                    policy.maxOwnedPerOwner(),
                    count.counts().committedOwned(),
                    count.counts().pendingOwned()
            )
                    || exceeds(
                    policy.maxActivePerOwner(),
                    count.counts().committedActive(),
                    count.counts().pendingActive()
            )) {
                return true;
            }
        }
        return false;
    }

    private boolean exceeds(int limit, long committed, long pending) {
        if (limit == 0) {
            return false;
        }
        try {
            return Math.addExact(
                    Math.addExact(committed, pending), 1L
            ) > limit;
        } catch (ArithmeticException overflow) {
            return true;
        }
    }

    private List<CommandRosterMembership> familyMembers(
            Map<ProfileId, CommandRosterActionView> actions,
            CommandFamilyKey family
    ) {
        return actions.values().stream()
                .map(CommandRosterActionView::membership)
                .filter(member -> member.familyKey().equals(family))
                .sorted()
                .toList();
    }

    private CommandRoster roster(
            CommandFamilyKey family,
            long revision,
            List<CommandRosterMembership> members
    ) {
        long created = members.stream()
                .map(CommandRosterMembership::createdAtMs)
                .min(Comparator.naturalOrder())
                .orElse(0L);
        long updated = members.stream()
                .map(CommandRosterMembership::updatedAtMs)
                .max(Comparator.naturalOrder())
                .orElse(0L);
        return new CommandRoster(
                family, revision, members, created, updated
        );
    }

    @Nullable
    private CommandRosterMembership membership(
            @Nullable CommandRosterActionView action
    ) {
        return action == null ? null : action.membership();
    }

    @Nullable
    private CommandRosterMembership slotMembership(
            Map<ProfileId, CommandRosterActionView> actions,
            CommandRosterSlotId slotId
    ) {
        return actions.values().stream()
                .map(CommandRosterActionView::membership)
                .filter(member -> member.slotId().equals(slotId))
                .findFirst()
                .orElse(null);
    }

    private CommandRosterSlotId slotId(ProfileId profileId) {
        UUID value = UUID.nameUUIDFromBytes(
                (SLOT_NAMESPACE + profileId)
                        .getBytes(StandardCharsets.UTF_8)
        );
        return new CommandRosterSlotId(value);
    }

    record ProjectionValues(
            Map<ProfileId, CompanionProfileProjectionState> profiles,
            Map<ProfileId, CompanionLifecycle> lifecycles,
            Map<ProfileId, PopulationGroupAssignment> assignments,
            Set<ProfileId> laggingGroups,
            Map<ProfileId, CommandRosterActionView> actions,
            Set<ProfileId> laggingRosters,
            Map<CommandFamilyKey, Long> rosterRevisions,
            Map<ProfileId, TimedSummonProjectionView> timed,
            Set<ProfileId> laggingTimed
    ) {
        private boolean consistent(
                SpawnerTameAndLinkIntentFactory.Input input,
                CommandFamilyKey family
        ) {
            ProfileId profileId = input.profileId();
            CompanionProfileProjectionState profile =
                    profiles.get(profileId);
            CompanionLifecycle lifecycle = lifecycles.get(profileId);
            if (profile != null && (lifecycle == null
                    || profile.currentAlias() != null
                    && !profile.currentAlias().equals(
                    input.sourceAlias()
            ))) {
                return false;
            }
            if (lifecycle != null && profile == null) {
                return false;
            }
            if (laggingGroups.contains(profileId)
                    || laggingRosters.contains(profileId)
                    || laggingTimed.contains(profileId)) {
                return false;
            }
            return actions.values().stream()
                    .map(CommandRosterActionView::membership)
                    .filter(member -> member.familyKey().equals(family))
                    .allMatch(member ->
                            rosterRevisions.containsKey(family));
        }
    }
}
