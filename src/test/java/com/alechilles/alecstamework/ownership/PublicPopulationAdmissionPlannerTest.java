package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Guards public replacement admission against duplicate or ambiguous live representations. */
class PublicPopulationAdmissionPlannerTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID CURRENT = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID REPLACEMENT = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final PopulationAdmissionLocation LOCATION =
            new PopulationAdmissionLocation("alpha", 2, 3);

    @Test
    void restoreRejectsActiveAndReplacementOfUnloadedRepresentations() {
        assertEquals(
                "population-admission-duplicate-active-profile",
                validate(CompanionLifecycleState.ACTIVE, CURRENT, CURRENT)
        );
        assertEquals(
                "population-admission-duplicate-active-profile",
                validate(CompanionLifecycleState.ACTIVE, CURRENT, REPLACEMENT)
        );
        assertNull(validate(CompanionLifecycleState.UNLOADED, CURRENT, CURRENT));
        assertEquals(
                "population-admission-duplicate-active-profile",
                validate(CompanionLifecycleState.UNLOADED, CURRENT, REPLACEMENT)
        );
    }

    @Test
    void restoreAllowsOnlyAuthoritativeStoredLifecycleReplacement() {
        assertNull(validate(CompanionLifecycleState.CAPTURED, CURRENT, REPLACEMENT));
        assertNull(validate(CompanionLifecycleState.COOP, CURRENT, REPLACEMENT));
        assertNull(validate(CompanionLifecycleState.DEAD_REVIVABLE, CURRENT, REPLACEMENT));
        assertNull(validate(CompanionLifecycleState.LOST, CURRENT, REPLACEMENT));
        assertEquals(
                "population-admission-restore-source-not-authoritative",
                validate(CompanionLifecycleState.UNKNOWN_DORMANT, CURRENT, REPLACEMENT)
        );
        assertEquals(
                "population-admission-restore-source-not-authoritative",
                validate(CompanionLifecycleState.RELEASED, CURRENT, REPLACEMENT)
        );
    }

    @Test
    void nonRestoreStillRequiresExactCurrentUuid() {
        OwnerPopulationEntry owner = entry(CompanionLifecycleState.ACTIVE);
        PopulationAdmissionRequest request = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity("profile", null, "rehome"),
                REPLACEMENT,
                4L,
                OWNER,
                OWNER,
                LOCATION,
                new PopulationAdmissionLocation("alpha", 4, 5),
                PopulationAdmissionOperation.REHOME,
                1,
                PopulationAdmissionForcePolicy.ENFORCE
        );

        assertEquals(
                "population-admission-current-npc-mismatch",
                PublicPopulationAdmissionPlanner.validateCurrentRepresentation(request, CURRENT, owner)
        );
    }

    private static String validate(CompanionLifecycleState lifecycle,
                                   UUID authoritativeUuid,
                                   UUID requestedUuid) {
        PopulationAdmissionRequest request = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity("profile", null, "restore"),
                requestedUuid,
                4L,
                OWNER,
                OWNER,
                null,
                LOCATION,
                PopulationAdmissionOperation.RESTORE,
                1,
                PopulationAdmissionForcePolicy.ENFORCE
        );
        return PublicPopulationAdmissionPlanner.validateCurrentRepresentation(
                request, authoritativeUuid, entry(lifecycle)
        );
    }

    private static OwnerPopulationEntry entry(CompanionLifecycleState lifecycle) {
        return new OwnerPopulationEntry("profile", OWNER, "alpha", lifecycle, 4L);
    }
}
