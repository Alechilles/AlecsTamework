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

/** Regression coverage for exact shared manual/passive breeding population admission. */
class BreedingPopulationAdmissionServiceTest {
    private static final String WORLD_ID = "world-a";
    private static final BreedingBirthAnchor ORIGIN = new BreedingBirthAnchor(0.0, 64.0, 0.0);

    private final BreedingBirthJobRegistry registry = new BreedingBirthJobRegistry();
    private final BreedingPopulationAdmissionService service =
            new BreedingPopulationAdmissionService(registry);

    @Test
    void sevenLiveAtCapEightAdmitsExactlyOneFromPlannedFour() {
        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(request(
                1L,
                PASSIVE,
                plan("cattle", "cattle", "cattle", "cattle"),
                ORIGIN,
                nearbyScope(10.0),
                8,
                Map.of("cattle", 7),
                BreedingCapacityHeadroom.unlimited()
        ));

        assertEquals(1, result.admittedCount());
        assertEquals(Map.of("cattle", 1), result.reservation().countsByPopulationType());
    }

    @Test
    void eightLiveAtCapEightRejectsEntireLitter() {
        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(request(
                1L,
                MANUAL,
                plan("cattle", "cattle", "cattle", "cattle"),
                ORIGIN,
                nearbyScope(10.0),
                8,
                Map.of("cattle", 8),
                BreedingCapacityHeadroom.unlimited()
        ));

        assertEquals(0, result.admittedCount());
        assertFalse(result.admittedAny());
        assertEquals(Map.of(), result.reservation().countsByPopulationType());
    }

    @Test
    void passiveReservationBlocksCompetingManualPair() {
        BreedingBirthPlan passivePlan = plan("cattle", "cattle");
        BreedingReservationScope scope = nearbyScope(10.0);
        BreedingPopulationAdmissionService.AdmissionResult passive = service.admit(request(
                1L,
                PASSIVE,
                passivePlan,
                ORIGIN,
                scope,
                8,
                Map.of("cattle", 7),
                BreedingCapacityHeadroom.unlimited()
        ));
        register(1L, PASSIVE, passivePlan, passive.admission(), ORIGIN);

        BreedingPopulationAdmissionService.AdmissionResult manual = service.admit(request(
                2L,
                MANUAL,
                plan("cattle", "cattle"),
                ORIGIN,
                scope,
                8,
                Map.of("cattle", 7),
                BreedingCapacityHeadroom.unlimited()
        ));

        assertEquals(1, passive.admittedCount());
        assertEquals(0, manual.admittedCount());
        assertEquals(PASSIVE, registry.activeReservations().getFirst().mode());
    }

    @Test
    void differentTypeAndOutOfRadiusReservationsDoNotConsumeNearbyHeadroom() {
        BreedingReservationScope scope = nearbyScope(10.0);
        BreedingBirthPlan sheepPlan = plan("sheep", "sheep");
        BreedingJobAdmission sheepAdmission = BreedingJobAdmission.of(sheepPlan.children(), scope);
        register(10L, PASSIVE, sheepPlan, sheepAdmission, ORIGIN);

        BreedingBirthAnchor farAnchor = new BreedingBirthAnchor(100.0, 64.0, 0.0);
        BreedingBirthPlan farCattlePlan = plan("cattle", "cattle");
        BreedingJobAdmission farCattleAdmission = BreedingJobAdmission.of(farCattlePlan.children(), scope);
        register(20L, MANUAL, farCattlePlan, farCattleAdmission, farAnchor);

        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(request(
                30L,
                PASSIVE,
                plan("cattle", "cattle"),
                ORIGIN,
                scope,
                8,
                Map.of("cattle", 6),
                BreedingCapacityHeadroom.unlimited()
        ));

        assertEquals(2, result.admittedCount());
        assertEquals(Map.of("cattle", 2), result.reservation().countsByPopulationType());
    }

    @Test
    void nearbyClaimAndEachPlayerScopeChooseSmallestHeadroom() {
        UUID ownerA = uuid(101L);
        UUID ownerB = uuid(102L);
        BreedingClaimCapacityScope claim = new BreedingClaimCapacityScope(
                "claims-provider",
                WORLD_ID,
                "claim-7"
        );
        BreedingPlayerCapacityScope playerA = BreedingPlayerCapacityScope.perWorld(WORLD_ID, ownerA);
        BreedingPlayerCapacityScope playerB = BreedingPlayerCapacityScope.global(ownerB);
        BreedingReservationScope scope = new BreedingReservationScope(
                10.0,
                claim,
                List.of(playerA, playerB)
        );
        BreedingCapacityHeadroom headroom = new BreedingCapacityHeadroom(
                OptionalInt.of(2),
                Map.of(playerA, 3, playerB, 1)
        );

        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(request(
                1L,
                PASSIVE,
                plan("cattle", "cattle", "cattle", "cattle"),
                ORIGIN,
                scope,
                8,
                Map.of("cattle", 4),
                headroom
        ));

        assertEquals(1, result.admittedCount());
        assertEquals(2, result.availableClaimHeadroom());
        assertEquals(1, result.availablePlayerHeadroom());
        assertEquals(1, result.combinedTotalHeadroom());
        assertEquals(Map.of(playerA, 3, playerB, 1), result.availablePlayerHeadroomByScope());
    }

    @Test
    void matchingClaimAndPlayerReservationsAreSubtractedIndependently() {
        UUID ownerA = uuid(101L);
        UUID ownerB = uuid(102L);
        BreedingClaimCapacityScope claim = new BreedingClaimCapacityScope(
                "claims-provider",
                WORLD_ID,
                "claim-7"
        );
        BreedingPlayerCapacityScope playerA = BreedingPlayerCapacityScope.perWorld(WORLD_ID, ownerA);
        BreedingPlayerCapacityScope playerB = BreedingPlayerCapacityScope.perWorld(WORLD_ID, ownerB);
        BreedingReservationScope existingScope = new BreedingReservationScope(
                10.0,
                claim,
                List.of(playerA)
        );
        BreedingBirthPlan existingPlan = plan("cattle", "cattle");
        register(
                10L,
                MANUAL,
                existingPlan,
                BreedingJobAdmission.of(existingPlan.children(), existingScope),
                ORIGIN
        );
        BreedingReservationScope requestedScope = new BreedingReservationScope(
                10.0,
                claim,
                List.of(playerA, playerB)
        );

        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(request(
                20L,
                PASSIVE,
                plan("cattle", "cattle", "cattle", "cattle"),
                ORIGIN,
                requestedScope,
                0,
                Map.of(),
                new BreedingCapacityHeadroom(
                        OptionalInt.of(4),
                        Map.of(playerA, 4, playerB, 1)
                )
        ));

        assertEquals(1, result.admittedCount());
        assertEquals(2, result.availableClaimHeadroom());
        assertEquals(Map.of(playerA, 2, playerB, 1), result.availablePlayerHeadroomByScope());
    }

    @Test
    void spawnRecheckExcludesOwnReservationAndCannotExpand() {
        BreedingBirthPlan plan = plan("cattle", "cattle", "cattle", "cattle");
        BreedingReservationScope scope = nearbyScope(10.0);
        BreedingPopulationAdmissionService.AdmissionRequest initialRequest = request(
                1L,
                PASSIVE,
                plan,
                ORIGIN,
                scope,
                8,
                Map.of("cattle", 7),
                BreedingCapacityHeadroom.unlimited()
        );
        BreedingPopulationAdmissionService.AdmissionResult initial = service.admit(initialRequest);
        register(1L, PASSIVE, plan, initial.admission(), ORIGIN);

        BreedingPopulationAdmissionService.AdmissionResult rechecked = service.recheckAtSpawn(
                request(
                        1L,
                        PASSIVE,
                        plan,
                        ORIGIN,
                        scope,
                        8,
                        Map.of("cattle", 5),
                        BreedingCapacityHeadroom.unlimited()
                ),
                initial.admission()
        );

        assertEquals(1, initial.admittedCount());
        assertEquals(initial.admittedChildren(), rechecked.admittedChildren());
        assertEquals(1, rechecked.admittedCount());
    }

    @Test
    void naturalZeroPlanProducesEmptyExactAdmission() {
        BreedingBirthPlan naturalZero = new BreedingBirthPlanService(() -> 0.9).createPlan(
                0.4,
                1.0,
                index -> child("cattle", index)
        );
        BreedingReservationScope scope = nearbyScope(10.0);

        BreedingPopulationAdmissionService.AdmissionResult result = service.admit(request(
                1L,
                PASSIVE,
                naturalZero,
                ORIGIN,
                scope,
                8,
                Map.of(),
                BreedingCapacityHeadroom.unlimited()
        ));

        assertEquals(0, naturalZero.rolledChildCount());
        assertEquals(List.of(), result.admittedChildren());
        assertEquals(scope, result.reservation().scope());
    }

    @Test
    void replayCandidateSubsetDoesNotReserveAlreadyCommittedIdenticalChild() {
        PlannedChild identical = child("cattle", 0);
        BreedingBirthPlan fullPlan = BreedingBirthPlan.of(List.of(identical, identical));
        BreedingPopulationAdmissionService.AdmissionRequest request = request(
                44L,
                PASSIVE,
                fullPlan,
                ORIGIN,
                nearbyScope(10.0),
                8,
                Map.of("cattle", 7),
                BreedingCapacityHeadroom.unlimited()
        );

        BreedingPopulationAdmissionService.AdmissionResult result =
                service.admit(request, List.of(identical));

        assertEquals(1, result.admittedCount());
        assertEquals(List.of(identical), result.admittedChildren());
        assertEquals(Map.of("cattle", 1), result.reservation().countsByPopulationType());
    }

    private BreedingBirthJobRegistry.AdmissionResult register(
            long jobId,
            BreedingPopulationAdmissionService.BreedingMode mode,
            BreedingBirthPlan plan,
            BreedingJobAdmission admission,
            BreedingBirthAnchor anchor) {
        BreedingParentIdentity parentA = parent(jobId * 2L, "profile-" + jobId + "-a");
        BreedingParentIdentity parentB = parent(jobId * 2L + 1L, "profile-" + jobId + "-b");
        BreedingBirthJob job = BreedingBirthJob.reserved(
                uuid(jobId),
                WORLD_ID,
                parentA,
                parentB,
                mode,
                plan,
                admission,
                ParentBreedingSnapshot.empty(),
                ParentBreedingSnapshot.empty(),
                AppliedCooldownFingerprint.none(),
                AppliedCooldownFingerprint.none(),
                anchor
        );
        return registry.register(this, job);
    }

    private static BreedingPopulationAdmissionService.AdmissionRequest request(
            long jobId,
            BreedingPopulationAdmissionService.BreedingMode mode,
            BreedingBirthPlan plan,
            BreedingBirthAnchor anchor,
            BreedingReservationScope scope,
            int maxNearby,
            Map<String, Integer> liveNearby,
            BreedingCapacityHeadroom capacityHeadroom) {
        return new BreedingPopulationAdmissionService.AdmissionRequest(
                uuid(jobId),
                WORLD_ID,
                mode,
                plan,
                anchor,
                scope,
                maxNearby,
                liveNearby,
                capacityHeadroom
        );
    }

    private static BreedingReservationScope nearbyScope(double radius) {
        return new BreedingReservationScope(radius, null, List.of());
    }

    private static BreedingBirthPlan plan(String... populationTypes) {
        return BreedingBirthPlan.of(java.util.stream.IntStream.range(0, populationTypes.length)
                .mapToObj(index -> child(populationTypes[index], index))
                .toList());
    }

    private static PlannedChild child(String populationType, int index) {
        return new PlannedChild(
                "baby_" + populationType + "_" + index,
                "adult_" + populationType,
                "Female",
                "family_" + populationType,
                populationType
        );
    }

    private static BreedingParentIdentity parent(long id, String profileId) {
        return new BreedingParentIdentity(uuid(id), profileId);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
