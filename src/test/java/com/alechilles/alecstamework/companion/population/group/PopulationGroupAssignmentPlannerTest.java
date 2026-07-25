package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure complete-classification and positive-delta admission tests. */
class PopulationGroupAssignmentPlannerTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000051");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000051");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000051");

    @Test
    void plansOnlyNewMembershipAndRoundTripsExactPolicySnapshot() {
        PopulationGroupAssignment current = new PopulationGroupAssignment(
                PROFILE,
                "Mini",
                List.of(membership("mod:existing", PopulationGroupScope.GLOBAL)),
                6,
                2,
                new LifecycleRevision(3),
                4,
                -200
        );
        PopulationGroupAssignmentRequest request = request(
                List.of(
                        policy("mod:new", PopulationGroupScope.PER_WORLD, 2),
                        policy("mod:existing", PopulationGroupScope.GLOBAL, 2)
                ),
                4L
        );

        PopulationGroupAssignmentPlan plan =
                PopulationGroupAssignmentPlanner.plan(
                        OPERATION, request, current, lifecycle("world-a")
                );

        assertEquals(
                List.of("mod:existing", "mod:new"),
                plan.target().memberships().stream()
                        .map(PopulationGroupMembership::groupId)
                        .toList()
        );
        assertEquals(5, plan.target().assignmentRevision());
        assertEquals(1, plan.reservations().size());
        assertEquals("mod:new", plan.reservations().getFirst()
                .bucket().groupId());
        assertEquals("world-a", plan.reservations().getFirst()
                .bucket().ownerWorldKey());
        String encoded =
                PopulationGroupAssignmentDefinition.INSTANCE.encode(request);
        assertEquals(
                request,
                PopulationGroupAssignmentDefinition.INSTANCE.decode(encoded)
        );
    }

    @Test
    void emptyClassificationIsExplicitAndUnownedProfilesNeedNoReservations() {
        PopulationGroupAssignmentRequest request =
                new PopulationGroupAssignmentRequest(
                        PROFILE,
                        2,
                        null,
                        new LifecycleRevision(3),
                        null,
                        null,
                        null,
                        7,
                        List.of(),
                        -100
                );
        CompanionLifecycle unowned = new CompanionLifecycle(
                PROFILE,
                null,
                LifecycleState.UNRESOLVED,
                LifecycleLocation.unresolved(),
                new LifecycleRevision(3),
                null,
                -100,
                ReconciliationGeneration.INITIAL,
                null,
                null
        );

        PopulationGroupAssignmentPlan plan =
                PopulationGroupAssignmentPlanner.plan(
                        OPERATION, request, null, unowned
                );

        assertEquals(List.of(), plan.target().memberships());
        assertEquals(1, plan.target().assignmentRevision());
        assertEquals(List.of(), plan.reservations());
    }

    @Test
    void perWorldAdmissionRequiresCanonicalOwnerWorld() {
        PopulationGroupAssignmentRequest request = request(
                List.of(policy(
                        "mod:world", PopulationGroupScope.PER_WORLD, 2
                )),
                null
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> PopulationGroupAssignmentPlanner.plan(
                        OPERATION, request, null, lifecycle(null)
                )
        );
    }

    private PopulationGroupAssignmentRequest request(
            List<PopulationGroupPolicy> policies,
            Long expectedAssignmentRevision
    ) {
        return new PopulationGroupAssignmentRequest(
                PROFILE,
                2,
                "Mini",
                new LifecycleRevision(3),
                OWNER,
                "world-a",
                expectedAssignmentRevision,
                7,
                policies,
                -100
        );
    }

    private PopulationGroupPolicy policy(
            String groupId,
            PopulationGroupScope scope,
            int limit
    ) {
        return new PopulationGroupPolicy(
                groupId, scope, limit, limit, 7
        );
    }

    private PopulationGroupMembership membership(
            String groupId,
            PopulationGroupScope scope
    ) {
        return new PopulationGroupMembership(groupId, scope);
    }

    private CompanionLifecycle lifecycle(String ownerWorldKey) {
        return new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity("entity", "world-a"),
                new LifecycleRevision(3),
                null,
                -100,
                ReconciliationGeneration.INITIAL,
                null,
                ownerWorldKey
        );
    }
}

