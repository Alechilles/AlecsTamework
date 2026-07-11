package com.alechilles.alecstamework.npc.actions;

import java.util.List;
import java.util.Map;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for nearby-cap revalidation immediately before child spawn. */
class BreedingNearbyReservationServiceTest {
    private static final Vector3d CENTER = new Vector3d(4.0, 64.0, 8.0);

    @Test
    void naturalEntrantBetweenPrepareAndSpawnConsumesReservedCapacity() {
        BreedingNearbyReservationService service = service();
        BreedingNearbyReservationService.Reservation reservation = service.reserveEvaluated(
                "world", CENTER, 10.0, 1, List.of("wolf"), Map.of("wolf", 0)
        );

        assertEquals(1, reservation.admittedCount());
        assertFalse(service.claimEvaluated(
                reservation, 0, "world", CENTER, 10.0, "wolf", 1, 1
        ));
    }

    @Test
    void simultaneousPairsStillCountTheCompetingJobsReservationDuringRecheck() {
        BreedingNearbyReservationService service = service();
        BreedingNearbyReservationService.Reservation first = service.reserveEvaluated(
                "world", CENTER, 10.0, 2, List.of("wolf"), Map.of("wolf", 0)
        );
        BreedingNearbyReservationService.Reservation second = service.reserveEvaluated(
                "world", CENTER, 10.0, 2, List.of("wolf"), Map.of("wolf", 0)
        );

        assertEquals(1, first.admittedCount());
        assertEquals(1, second.admittedCount());
        assertFalse(service.claimEvaluated(
                first, 0, "world", CENTER, 10.0, "wolf", 2, 1
        ));
        assertTrue(service.claimEvaluated(
                second, 0, "world", CENTER, 10.0, "wolf", 2, 1
        ));
        service.releaseUnit(second, 0);
    }

    @Test
    void earlierLiveChildIsNotDoubleCountedAsTheSameJobsPendingUnit() {
        BreedingNearbyReservationService service = service();
        BreedingNearbyReservationService.Reservation reservation = service.reserveEvaluated(
                "world", CENTER, 10.0, 2, List.of("wolf", "wolf"), Map.of("wolf", 0)
        );

        assertEquals(2, reservation.admittedCount());
        assertTrue(service.claimEvaluated(
                reservation, 0, "world", CENTER, 10.0, "wolf", 2, 0
        ));
        assertTrue(service.claimEvaluated(
                reservation, 1, "world", CENTER, 10.0, "wolf", 2, 1
        ));
    }

    private static BreedingNearbyReservationService service() {
        return new BreedingNearbyReservationService(new BreedingPopulationTypeService());
    }
}
