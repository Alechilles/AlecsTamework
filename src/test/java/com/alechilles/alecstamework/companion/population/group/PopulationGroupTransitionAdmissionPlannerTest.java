package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Positive-delta admission tests for shared lifecycle participants. */
class PopulationGroupTransitionAdmissionPlannerTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000081");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000081");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000081");

    @Test
    void capturedToLiveReservesOnlyNewActiveCapacity() {
        PopulationGroupReservation reservation =
                PopulationGroupTransitionAdmissionPlanner.plan(
                        OPERATION,
                        request(captured(3), active(4), List.of(policy())),
                        assignment()
                ).getFirst();

        assertEquals(0, reservation.ownedDelta());
        assertEquals(1, reservation.activeDelta());
        assertEquals(PROFILE, reservation.profileId());
        assertEquals(new LifecycleRevision(3),
                reservation.expectedLifecycleRevision());
    }

    @Test
    void liveToCapturedNeedsNoPositiveCapacityReservation() {
        assertEquals(
                List.of(),
                PopulationGroupTransitionAdmissionPlanner.plan(
                        OPERATION,
                        request(active(3), captured(4), List.of(policy())),
                        assignment()
                )
        );
    }

    @Test
    void completePolicySnapshotMustMatchCanonicalAssignment() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PopulationGroupTransitionAdmissionPlanner.plan(
                        OPERATION,
                        request(captured(3), active(4), List.of()),
                        assignment()
                )
        );
    }

    private PopulationGroupTransitionAdmissionRequest request(
            CompanionLifecycle before,
            CompanionLifecycle after,
            List<PopulationGroupPolicy> policies
    ) {
        return new PopulationGroupTransitionAdmissionRequest(
                before,
                after,
                7,
                11,
                policies,
                -100
        );
    }

    private PopulationGroupAssignment assignment() {
        return new PopulationGroupAssignment(
                PROFILE,
                "Mini",
                List.of(new PopulationGroupMembership(
                        "mod:mini", PopulationGroupScope.GLOBAL
                )),
                11,
                2,
                new LifecycleRevision(3),
                7,
                -200
        );
    }

    private PopulationGroupPolicy policy() {
        return new PopulationGroupPolicy(
                "mod:mini",
                PopulationGroupScope.GLOBAL,
                3,
                1,
                11
        );
    }

    private CompanionLifecycle captured(long revision) {
        return lifecycle(
                LifecycleState.CAPTURED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM,
                        "50000000-0000-0000-0000-000000000081"
                ),
                revision
        );
    }

    private CompanionLifecycle active(long revision) {
        return lifecycle(
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity("entity", "world-a"),
                revision
        );
    }

    private CompanionLifecycle lifecycle(
            LifecycleState state,
            LifecycleLocation location,
            long revision
    ) {
        return new CompanionLifecycle(
                PROFILE,
                OWNER,
                state,
                location,
                new LifecycleRevision(revision),
                null,
                -100 + revision,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
    }
}
