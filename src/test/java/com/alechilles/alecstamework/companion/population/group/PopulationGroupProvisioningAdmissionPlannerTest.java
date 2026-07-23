package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Owned-capacity admission tests for a not-yet-created provisioned profile. */
class PopulationGroupProvisioningAdmissionPlannerTest {
    private static final ProvisioningOrigin ORIGIN =
            new ProvisioningOrigin("test:provisioning", "profile-a");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000093");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000093");

    @Test
    void reservesOwnedButNotActiveCapacityWithNoSourceRevision() {
        List<PopulationGroupReservation> reservations =
                PopulationGroupProvisioningAdmissionPlanner.plan(
                        OPERATION,
                        lifecycle(),
                        assignment(List.of(
                                membership(
                                        "mod:global",
                                        PopulationGroupScope.GLOBAL
                                ),
                                membership(
                                        "mod:world",
                                        PopulationGroupScope.PER_WORLD
                                )
                        )),
                        List.of(
                                policy(
                                        "mod:global",
                                        PopulationGroupScope.GLOBAL
                                ),
                                policy(
                                        "mod:world",
                                        PopulationGroupScope.PER_WORLD
                                )
                        )
                );

        assertEquals(2, reservations.size());
        assertEquals(1, reservations.getFirst().ownedDelta());
        assertEquals(0, reservations.getFirst().activeDelta());
        assertNull(reservations.getFirst().expectedLifecycleRevision());
        assertEquals(
                "world-a",
                reservations.get(1).bucket().ownerWorldKey()
        );
    }

    @Test
    void explicitEmptyClassificationNeedsNoReservations() {
        assertEquals(
                List.of(),
                PopulationGroupProvisioningAdmissionPlanner.plan(
                        OPERATION,
                        lifecycle(),
                        assignment(List.of()),
                        List.of()
                )
        );
    }

    @Test
    void policySnapshotMustExactlyMatchTheAssignment() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PopulationGroupProvisioningAdmissionPlanner.plan(
                        OPERATION,
                        lifecycle(),
                        assignment(List.of(membership(
                                "mod:global",
                                PopulationGroupScope.GLOBAL
                        ))),
                        List.of()
                )
        );
    }

    private CompanionLifecycle lifecycle() {
        return new CompanionLifecycle(
                ORIGIN.profileId(),
                OWNER,
                LifecycleState.PROVISIONED_DORMANT,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        ORIGIN.stableKey()
                ),
                LifecycleRevision.INITIAL,
                null,
                -2_000,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
    }

    private PopulationGroupAssignment assignment(
            List<PopulationGroupMembership> memberships
    ) {
        return new PopulationGroupAssignment(
                ORIGIN.profileId(),
                "Mini",
                memberships,
                7,
                0,
                LifecycleRevision.INITIAL,
                1,
                -2_000
        );
    }

    private PopulationGroupMembership membership(
            String groupId,
            PopulationGroupScope scope
    ) {
        return new PopulationGroupMembership(groupId, scope);
    }

    private PopulationGroupPolicy policy(
            String groupId,
            PopulationGroupScope scope
    ) {
        return new PopulationGroupPolicy(groupId, scope, 3, 1, 7);
    }
}
