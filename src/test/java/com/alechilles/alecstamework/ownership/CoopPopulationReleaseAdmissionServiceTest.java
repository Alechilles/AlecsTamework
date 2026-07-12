package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for canonical dormant-source validation before coop replacement spawn. */
class CoopPopulationReleaseAdmissionServiceTest {
    private static final UUID OWNER = UUID.fromString(
            "00000000-0000-0000-0000-000000000101"
    );
    private static final UUID HOUSED = UUID.fromString(
            "00000000-0000-0000-0000-000000000201"
    );

    @Test
    void acceptsExactCoopedOwnerAndClaimProjection() {
        assertNull(validate(
                HOUSED,
                CompanionLifecycleState.COOP,
                CompanionLifecycleState.COOP
        ));
    }

    @Test
    void deniesProfileAlreadyMappedToAnotherActiveRepresentation() {
        assertEquals(
                "coop-release-duplicate-active-profile",
                validate(UUID.randomUUID(), CompanionLifecycleState.COOP, CompanionLifecycleState.COOP)
        );
    }

    @Test
    void deniesWrongOwnerLifecycleEvenWhenRevisionAndOwnerMatch() {
        assertEquals(
                "coop-release-profile-not-cooped",
                validate(HOUSED, CompanionLifecycleState.ACTIVE, CompanionLifecycleState.COOP)
        );
    }

    @Test
    void deniesWrongClaimLifecycleEvenWhenOwnerProjectionIsCooped() {
        assertEquals(
                "coop-release-profile-not-cooped",
                validate(HOUSED, CompanionLifecycleState.COOP, CompanionLifecycleState.UNLOADED)
        );
    }

    private static String validate(
            UUID currentNpcUuid,
            CompanionLifecycleState ownerLifecycle,
            CompanionLifecycleState claimLifecycle
    ) {
        OwnerPopulationEntry owner = new OwnerPopulationEntry(
                "coop-profile", OWNER, "world", ownerLifecycle, 7L
        );
        ClaimOccupancyEntry claim = new ClaimOccupancyEntry(
                "coop-profile", OWNER, claimLifecycle, null, 7L
        );
        return CoopPopulationReleaseAdmissionService.validateDormantSource(
                HOUSED, currentNpcUuid, OWNER, owner, claim
        );
    }
}
