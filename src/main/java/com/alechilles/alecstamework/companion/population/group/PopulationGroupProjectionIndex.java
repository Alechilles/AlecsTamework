package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChange;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Rebuildable group counts over assignments plus the latest canonical lifecycle evidence.
 */
public final class PopulationGroupProjectionIndex
        implements ProjectionConsumer {
    public static final ProjectionConsumerId CONSUMER_ID =
            new ProjectionConsumerId("population_group_index");

    private final Map<ProfileId, PopulationGroupAssignment> assignments =
            new HashMap<>();
    private final Map<ProfileId, CompanionLifecycle> lifecycles =
            new HashMap<>();
    private final Map<ProfileId, MetadataEvidence> metadata =
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
                    "Population group projection event is required"
            );
        }
        if (PopulationGroupAssignmentChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return applyAssignment(event);
        }
        if (CompanionLifecycleProjectionChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return applyLifecycle(event);
        }
        if (CompanionProfileProjectionChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return applyMetadata(event);
        }
        return ProjectionApplyOutcome.IRRELEVANT;
    }

    /** Replaces all derived state from one consistent assignment/lifecycle read. */
    public synchronized void rebuild(
            @Nonnull Collection<PopulationGroupAssignment> assignments,
            @Nonnull Collection<CompanionLifecycle> lifecycles
    ) {
        if (assignments == null || lifecycles == null) {
            throw new IllegalArgumentException(
                    "Population group canonical rebuild evidence is required"
            );
        }
        this.assignments.clear();
        this.lifecycles.clear();
        metadata.clear();
        for (PopulationGroupAssignment assignment
                : List.copyOf(assignments)) {
            if (assignment == null || this.assignments.putIfAbsent(
                    assignment.profileId(), assignment
            ) != null) {
                throw new IllegalArgumentException(
                        "Group assignments must have unique profiles"
                );
            }
            metadata.put(
                    assignment.profileId(),
                    new MetadataEvidence(
                            assignment.roleId(),
                            assignment.sourceMetadataRevision()
                    )
            );
        }
        for (CompanionLifecycle lifecycle : List.copyOf(lifecycles)) {
            if (lifecycle == null || this.lifecycles.putIfAbsent(
                    lifecycle.profileId(), lifecycle
            ) != null) {
                throw new IllegalArgumentException(
                        "Group lifecycles must have unique profiles"
                );
            }
        }
    }

    /** Returns the committed derived count in one exact group bucket. */
    public synchronized PopulationGroupCounts counts(
            @Nonnull PopulationGroupBucket bucket
    ) {
        if (bucket == null) {
            throw new IllegalArgumentException(
                    "Population group bucket is required"
            );
        }
        long owned = 0;
        long active = 0;
        for (PopulationGroupAssignment assignment
                : assignments.values()) {
            if (!assignment.memberships().contains(
                    new PopulationGroupMembership(
                            bucket.groupId(), bucket.scope()
                    )
            )) {
                continue;
            }
            CompanionLifecycle lifecycle =
                    lifecycles.get(assignment.profileId());
            if (!belongs(lifecycle, bucket)) {
                continue;
            }
            if (PopulationGroupLifecycleClassifier.consumesOwned(
                    lifecycle.state()
            )) {
                owned++;
                if (PopulationGroupLifecycleClassifier.consumesActive(
                        lifecycle.state()
                )) {
                    active++;
                }
            }
        }
        return new PopulationGroupCounts(owned, active, 0, 0);
    }

    /** Returns exact profiles whose assignment cannot safely serve current evidence. */
    public synchronized Set<ProfileId> laggingProfiles() {
        HashSet<ProfileId> lagging = new HashSet<>();
        for (ProfileId profileId : lifecycles.keySet()) {
            if (!assignments.containsKey(profileId)) {
                lagging.add(profileId);
            }
        }
        for (PopulationGroupAssignment assignment
                : assignments.values()) {
            CompanionLifecycle lifecycle =
                    lifecycles.get(assignment.profileId());
            MetadataEvidence identity =
                    metadata.get(assignment.profileId());
            if (lifecycle == null
                    || lifecycle.revision().value()
                    < assignment.sourceLifecycleRevision().value()
                    || identity == null
                    || identity.revision()
                    != assignment.sourceMetadataRevision()
                    || !Objects.equals(
                    identity.roleId(), assignment.roleId()
            )
                    || missingPerWorldBucket(assignment, lifecycle)) {
                lagging.add(assignment.profileId());
            }
        }
        return Set.copyOf(lagging);
    }

    @Nonnull
    public synchronized Map<ProfileId, PopulationGroupAssignment>
    assignmentSnapshot() {
        return Map.copyOf(assignments);
    }

    private ProjectionApplyOutcome applyAssignment(
            ProjectionEvent event
    ) {
        PopulationGroupAssignmentChange change =
                PopulationGroupAssignmentChangeCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                );
        PopulationGroupAssignment after = change.after();
        if (!event.aggregateId().equals(change.profileId().toString())
                || event.aggregateRevision()
                != after.assignmentRevision()) {
            throw new IllegalArgumentException(
                    "population_group_assignment_event_mismatch"
            );
        }
        PopulationGroupAssignment current =
                assignments.get(change.profileId());
        if (current != null && current.assignmentRevision()
                >= after.assignmentRevision()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        assignments.put(change.profileId(), after);
        metadata.put(
                change.profileId(),
                new MetadataEvidence(
                        after.roleId(), after.sourceMetadataRevision()
                )
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
                    "population_group_lifecycle_event_mismatch"
            );
        }
        CompanionLifecycle current = lifecycles.get(after.profileId());
        if (current != null && current.revision().value()
                >= after.revision().value()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        lifecycles.put(after.profileId(), after);
        return ProjectionApplyOutcome.APPLIED;
    }

    private ProjectionApplyOutcome applyMetadata(ProjectionEvent event) {
        CompanionProfileProjectionChange change =
                CompanionProfileProjectionChangeCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                );
        if (!event.aggregateId().equals(
                CompanionProfileProjectionChangeCodec.aggregateId(change)
        ) || event.aggregateRevision() != change.sourceRevision()) {
            throw new IllegalArgumentException(
                    "population_group_metadata_event_mismatch"
            );
        }
        if (change.source()
                != CompanionProfileProjectionChange.Source.METADATA) {
            return ProjectionApplyOutcome.IRRELEVANT;
        }
        MetadataEvidence current = metadata.get(change.profileId());
        if (current != null
                && current.revision() >= change.sourceRevision()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        metadata.put(
                change.profileId(),
                new MetadataEvidence(
                        change.after() == null
                                ? null
                                : change.after().roleId(),
                        change.sourceRevision()
                )
        );
        return ProjectionApplyOutcome.APPLIED;
    }

    private boolean belongs(
            CompanionLifecycle lifecycle,
            PopulationGroupBucket bucket
    ) {
        return lifecycle != null
                && bucket.ownerId().equals(lifecycle.ownerId())
                && (bucket.scope() == PopulationGroupScope.GLOBAL
                || Objects.equals(
                bucket.ownerWorldKey(), lifecycle.ownerWorldKey()
        ));
    }

    private boolean missingPerWorldBucket(
            PopulationGroupAssignment assignment,
            CompanionLifecycle lifecycle
    ) {
        return lifecycle.ownerId() != null
                && lifecycle.ownerWorldKey() == null
                && assignment.memberships().stream().anyMatch(
                membership -> membership.scope()
                        == PopulationGroupScope.PER_WORLD
        );
    }

    private record MetadataEvidence(String roleId, long revision) {
    }
}
