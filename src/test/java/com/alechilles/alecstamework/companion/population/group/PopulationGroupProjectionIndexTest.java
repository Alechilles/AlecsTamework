package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChange;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Assignment, lifecycle, metadata, replay, and canonical rebuild tests. */
class PopulationGroupProjectionIndexTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000061");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000061");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000061");

    @Test
    void assignmentAndLifecycleEventsKeepCountsCanonicalAndReplaySafe() {
        CompanionLifecycle active = lifecycle(
                LifecycleState.ACTIVE, 0, "world-a"
        );
        PopulationGroupProjectionIndex index =
                new PopulationGroupProjectionIndex();
        index.rebuild(List.of(), List.of(active));
        assertEquals(Set.of(PROFILE), index.laggingProfiles());

        PopulationGroupAssignment assignment = assignment();
        ProjectionEvent assignmentEvent = event(
                1,
                PopulationGroupAssignmentChangeCodec.draft(
                        OPERATION,
                        new PopulationGroupAssignmentChange(
                                PROFILE, null, assignment
                        )
                )
        );
        assertEquals(
                com.alechilles.alecstamework.persistence.projection
                        .ProjectionApplyOutcome.APPLIED,
                index.apply(assignmentEvent)
        );
        assertTrue(index.laggingProfiles().isEmpty());
        assertEquals(
                new PopulationGroupCounts(1, 1, 0, 0),
                index.counts(bucket())
        );
        assertEquals(
                com.alechilles.alecstamework.persistence.projection
                        .ProjectionApplyOutcome.ALREADY_APPLIED,
                index.apply(assignmentEvent)
        );

        CompanionLifecycle released = lifecycle(
                LifecycleState.RELEASED, 1, "world-a"
        );
        assertEquals(
                com.alechilles.alecstamework.persistence.projection
                        .ProjectionApplyOutcome.APPLIED,
                index.apply(lifecycleEvent(2, active, released))
        );
        assertEquals(
                new PopulationGroupCounts(0, 0, 0, 0),
                index.counts(bucket())
        );
        assertTrue(index.laggingProfiles().isEmpty());
    }

    @Test
    void metadataChangeAndMissingOwnerWorldAreDetectableLag() {
        PopulationGroupProjectionIndex index =
                new PopulationGroupProjectionIndex();
        index.rebuild(List.of(assignment()), List.of(lifecycle(
                LifecycleState.ACTIVE, 0, "world-a"
        )));

        index.apply(metadataEvent(3, "Other", 1));
        assertEquals(Set.of(PROFILE), index.laggingProfiles());

        PopulationGroupProjectionIndex missingWorld =
                new PopulationGroupProjectionIndex();
        missingWorld.rebuild(
                List.of(assignment()),
                List.of(lifecycle(LifecycleState.ACTIVE, 1, null))
        );
        assertEquals(Set.of(PROFILE), missingWorld.laggingProfiles());
    }

    private PopulationGroupAssignment assignment() {
        return new PopulationGroupAssignment(
                PROFILE,
                "Mini",
                List.of(new PopulationGroupMembership(
                        "mod:world", PopulationGroupScope.PER_WORLD
                )),
                7,
                0,
                LifecycleRevision.INITIAL,
                1,
                -100
        );
    }

    private PopulationGroupBucket bucket() {
        return new PopulationGroupBucket(
                OWNER,
                "mod:world",
                PopulationGroupScope.PER_WORLD,
                "world-a"
        );
    }

    private CompanionLifecycle lifecycle(
            LifecycleState state,
            long revision,
            String ownerWorldKey
    ) {
        return new CompanionLifecycle(
                PROFILE,
                OWNER,
                state,
                state == LifecycleState.ACTIVE
                        ? LifecycleLocation.liveEntity("entity", "world-a")
                        : LifecycleLocation.none(),
                new LifecycleRevision(revision),
                null,
                -100 + revision,
                ReconciliationGeneration.INITIAL,
                null,
                ownerWorldKey
        );
    }

    private ProjectionEvent lifecycleEvent(
            long sequence,
            CompanionLifecycle before,
            CompanionLifecycle after
    ) {
        CompanionLifecycleProjectionChange change =
                new CompanionLifecycleProjectionChange(before, after);
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                OPERATION,
                CompanionLifecycleProjectionChangeCodec.EVENT_TYPE,
                PROFILE.toString(),
                after.revision().value(),
                1,
                CompanionLifecycleProjectionChangeCodec.encode(change),
                after.stateChangedAtMs()
        );
    }

    private ProjectionEvent metadataEvent(
            long sequence,
            String roleId,
            long revision
    ) {
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.METADATA,
                        PROFILE,
                        revision,
                        state("Mini"),
                        state(roleId),
                        -90
                );
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                OPERATION,
                CompanionProfileProjectionChangeCodec.EVENT_TYPE,
                CompanionProfileProjectionChangeCodec.aggregateId(change),
                revision,
                CompanionProfileProjectionChangeCodec.VERSION,
                CompanionProfileProjectionChangeCodec.encode(change),
                -90
        );
    }

    private CompanionProfileProjectionState state(String roleId) {
        return new CompanionProfileProjectionState(
                PROFILE,
                null,
                OWNER,
                null,
                roleId,
                "Companion",
                null,
                true,
                null,
                null,
                Set.of(),
                Set.of(),
                -100
        );
    }

    private ProjectionEvent event(
            long sequence,
            ProjectionEventDraft draft
    ) {
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                draft.operationId(),
                draft.eventType(),
                draft.aggregateId(),
                draft.aggregateRevision(),
                draft.payloadVersion(),
                draft.payloadJson(),
                draft.createdAtMs()
        );
    }
}
