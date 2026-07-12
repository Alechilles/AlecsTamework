package com.alechilles.alecstamework.npc.breeding;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.AdmissionStatus.ACCEPTED;
import static com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry.AdmissionStatus.SCOPE_CLOSED;
import static com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService.BreedingMode.PASSIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Verifies that manual and passive coordinators can share one plugin-owned breeding seam. */
class TameworkBreedingServicesTest {
    @AfterEach
    void resetSharedServices() {
        TameworkBreedingServices.shutdownShared();
    }

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

    /** Regression: plugin shutdown used to leave the static shared registry closed after reload. */
    @Test
    void shutdownSharedClosesRetiredBundleAndInstallsFreshReloadBundle() {
        TameworkBreedingServices retired = TameworkBreedingServices.shared();
        Object retiredScope = new Object();
        BreedingBirthJob retiredJob = BreedingBirthJob.reserved(
                uuid(100L),
                "world-before-reload",
                new BreedingParentIdentity(uuid(110L), "profile-before-a"),
                new BreedingParentIdentity(uuid(111L), "profile-before-b")
        );
        assertEquals(ACCEPTED,
                retired.jobRegistry().register(retiredScope, retiredJob).status());

        TameworkBreedingServices.shutdownShared();

        TameworkBreedingServices reloaded = TameworkBreedingServices.shared();
        assertNotSame(retired, reloaded);
        assertSame(reloaded, TameworkBreedingServices.shared());
        assertEquals(SCOPE_CLOSED, retired.jobRegistry().register(
                new Object(),
                BreedingBirthJob.reserved(
                        uuid(101L),
                        "world-retired",
                        new BreedingParentIdentity(uuid(112L), "profile-retired-a"),
                        new BreedingParentIdentity(uuid(113L), "profile-retired-b")
                )
        ).status());
        assertEquals(ACCEPTED, reloaded.jobRegistry().register(
                new Object(),
                BreedingBirthJob.reserved(
                        uuid(102L),
                        "world-after-reload",
                        new BreedingParentIdentity(uuid(114L), "profile-after-a"),
                        new BreedingParentIdentity(uuid(115L), "profile-after-b")
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
