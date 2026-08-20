package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChange;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSubscription;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/** Rebuildable post-commit index for owner-family command actions. */
public final class CommandRosterProjectionIndex
        implements ProjectionConsumer {
    public static final ProjectionConsumerId CONSUMER_ID =
            new ProjectionConsumerId("command_roster_index");

    private final Map<ProfileId, CommandRosterMembership> memberships =
            new HashMap<>();
    private final Map<CommandFamilyKey, Long> familyRevisions =
            new HashMap<>();
    private final Map<ProfileId, CompanionLifecycle> lifecycles =
            new HashMap<>();
    private final Map<ProfileId, ProfileEvidence> profiles =
            new HashMap<>();

    @Override
    public ProjectionConsumerId consumerId() {
        return CONSUMER_ID;
    }

    @Override
    public ProjectionSubscription subscription() {
        return ProjectionSubscription.events(Set.of(
                CommandRosterMembershipChangeCodec.EVENT_TYPE,
                CompanionLifecycleProjectionChangeCodec.EVENT_TYPE,
                CompanionProfileProjectionChangeCodec.EVENT_TYPE
        ));
    }

    @Override
    public synchronized ProjectionApplyOutcome apply(
            @Nonnull ProjectionEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Command roster projection event is required"
            );
        }
        if (CommandRosterMembershipChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return applyMembership(event);
        }
        if (CompanionLifecycleProjectionChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return applyLifecycle(event);
        }
        if (CompanionProfileProjectionChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return applyProfile(event);
        }
        return ProjectionApplyOutcome.IRRELEVANT;
    }

    /** Rebuilds all command action state from one canonical read. */
    public synchronized void rebuild(
            @Nonnull Collection<CommandRoster> rosters,
            @Nonnull Collection<CommandRosterProjectionSeed> seeds,
            @Nonnull Collection<CompanionLifecycle> lifecycles
    ) {
        if (rosters == null || seeds == null || lifecycles == null) {
            throw new IllegalArgumentException(
                    "Complete command roster rebuild evidence is required"
            );
        }
        memberships.clear();
        familyRevisions.clear();
        this.lifecycles.clear();
        profiles.clear();
        for (CommandRoster roster : List.copyOf(rosters)) {
            if (roster == null || familyRevisions.putIfAbsent(
                    roster.familyKey(), roster.rosterRevision()
            ) != null) {
                throw new IllegalArgumentException(
                        "Command rosters must have unique families"
                );
            }
            for (CommandRosterMembership membership
                    : roster.memberships()) {
                if (memberships.putIfAbsent(
                        membership.profileId(), membership
                ) != null) {
                    throw new IllegalArgumentException(
                            "Command profiles must occupy one slot"
                    );
                }
            }
        }
        for (CompanionLifecycle lifecycle
                : List.copyOf(lifecycles)) {
            if (lifecycle == null || this.lifecycles.putIfAbsent(
                    lifecycle.profileId(), lifecycle
            ) != null) {
                throw new IllegalArgumentException(
                        "Command lifecycles must have unique profiles"
                );
            }
        }
        for (CommandRosterProjectionSeed seed
                : List.copyOf(seeds)) {
            if (seed == null || !seed.membership().equals(
                    memberships.get(seed.membership().profileId())
            ) || !seed.lifecycle().equals(
                    this.lifecycles.get(
                            seed.membership().profileId()
                    )
            ) || profiles.putIfAbsent(
                    seed.membership().profileId(),
                    ProfileEvidence.from(seed)
            ) != null) {
                throw new IllegalArgumentException(
                        "Command projection seeds must match unique slots"
                );
            }
        }
    }

    /** Returns complete action views whose canonical joins are currently valid. */
    @Nonnull
    public synchronized Map<ProfileId, CommandRosterActionView>
    actionSnapshot() {
        HashMap<ProfileId, CommandRosterActionView> views =
                new HashMap<>();
        Set<ProfileId> lagging = laggingProfiles();
        for (CommandRosterMembership membership
                : memberships.values()) {
            if (lagging.contains(membership.profileId())) {
                continue;
            }
            ProfileEvidence profile =
                    profiles.get(membership.profileId());
            views.put(
                    membership.profileId(),
                    new CommandRosterActionView(
                            membership,
                            profile.roleId(),
                            profile.metadataRevision(),
                            profile.alias(),
                            lifecycles.get(membership.profileId())
                    )
            );
        }
        return Map.copyOf(views);
    }

    /** Returns exact profile scopes whose roster/canonical join is inconsistent. */
    @Nonnull
    public synchronized Set<ProfileId> laggingProfiles() {
        HashSet<ProfileId> lagging = new HashSet<>();
        for (CommandRosterMembership membership
                : memberships.values()) {
            CompanionLifecycle lifecycle =
                    lifecycles.get(membership.profileId());
            ProfileEvidence profile =
                    profiles.get(membership.profileId());
            if (lifecycle == null || profile == null
                    || profile.roleId() == null
                    || !membership.familyKey().ownerId().equals(
                    lifecycle.ownerId()
            ) || !storedSlotMatches(membership, lifecycle)) {
                lagging.add(membership.profileId());
            }
        }
        for (CompanionLifecycle lifecycle : lifecycles.values()) {
            if (lifecycle.state() == LifecycleState.ROSTER_STORED
                    && !storedSlotMatches(
                    memberships.get(lifecycle.profileId()), lifecycle
            )) {
                lagging.add(lifecycle.profileId());
            }
        }
        return Set.copyOf(lagging);
    }

    @Nonnull
    public synchronized Map<CommandFamilyKey, Long>
    familyRevisionSnapshot() {
        return Map.copyOf(familyRevisions);
    }

    private ProjectionApplyOutcome applyMembership(
            ProjectionEvent event
    ) {
        CommandRosterMutationOutcome change =
                CommandRosterMembershipChangeCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                );
        CommandRosterMembership evidence = change.after() == null
                ? change.before()
                : change.after();
        if (!event.aggregateId().equals(
                evidence.profileId().toString()
        ) || event.aggregateRevision()
                != change.currentRosterRevision()) {
            throw new IllegalArgumentException(
                    "command_roster_membership_event_mismatch"
            );
        }
        long currentRevision = familyRevisions.getOrDefault(
                change.familyKey(), 0L
        );
        if (currentRevision >= change.currentRosterRevision()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        if (currentRevision != change.previousRosterRevision()) {
            throw new IllegalArgumentException(
                    "command_roster_family_revision_gap"
            );
        }
        if (change.after() == null) {
            memberships.remove(change.before().profileId());
        } else {
            memberships.put(
                    change.after().profileId(), change.after()
            );
        }
        familyRevisions.put(
                change.familyKey(), change.currentRosterRevision()
        );
        return ProjectionApplyOutcome.APPLIED;
    }

    private ProjectionApplyOutcome applyLifecycle(
            ProjectionEvent event
    ) {
        CompanionLifecycleProjectionChange change =
                CompanionLifecycleProjectionChangeCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                );
        CompanionLifecycle after = change.after();
        if (!event.aggregateId().equals(after.profileId().toString())
                || event.aggregateRevision()
                != after.revision().value()) {
            throw new IllegalArgumentException(
                    "command_roster_lifecycle_event_mismatch"
            );
        }
        CompanionLifecycle current =
                lifecycles.get(after.profileId());
        if (current != null && current.revision().value()
                >= after.revision().value()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        lifecycles.put(after.profileId(), after);
        return ProjectionApplyOutcome.APPLIED;
    }

    private ProjectionApplyOutcome applyProfile(
            ProjectionEvent event
    ) {
        CompanionProfileProjectionChange change =
                CompanionProfileProjectionChangeCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                );
        if (!event.aggregateId().equals(
                CompanionProfileProjectionChangeCodec.aggregateId(change)
        ) || event.aggregateRevision() != change.sourceRevision()) {
            throw new IllegalArgumentException(
                    "command_roster_profile_event_mismatch"
            );
        }
        if (change.source()
                != CompanionProfileProjectionChange.Source.METADATA
                && change.source()
                != CompanionProfileProjectionChange.Source.ALIAS) {
            return ProjectionApplyOutcome.IRRELEVANT;
        }
        ProfileEvidence current = profiles.get(change.profileId());
        if (current != null && current.revision(change.source())
                >= change.sourceRevision()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        profiles.put(
                change.profileId(),
                ProfileEvidence.update(current, change)
        );
        return ProjectionApplyOutcome.APPLIED;
    }

    private boolean storedSlotMatches(
            CommandRosterMembership membership,
            CompanionLifecycle lifecycle
    ) {
        if (membership == null || lifecycle == null) {
            return false;
        }
        if (lifecycle.state() != LifecycleState.ROSTER_STORED) {
            return true;
        }
        return lifecycle.location().kind()
                == LifecycleLocationKind.COMMAND_ROSTER
                && Objects.equals(
                lifecycle.location().key(),
                membership.slotId().toString()
        );
    }

    private record ProfileEvidence(
            String roleId,
            long metadataRevision,
            NpcAlias alias,
            long aliasRevision
    ) {
        private static ProfileEvidence from(
                CommandRosterProjectionSeed seed
        ) {
            return new ProfileEvidence(
                    seed.identity().roleId(),
                    seed.identity().metadataRevision(),
                    seed.currentAlias() == null
                            ? null
                            : seed.currentAlias().alias(),
                    seed.currentAlias() == null
                            ? 0
                            : seed.currentAlias().generation()
            );
        }

        private static ProfileEvidence update(
                ProfileEvidence current,
                CompanionProfileProjectionChange change
        ) {
            CompanionProfileProjectionState state = change.after();
            String role = current == null ? null : current.roleId();
            long metadata = current == null
                    ? 0
                    : current.metadataRevision();
            NpcAlias alias = current == null ? null : current.alias();
            long aliasRevision = current == null
                    ? 0
                    : current.aliasRevision();
            if (change.source()
                    == CompanionProfileProjectionChange.Source.METADATA) {
                role = state == null ? null : state.roleId();
                metadata = change.sourceRevision();
            } else {
                alias = state == null ? null : state.currentAlias();
                aliasRevision = change.sourceRevision();
            }
            return new ProfileEvidence(
                    role, metadata, alias, aliasRevision
            );
        }

        private long revision(
                CompanionProfileProjectionChange.Source source
        ) {
            return source
                    == CompanionProfileProjectionChange.Source.METADATA
                    ? metadataRevision
                    : aliasRevision;
        }
    }
}

