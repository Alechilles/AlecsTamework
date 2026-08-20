package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for independent weighted domain dimensions and world deltas. */
class PopulationDomainAdmissionPlannerTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000451"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "30000000-0000-0000-0000-000000000451"
    );
    private static final OperationId OPERATION = OperationId.parse(
            "40000000-0000-0000-0000-000000000451"
    );

    @Test
    void deployableOnlyDomainProducesDeployableCapacityWithoutOwnedCapacity() {
        PopulationDomainAdmissionPlanner.DomainPolicy policy =
                new PopulationDomainAdmissionPlanner.DomainPolicy(
                        "runeteria:deployable", PopulationDomainScope.GLOBAL,
                        false, true, 2, 0, 4, 1
                );

        List<PopulationDomainReservation> reservations =
                PopulationDomainAdmissionPlanner.plan(
                        OPERATION, PROFILE, null, null, null, OWNER, null,
                        LifecycleState.ACTIVE, "world-a", List.of(policy),
                        2, 3, 4
                );

        assertEquals(1, reservations.size());
        PopulationDomainReservation reservation = reservations.getFirst();
        assertEquals(0, reservation.ownedDelta());
        assertEquals(1, reservation.deployableDelta());
        assertEquals(2, reservation.weightedDeployableDelta());
    }

    @Test
    void sameOwnerMoveReservesDestinationPerWorldBucket() {
        PopulationDomainAdmissionPlanner.DomainPolicy policy =
                new PopulationDomainAdmissionPlanner.DomainPolicy(
                        "runeteria:owned", PopulationDomainScope.PER_WORLD,
                        true, true, 1, 4, 4, 1
                );

        List<PopulationDomainReservation> reservations =
                PopulationDomainAdmissionPlanner.plan(
                        OPERATION, PROFILE, null, OWNER, "world-a", OWNER,
                        LifecycleState.ACTIVE, LifecycleState.ACTIVE, "world-b",
                        List.of(policy), 2, 3, 4
                );

        assertEquals(1, reservations.size());
        assertEquals("world-b", reservations.getFirst().bucket().ownerWorldKey());
        assertEquals(1, reservations.getFirst().ownedDelta());
        assertTrue(reservations.getFirst().deployableDelta() > 0);
    }

    @Test
    void releaseHasNoPositiveDomainDelta() {
        PopulationDomainAdmissionPlanner.DomainPolicy policy =
                new PopulationDomainAdmissionPlanner.DomainPolicy(
                        "runeteria:owned", PopulationDomainScope.GLOBAL,
                        true, true, 1, 4, 4, 1
                );
        assertTrue(PopulationDomainAdmissionPlanner.plan(
                OPERATION, PROFILE, null, OWNER, "world-a", null,
                LifecycleState.ACTIVE, LifecycleState.RELEASED, "world-a",
                List.of(policy), 2, 3, 4
        ).isEmpty());
    }
}
