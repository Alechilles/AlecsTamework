package com.alechilles.alecstamework.npc.breeding;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService.BreedingMode.MANUAL;
import static com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService.BreedingMode.PASSIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regression coverage for exact, shared breeding population admission. */
class BreedingPopulationAdmissionServiceTest {
    private final BreedingPopulationAdmissionService service = new BreedingPopulationAdmissionService();

    @Test
    void sevenLiveAtCapEightAdmitsExactlyOneFromPlannedFour() {
        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(request(
                job(1),
                PASSIVE,
                plan("cattle", "cattle", "cattle", "cattle"),
                8,
                Map.of("cattle", 7),
                List.of(),
                unlimited(),
                unlimited()
        ));

        assertEquals(1, result.admittedCount());
        assertEquals(Map.of("cattle", 1), result.reservation().countsByPopulationType());
    }

    @Test
    void fullNearbyPopulationRejectsEntireLitter() {
        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(request(
                job(1),
                MANUAL,
                plan("cattle", "cattle", "cattle", "cattle"),
                8,
                Map.of("cattle", 8),
                List.of(),
                unlimited(),
                unlimited()
        ));

        assertEquals(0, result.admittedCount());
        assertFalse(result.admittedAny());
        assertEquals(Map.of(), result.reservation().countsByPopulationType());
    }

    @Test
    void activeJobReservationsCannotCollectivelyExceedNearbyCap() {
        BreedingPopulationAdmissionService.AdmissionResult first = service.admit(request(
                job(1), PASSIVE, plan("cattle", "cattle"), 8, Map.of("cattle", 7),
                List.of(), unlimited(), unlimited()
        ));
        BreedingPopulationAdmissionService.AdmissionResult second = service.admit(request(
                job(2), MANUAL, plan("cattle", "cattle"), 8, Map.of("cattle", 7),
                List.of(first.reservation()), unlimited(), unlimited()
        ));

        assertEquals(1, first.admittedCount());
        assertEquals(0, second.admittedCount());
    }

    @Test
    void manualAndPassiveModesProduceIdenticalAdmission() {
        BreedingBirthPlan plan = plan("cattle", "cattle", "cattle");
        BreedingPopulationAdmissionService.AdmissionResult manual = service.admit(request(
                job(1), MANUAL, plan, 10, Map.of("cattle", 8), List.of(),
                OptionalInt.of(3), OptionalInt.of(3)
        ));
        BreedingPopulationAdmissionService.AdmissionResult passive = service.admit(request(
                job(1), PASSIVE, plan, 10, Map.of("cattle", 8), List.of(),
                OptionalInt.of(3), OptionalInt.of(3)
        ));

        assertEquals(manual, passive);
    }

    @Test
    void differentPopulationTypeReservationDoesNotConsumeNearbyHeadroom() {
        BreedingPopulationAdmissionService.ActiveReservation sheepReservation =
                new BreedingPopulationAdmissionService.ActiveReservation(job(9), Map.of("sheep", 4));

        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(request(
                job(1), PASSIVE, plan("cattle", "cattle"), 8, Map.of("cattle", 6),
                List.of(sheepReservation), unlimited(), unlimited()
        ));

        assertEquals(2, result.admittedCount());
        assertEquals(Map.of("cattle", 2), result.reservation().countsByPopulationType());
    }

    @Test
    void reservationScopeFlagsSeparateNearbyClaimAndPlayerAccounting() {
        BreedingPopulationAdmissionService.ActiveReservation farSameOwnerReservation =
                new BreedingPopulationAdmissionService.ActiveReservation(
                        job(9),
                        Map.of("cattle", 2)
                );

        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(
                new BreedingPopulationAdmissionService.AdmissionRequest(
                        job(1),
                        PASSIVE,
                        plan("cattle", "cattle"),
                        8,
                        Map.of("cattle", 6),
                        List.of(),
                        List.of(),
                        List.of(farSameOwnerReservation),
                        unlimited(),
                        OptionalInt.of(3)
                )
        );

        assertEquals(1, result.admittedCount());
        assertEquals(1, result.availablePlayerHeadroom());
    }

    @Test
    void claimPlayerAndNearbyConstraintsUseSmallestHeadroom() {
        BreedingBirthPlan plan = plan("cattle", "cattle", "cattle", "cattle");
        BreedingPopulationAdmissionService.AdmissionResult nearbyLimited = service.admit(request(
                job(1), PASSIVE, plan, 8, Map.of("cattle", 7), List.of(),
                OptionalInt.of(4), OptionalInt.of(4)
        ));
        BreedingPopulationAdmissionService.AdmissionResult claimLimited = service.admit(request(
                job(2), PASSIVE, plan, 10, Map.of("cattle", 6), List.of(),
                OptionalInt.of(2), OptionalInt.of(3)
        ));
        BreedingPopulationAdmissionService.AdmissionResult playerLimited = service.admit(request(
                job(1),
                PASSIVE,
                plan,
                10,
                Map.of("cattle", 7),
                List.of(),
                OptionalInt.of(2),
                OptionalInt.of(1)
        ));

        assertEquals(1, nearbyLimited.admittedCount());
        assertEquals(2, claimLimited.admittedCount());
        assertEquals(1, playerLimited.admittedCount());
        assertEquals(2, playerLimited.availableClaimHeadroom());
        assertEquals(1, playerLimited.availablePlayerHeadroom());
        assertEquals(1, playerLimited.combinedTotalHeadroom());
    }

    @Test
    void mixedPlanReservesCorrectTypesAndPreservesAdmittedOrder() {
        BreedingBirthPlan plan = plan("cattle", "sheep", "cattle", "sheep");

        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(request(
                job(1), PASSIVE, plan, 8, Map.of("cattle", 7, "sheep", 6),
                List.of(), unlimited(), unlimited()
        ));

        assertEquals(List.of(
                plan.children().get(0),
                plan.children().get(1),
                plan.children().get(3)
        ), result.admittedChildren());
        assertEquals(Map.of("cattle", 1, "sheep", 2), result.reservation().countsByPopulationType());
    }

    @Test
    void unlimitedNearbyCapStillHonorsClaimAndPlayerTotals() {
        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(request(
                job(1), PASSIVE, plan("cattle", "cattle", "cattle", "cattle"),
                0, Map.of("cattle", 1_000), List.of(), OptionalInt.of(3), OptionalInt.of(2)
        ));

        assertEquals(2, result.admittedCount());
    }

    @Test
    void spawnRecheckExcludesCurrentJobsOwnReservation() {
        UUID currentJobId = job(1);
        BreedingPopulationAdmissionService.ActiveReservation ownReservation =
                new BreedingPopulationAdmissionService.ActiveReservation(currentJobId, Map.of("cattle", 1));
        BreedingPopulationAdmissionService.AdmissionRequest request = request(
                currentJobId,
                PASSIVE,
                plan("cattle"),
                8,
                Map.of("cattle", 7),
                List.of(ownReservation),
                OptionalInt.of(1),
                OptionalInt.of(1)
        );

        assertEquals(0, service.admit(request).admittedCount());
        assertEquals(
                1,
                service.recheckAtSpawn(request, request.plan().children()).admittedCount()
        );
    }

    @Test
    void spawnRecheckStillCountsOtherJobsReservations() {
        UUID currentJobId = job(1);
        BreedingPopulationAdmissionService.ActiveReservation ownReservation =
                new BreedingPopulationAdmissionService.ActiveReservation(currentJobId, Map.of("cattle", 1));
        BreedingPopulationAdmissionService.ActiveReservation otherReservation =
                new BreedingPopulationAdmissionService.ActiveReservation(job(2), Map.of("cattle", 1));

        BreedingPopulationAdmissionService.AdmissionRequest request = request(
                currentJobId,
                PASSIVE,
                plan("cattle"),
                8,
                Map.of("cattle", 7),
                List.of(ownReservation, otherReservation),
                OptionalInt.of(2),
                OptionalInt.of(2)
        );
        BreedingPopulationAdmissionService.AdmissionResult result = service.recheckAtSpawn(
                request,
                request.plan().children()
        );

        assertEquals(0, result.admittedCount());
    }

    @Test
    void spawnRecheckCannotExpandBeyondExactInitialAdmission() {
        UUID currentJobId = job(1);
        BreedingBirthPlan plan = plan("cattle", "cattle", "cattle", "cattle");
        BreedingPopulationAdmissionService.AdmissionResult initial = service.admit(request(
                currentJobId,
                PASSIVE,
                plan,
                8,
                Map.of("cattle", 7),
                List.of(),
                unlimited(),
                unlimited()
        ));
        BreedingPopulationAdmissionService.AdmissionRequest spawnRequest = request(
                currentJobId,
                PASSIVE,
                plan,
                8,
                Map.of("cattle", 6),
                List.of(initial.reservation()),
                unlimited(),
                unlimited()
        );

        BreedingPopulationAdmissionService.AdmissionResult rechecked = service.recheckAtSpawn(
                spawnRequest,
                initial.admittedChildren()
        );

        assertEquals(1, initial.admittedCount());
        assertEquals(1, rechecked.admittedCount());
        assertEquals(initial.admittedChildren(), rechecked.admittedChildren());
    }

    private static BreedingPopulationAdmissionService.AdmissionRequest request(
            UUID jobId,
            BreedingPopulationAdmissionService.BreedingMode mode,
            BreedingBirthPlan plan,
            int maxNearby,
            Map<String, Integer> liveNearby,
            List<BreedingPopulationAdmissionService.ActiveReservation> reservations,
            OptionalInt claimHeadroom,
            OptionalInt playerHeadroom) {
        return new BreedingPopulationAdmissionService.AdmissionRequest(
                jobId,
                mode,
                plan,
                maxNearby,
                liveNearby,
                reservations,
                claimHeadroom,
                playerHeadroom
        );
    }

    private static BreedingBirthPlan plan(String... populationTypes) {
        return BreedingBirthPlan.of(java.util.Arrays.stream(populationTypes)
                .map(type -> new PlannedChild(
                        "baby_" + type,
                        "adult_" + type,
                        "Female",
                        "family_" + type,
                        type
                ))
                .toList());
    }

    private static OptionalInt unlimited() {
        return OptionalInt.empty();
    }

    private static UUID job(long value) {
        return new UUID(0L, value);
    }
}
