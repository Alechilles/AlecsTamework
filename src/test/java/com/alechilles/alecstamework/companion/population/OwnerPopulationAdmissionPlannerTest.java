package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Positive-delta derivation and durable payload round-trip tests. */
class OwnerPopulationAdmissionPlannerTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER_A =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER_B =
            OwnerId.parse("30000000-0000-0000-0000-000000000002");

    @Test
    void transferReservesTargetGlobalAndPerWorldWithoutReleasingSource() {
        OwnerPopulationTransitionRequest request = transition(
                OWNER_A,
                "world-a",
                OWNER_B,
                "world-b",
                5,
                3
        );

        OwnerPopulationAdmissionPlan plan =
                OwnerPopulationAdmissionPlanner.plan(request).orElseThrow();

        assertEquals(
                List.of(
                        new OwnerPopulationAdmissionPlan.LimitIncrease(
                                OwnerPopulationScope.global(OWNER_B),
                                1,
                                5
                        ),
                        new OwnerPopulationAdmissionPlan.LimitIncrease(
                                OwnerPopulationScope.perWorld(
                                        OWNER_B,
                                        "world-b"
                                ),
                                1,
                                3
                        )
                ),
                plan.increases()
        );
        assertEquals(
                request,
                OwnerPopulationTransitionDefinition.INSTANCE.decode(
                        OwnerPopulationTransitionDefinition.INSTANCE.encode(
                                request
                        )
                )
        );
    }

    @Test
    void clearNeedsNoReservationAndRehomeNeedsOnlyTargetWorld() {
        assertTrue(OwnerPopulationAdmissionPlanner.plan(transition(
                OWNER_A,
                "world-a",
                null,
                null,
                5,
                3
        )).isEmpty());

        OwnerPopulationAdmissionPlan rehome =
                OwnerPopulationAdmissionPlanner.plan(transition(
                        OWNER_A,
                        "world-a",
                        OWNER_A,
                        "world-b",
                        5,
                        3
                )).orElseThrow();
        assertEquals(
                List.of(new OwnerPopulationAdmissionPlan.LimitIncrease(
                        OwnerPopulationScope.perWorld(OWNER_A, "world-b"),
                        1,
                        3
                )),
                rehome.increases()
        );
    }

    private OwnerPopulationTransitionRequest transition(
            OwnerId expectedOwner,
            String expectedWorld,
            OwnerId targetOwner,
            String targetWorld,
            int globalLimit,
            int perWorldLimit
    ) {
        return new OwnerPopulationTransitionRequest(
                PROFILE,
                LifecycleRevision.INITIAL,
                expectedOwner,
                expectedWorld,
                targetOwner,
                targetWorld,
                globalLimit,
                perWorldLimit,
                -100
        );
    }
}
