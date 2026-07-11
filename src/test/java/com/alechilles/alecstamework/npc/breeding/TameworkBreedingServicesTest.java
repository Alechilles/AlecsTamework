package com.alechilles.alecstamework.npc.breeding;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.AdmissionStatus.ACCEPTED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.AdmissionStatus.SCOPE_CLOSED;
import static com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService.BreedingMode.PASSIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Verifies that manual and passive coordinators can share one plugin-owned breeding seam. */
class TameworkBreedingServicesTest {
    @Test
    void sharedReturnsOneServiceBundle() {
        assertSame(TameworkBreedingServices.shared(), TameworkBreedingServices.shared());
        assertSame(
                TameworkBreedingServices.shared().jobRegistry(),
                TameworkBreedingServices.shared().jobRegistry()
        );
    }

    @Test
    void isolatedAdmissionServiceReadsItsBundlesRegistry() {
        TameworkBreedingServices services = new TameworkBreedingServices(() -> 0.25);
        Object scope = new Object();
        BreedingReservationScope reservationScope = new BreedingReservationScope(10.0, null, List.of());
        BreedingBirthPlan plan = services.birthPlanService().createPlan(
                1.5,
                1.0,
                index -> child(index)
        );
        BreedingJobAdmission reserved = BreedingJobAdmission.of(
                List.of(plan.children().getFirst()),
                reservationScope
        );
        BreedingBirthJob job = BreedingBirthJob.reserved(
                uuid(1L),
                "world-a",
                new BreedingParentIdentity(uuid(10L), "profile-a"),
                new BreedingParentIdentity(uuid(11L), "profile-b"),
                PASSIVE,
                plan,
                reserved,
                ParentBreedingSnapshot.empty(),
                ParentBreedingSnapshot.empty(),
                AppliedCooldownFingerprint.none(),
                AppliedCooldownFingerprint.none(),
                new BreedingBirthAnchor(0.0, 64.0, 0.0)
        );
        assertEquals(ACCEPTED, services.jobRegistry().register(scope, job).status());

        BreedingPopulationAdmissionService.AdmissionResult competing =
                services.populationAdmissionService().admit(
                        new BreedingPopulationAdmissionService.AdmissionRequest(
                                uuid(2L),
                                "world-a",
                                PASSIVE,
                                BreedingBirthPlan.of(List.of(child(9))),
                                new BreedingBirthAnchor(0.0, 64.0, 0.0),
                                reservationScope,
                                1,
                                Map.of(),
                                BreedingCapacityHeadroom.unlimited()
                        )
                );

        assertEquals(0, competing.admittedCount());
    }

    @Test
    void closeReleasesAndPermanentlyClosesIsolatedRegistry() {
        TameworkBreedingServices services = new TameworkBreedingServices();
        Object scope = new Object();

        services.close();

        assertEquals(SCOPE_CLOSED, services.jobRegistry().register(
                scope,
                BreedingBirthJob.reserved(
                        uuid(1L),
                        "world-a",
                        new BreedingParentIdentity(uuid(10L), "profile-a"),
                        new BreedingParentIdentity(uuid(11L), "profile-b")
                )
        ).status());
    }

    private static PlannedChild child(int index) {
        return new PlannedChild("baby-" + index, "adult", "Female", "family", "cattle");
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
