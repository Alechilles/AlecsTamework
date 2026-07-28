package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentPlan;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Third capture terminal variant codec and authority consistency tests. */
class CompanionCaptureTameAndLinkDefinitionTest {
    @Test
    void tameAndLinkRoundTripsEveryAuthorityExactly() throws Exception {
        CompanionCaptureRequest request =
                CaptureTameAndLinkTestFixtures.request();

        CompanionCaptureRequest decoded =
                CompanionCaptureDefinition.INSTANCE.decode(
                        CompanionCaptureDefinition.INSTANCE.encode(
                                request
                        )
                );

        assertEquals(request, decoded);
        assertTrue(decoded.tameAndCommandLink());
        assertEquals(
                "Tamed_Dragon_Fire",
                decoded.tameAndLinkEvidence()
                        .targetIdentity().roleId()
        );
        assertEquals(
                2,
                decoded.tameAndLinkEvidence()
                        .ownerPopulation().increases().size()
        );
    }

    @Test
    void requestRejectsOwnerThatDisagreesWithFrozenTarget() {
        CompanionCaptureRequest valid =
                CaptureTameAndLinkTestFixtures.request();

        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionCaptureRequest(
                        valid.profileId(),
                        valid.expectedLifecycleRevision(),
                        OwnerId.parse(
                                "30000000-0000-0000-0000-000000000999"
                        ),
                        valid.targetAlias(),
                        valid.targetWorldKey(),
                        valid.terminal(),
                        valid.source(),
                        valid.requestedAtMs()
                )
        );
    }

    @Test
    void terminalRejectsResolutionForDifferentLiveRole() {
        CaptureAttemptResolution valid =
                CaptureTameAndLinkTestFixtures.resolution();
        CaptureAttemptResolution wrong = new CaptureAttemptResolution(
                valid.attemptId(),
                "Dragon_Ice",
                valid.formula(),
                valid.sourceConsumption(),
                valid.successDisposition(),
                valid.outcome(),
                valid.reason(),
                valid.effectiveChance(),
                valid.guaranteed(),
                valid.missingHealthFraction(),
                valid.entropy(),
                valid.failureCooldownUntilMs()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new CaptureTerminalPlan.TameAndCommandLink(
                        wrong,
                        CaptureTameAndLinkTestFixtures.evidence()
                )
        );
    }

    @Test
    void evidenceRejectsOwnerCapacityReservedInAnotherWorld() {
        CaptureTameAndLinkEvidence valid =
                CaptureTameAndLinkTestFixtures.evidence();
        OwnerPopulationAdmissionPlan wrong =
                new OwnerPopulationAdmissionPlan(
                        valid.ownerPopulation().profileId(),
                        valid.ownerPopulation()
                                .expectedLifecycleRevision(),
                        List.of(
                                valid.ownerPopulation().increases()
                                        .getFirst(),
                                new OwnerPopulationAdmissionPlan.LimitIncrease(
                                        OwnerPopulationScope.perWorld(
                                                valid.finalLifecycle().ownerId(),
                                                "other-world"
                                        ),
                                        1,
                                        0
                                )
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> withPopulation(
                        valid, wrong, valid.populationGroups()
                )
        );
    }

    @Test
    void evidenceRejectsReservationForUnassignedGroup() {
        CaptureTameAndLinkEvidence valid =
                CaptureTameAndLinkTestFixtures.evidence();
        PopulationGroupReservation reservation =
                valid.populationGroups().targetPlan()
                        .reservations().getFirst();
        PopulationGroupReservation wrong =
                new PopulationGroupReservation(
                        reservation.operationId(),
                        reservation.profileId(),
                        reservation.expectedLifecycleRevision(),
                        new PopulationGroupBucket(
                                reservation.bucket().ownerId(),
                                "unassigned_group",
                                reservation.bucket().scope(),
                                reservation.bucket().ownerWorldKey()
                        ),
                        reservation.ownedDelta(),
                        reservation.activeDelta(),
                        reservation.snapshottedMaxOwned(),
                        reservation.snapshottedMaxActive(),
                        reservation.policyRevision(),
                        reservation.createdAtMs()
                );
        CapturePopulationGroupEvidence groups =
                new CapturePopulationGroupEvidence(
                        valid.populationGroups().expectedAssignment(),
                        new PopulationGroupAssignmentPlan(
                                valid.populationGroups()
                                        .targetPlan().target(),
                                List.of(wrong)
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> withPopulation(
                        valid, valid.ownerPopulation(), groups
                )
        );
    }

    @Test
    void evidenceRejectsChangedReconciliationGeneration() {
        CaptureTameAndLinkEvidence valid =
                CaptureTameAndLinkTestFixtures.evidence();
        CompanionLifecycle target = valid.finalLifecycle();
        CompanionLifecycle wrong = new CompanionLifecycle(
                target.profileId(),
                target.ownerId(),
                target.state(),
                target.location(),
                target.revision(),
                target.activeOperationId(),
                target.stateChangedAtMs(),
                target.lastReconciledGeneration().next(),
                target.quarantineIncidentId(),
                target.ownerWorldKey()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new CaptureTameAndLinkEvidence(
                        valid.expectedIdentity(),
                        valid.targetIdentity(),
                        valid.expectedLifecycle(),
                        wrong,
                        valid.ownerPopulation(),
                        valid.populationGroups(),
                        valid.expectedRosterRevision(),
                        valid.rosterMembership(),
                        valid.timedActivation(),
                        valid.live()
                )
        );
    }

    private CaptureTameAndLinkEvidence withPopulation(
            CaptureTameAndLinkEvidence valid,
            OwnerPopulationAdmissionPlan owner,
            CapturePopulationGroupEvidence groups
    ) {
        return new CaptureTameAndLinkEvidence(
                valid.expectedIdentity(),
                valid.targetIdentity(),
                valid.expectedLifecycle(),
                valid.finalLifecycle(),
                owner,
                groups,
                valid.expectedRosterRevision(),
                valid.rosterMembership(),
                valid.timedActivation(),
                valid.live()
        );
    }
}
