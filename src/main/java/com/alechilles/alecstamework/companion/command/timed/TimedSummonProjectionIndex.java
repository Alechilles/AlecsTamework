package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipChangeCodec;
import com.alechilles.alecstamework.companion.command.CommandRosterMutationOutcome;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/** Rebuildable timed session index joined from lease, roster, and lifecycle. */
public final class TimedSummonProjectionIndex
        implements ProjectionConsumer {
    public static final ProjectionConsumerId CONSUMER_ID =
            new ProjectionConsumerId("timed_summon_index");

    private final Map<ProfileId, TimedSummonLease> leases =
            new HashMap<>();
    private final Map<ProfileId, CommandRosterMembership> memberships =
            new HashMap<>();
    private final Map<CommandFamilyKey, Long> familyRevisions =
            new HashMap<>();
    private final Map<ProfileId, CompanionLifecycle> lifecycles =
            new HashMap<>();

    @Override
    public ProjectionConsumerId consumerId() {
        return CONSUMER_ID;
    }

    @Override
    public synchronized ProjectionApplyOutcome apply(
            @Nonnull ProjectionEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Timed summon projection event is required"
            );
        }
        if (TimedSummonLeaseChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return applyLease(event);
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
        return ProjectionApplyOutcome.IRRELEVANT;
    }

    /** Rebuilds from one bounded canonical snapshot without runtime caches. */
    public synchronized void rebuild(
            @Nonnull Collection<TimedSummonLease> leases,
            @Nonnull Collection<CommandRoster> rosters,
            @Nonnull Collection<CompanionLifecycle> lifecycles
    ) {
        if (leases == null || rosters == null || lifecycles == null) {
            throw new IllegalArgumentException(
                    "Complete timed summon rebuild evidence is required"
            );
        }
        this.leases.clear();
        memberships.clear();
        familyRevisions.clear();
        this.lifecycles.clear();
        for (TimedSummonLease lease : List.copyOf(leases)) {
            if (lease == null || this.leases.putIfAbsent(
                    lease.profileId(), lease
            ) != null) {
                throw new IllegalArgumentException(
                        "Timed leases must have unique profiles"
                );
            }
        }
        for (CommandRoster roster : List.copyOf(rosters)) {
            if (roster == null || familyRevisions.putIfAbsent(
                    roster.familyKey(), roster.rosterRevision()
            ) != null) {
                throw new IllegalArgumentException(
                        "Timed rebuild rosters must have unique families"
                );
            }
            for (CommandRosterMembership membership
                    : roster.memberships()) {
                if (memberships.putIfAbsent(
                        membership.profileId(), membership
                ) != null) {
                    throw new IllegalArgumentException(
                            "Timed rebuild profiles must occupy one slot"
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
                        "Timed rebuild lifecycles must have unique profiles"
                );
            }
        }
    }

    /** Returns only exact, internally consistent timed session joins. */
    @Nonnull
    public synchronized Map<ProfileId, TimedSummonProjectionView>
    readySnapshot() {
        HashMap<ProfileId, TimedSummonProjectionView> result =
                new HashMap<>();
        Set<ProfileId> lagging = laggingProfiles();
        for (TimedSummonLease lease : leases.values()) {
            if (!lagging.contains(lease.profileId())) {
                result.put(
                        lease.profileId(),
                        new TimedSummonProjectionView(
                                lease,
                                memberships.get(lease.profileId()),
                                lifecycles.get(lease.profileId())
                        )
                );
            }
        }
        return Map.copyOf(result);
    }

    /** Returns profiles whose canonical lease/roster/lifecycle join is torn. */
    @Nonnull
    public synchronized Set<ProfileId> laggingProfiles() {
        HashSet<ProfileId> result = new HashSet<>();
        for (TimedSummonLease lease : leases.values()) {
            CommandRosterMembership membership =
                    memberships.get(lease.profileId());
            CompanionLifecycle lifecycle =
                    lifecycles.get(lease.profileId());
            if (membership == null || lifecycle == null
                    || !consistent(lease, membership, lifecycle)) {
                result.add(lease.profileId());
            }
        }
        return Set.copyOf(result);
    }

    private ProjectionApplyOutcome applyLease(ProjectionEvent event) {
        if (event.payloadVersion()
                != TimedSummonLeaseChangeCodec.VERSION) {
            throw new IllegalArgumentException(
                    "timed_summon_lease_event_version"
            );
        }
        TimedSummonLeaseChange change =
                TimedSummonLeaseChangeCodec.decode(
                        event.payloadJson()
                );
        TimedSummonLease after = change.after();
        if (!event.aggregateId().equals(after.profileId().toString())
                || event.aggregateRevision() != after.leaseRevision()) {
            throw new IllegalArgumentException(
                    "timed_summon_lease_event_mismatch"
            );
        }
        TimedSummonLease current = leases.get(after.profileId());
        if (current != null
                && current.leaseRevision() >= after.leaseRevision()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        if (!java.util.Objects.equals(current, change.before())) {
            throw new IllegalArgumentException(
                    "timed_summon_lease_revision_gap"
            );
        }
        leases.put(after.profileId(), after);
        return ProjectionApplyOutcome.APPLIED;
    }

    private ProjectionApplyOutcome applyMembership(
            ProjectionEvent event
    ) {
        CommandRosterMutationOutcome change =
                CommandRosterMembershipChangeCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                );
        long current = familyRevisions.getOrDefault(
                change.familyKey(), 0L
        );
        if (current >= change.currentRosterRevision()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        if (current != change.previousRosterRevision()) {
            throw new IllegalArgumentException(
                    "timed_summon_roster_revision_gap"
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
        CompanionLifecycle after =
                CompanionLifecycleProjectionChangeCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                ).after();
        CompanionLifecycle current = lifecycles.get(after.profileId());
        if (current != null && current.revision().compareTo(
                after.revision()
        ) >= 0) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        lifecycles.put(after.profileId(), after);
        return ProjectionApplyOutcome.APPLIED;
    }

    private boolean consistent(
            TimedSummonLease lease,
            CommandRosterMembership membership,
            CompanionLifecycle lifecycle
    ) {
        if (!membership.familyKey().ownerId().equals(
                lifecycle.ownerId()
        )) {
            return false;
        }
        if (lease.activeSession()) {
            return lifecycle.state() == LifecycleState.ACTIVE
                    || lifecycle.state() == LifecycleState.UNLOADED;
        }
        return lifecycle.state() == LifecycleState.ROSTER_STORED
                && lifecycle.location().kind()
                == LifecycleLocationKind.COMMAND_ROSTER
                && membership.slotId().toString().equals(
                lifecycle.location().key()
        );
    }
}

