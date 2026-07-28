package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChange;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Canonical rebuild, event replay, and owner-scope count tests. */
class OwnerPopulationProjectionIndexTest {
    private static final ProfileId PROFILE_A =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileId PROFILE_B =
            ProfileId.parse("20000000-0000-0000-0000-000000000002");
    private static final OwnerId OWNER_A =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER_B =
            OwnerId.parse("30000000-0000-0000-0000-000000000002");

    @Test
    void rebuildAndSelfContainedChangeProduceCanonicalCounts() {
        CompanionLifecycle active = lifecycle(
                PROFILE_A,
                OWNER_A,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity("entity-a", "physical-world"),
                0,
                "world-a"
        );
        CompanionLifecycle captured = lifecycle(
                PROFILE_B,
                OWNER_A,
                LifecycleState.CAPTURED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM,
                        "capture-b"
                ),
                0,
                "world-a"
        );
        OwnerPopulationProjectionIndex index =
                new OwnerPopulationProjectionIndex();
        index.rebuild(List.of(active, captured));

        assertEquals(
                2,
                index.count(OwnerPopulationScope.global(OWNER_A))
        );
        assertEquals(
                2,
                index.count(
                        OwnerPopulationScope.perWorld(OWNER_A, "world-a")
                )
        );

        CompanionLifecycle transferred = lifecycle(
                PROFILE_A,
                OWNER_B,
                LifecycleState.ACTIVE,
                active.location(),
                1,
                "world-b"
        );
        ProjectionEvent event = event(
                new CompanionLifecycleProjectionChange(active, transferred)
        );
        assertEquals(ProjectionApplyOutcome.APPLIED, index.apply(event));
        assertEquals(
                ProjectionApplyOutcome.ALREADY_APPLIED,
                index.apply(event)
        );

        assertEquals(
                1,
                index.count(OwnerPopulationScope.global(OWNER_A))
        );
        assertEquals(
                1,
                index.count(
                        OwnerPopulationScope.perWorld(OWNER_A, "world-a")
                )
        );
        assertEquals(
                1,
                index.count(OwnerPopulationScope.global(OWNER_B))
        );
        assertEquals(
                1,
                index.count(
                        OwnerPopulationScope.perWorld(OWNER_B, "world-b")
                )
        );
        assertEquals(transferred, index.snapshot().get(PROFILE_A));
    }

    private ProjectionEvent event(
            CompanionLifecycleProjectionChange change
    ) {
        String payload =
                CompanionLifecycleProjectionChangeCodec.encode(change);
        assertEquals(
                change,
                CompanionLifecycleProjectionChangeCodec.decode(1, payload)
        );
        return new ProjectionEvent(
                new ProjectionSequence(1),
                new OperationId(new UUID(0, 1)),
                CompanionLifecycleProjectionChangeCodec.EVENT_TYPE,
                change.after().profileId().toString(),
                change.after().revision().value(),
                1,
                payload,
                change.after().stateChangedAtMs()
        );
    }

    private CompanionLifecycle lifecycle(
            ProfileId profileId,
            OwnerId ownerId,
            LifecycleState state,
            LifecycleLocation location,
            long revision,
            String ownerWorldKey
    ) {
        return new CompanionLifecycle(
                profileId,
                ownerId,
                state,
                location,
                new LifecycleRevision(revision),
                null,
                -100 + revision,
                ReconciliationGeneration.INITIAL,
                null,
                ownerWorldKey
        );
    }
}

