package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreedingPopulationRetryBaselineResolverTest {
    private static final String PROFILE_ID = "planned-child-profile";
    private static final UUID PLANNED_UUID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final ClaimChunkCoordinate DESTINATION =
            new ClaimChunkCoordinate("destination", 7, -4);
    private static final PreparedBreedingPopulationBatch.ReservedChild CHILD =
            new PreparedBreedingPopulationBatch.ReservedChild(
                    "child-0", PROFILE_ID, PLANNED_UUID, null, null
            );

    @Test
    void nonReplayAndEvidenceFreeReplayUseFreshBaselines() {
        BreedingPopulationRetryBaselineResolver conflicting = resolver(
                owner(OWNER_ID, CompanionLifecycleState.CAPTURED, "other", 5L),
                claim(OWNER_ID, CompanionLifecycleState.CAPTURED,
                        new ClaimChunkCoordinate("other", 1, 2), 9L),
                UUID.randomUUID()
        );
        BreedingPopulationRetryBaselineResolver empty = resolver(null, null, null);

        assertAll(
                () -> assertFalse(conflicting.resolve(CHILD, DESTINATION, false).reusable()),
                () -> assertFalse(empty.resolve(CHILD, DESTINATION, true).reusable())
        );
    }

    /** Regression: restart replay reuses revision zero instead of allocating a duplicate profile. */
    @Test
    void exactUnownedActiveBaselineIsReusable() {
        OwnerPopulationEntry owner = owner(null, CompanionLifecycleState.ACTIVE, "destination", 0L);
        ClaimOccupancyEntry claim = claim(
                null, CompanionLifecycleState.ACTIVE, DESTINATION, 0L
        );

        BreedingPopulationRetryBaselineResolver.Baseline baseline =
                resolver(owner, claim, PLANNED_UUID).resolve(CHILD, DESTINATION, true);

        assertTrue(baseline.reusable());
        assertEquals(owner, baseline.owner());
        assertEquals(claim, baseline.claim());
    }

    @Test
    void partialOrIdentityConflictingEvidenceFailsClosed() {
        OwnerPopulationEntry owner = owner(null, CompanionLifecycleState.ACTIVE, "destination", 0L);
        ClaimOccupancyEntry claim = claim(null, CompanionLifecycleState.ACTIVE, DESTINATION, 0L);

        assertAll(
                () -> assertConflict(resolver(owner, null, PLANNED_UUID)),
                () -> assertConflict(resolver(null, claim, PLANNED_UUID)),
                () -> assertConflict(resolver(owner, claim, null)),
                () -> assertConflict(resolver(owner, claim, UUID.randomUUID()))
        );
    }

    @Test
    void ownedLifecycleRevisionAndLocationConflictsFailClosed() {
        OwnerPopulationEntry validOwner =
                owner(null, CompanionLifecycleState.ACTIVE, "destination", 3L);
        ClaimOccupancyEntry validClaim =
                claim(null, CompanionLifecycleState.ACTIVE, DESTINATION, 3L);

        assertAll(
                () -> assertConflict(resolver(validOwner, validClaim, PLANNED_UUID)),
                () -> assertConflict(resolver(
                        owner(OWNER_ID, CompanionLifecycleState.ACTIVE, "destination", 3L),
                        validClaim, PLANNED_UUID
                )),
                () -> assertConflict(resolver(
                        validOwner,
                        claim(OWNER_ID, CompanionLifecycleState.ACTIVE, DESTINATION, 3L),
                        PLANNED_UUID
                )),
                () -> assertConflict(resolver(
                        owner(null, CompanionLifecycleState.CAPTURED, "destination", 3L),
                        validClaim, PLANNED_UUID
                )),
                () -> assertConflict(resolver(
                        validOwner,
                        claim(null, CompanionLifecycleState.UNLOADED, DESTINATION, 3L),
                        PLANNED_UUID
                )),
                () -> assertConflict(resolver(
                        validOwner,
                        claim(null, CompanionLifecycleState.ACTIVE, DESTINATION, 4L),
                        PLANNED_UUID
                )),
                () -> assertConflict(resolver(
                        owner(null, CompanionLifecycleState.ACTIVE, "other", 3L),
                        validClaim, PLANNED_UUID
                )),
                () -> assertConflict(resolver(
                        validOwner,
                        claim(null, CompanionLifecycleState.ACTIVE,
                                new ClaimChunkCoordinate("destination", 8, -4), 3L),
                        PLANNED_UUID
                ))
        );
    }

    private static void assertConflict(BreedingPopulationRetryBaselineResolver resolver) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(CHILD, DESTINATION, true)
        );
        assertTrue(failure.getMessage().startsWith(
                BreedingPopulationRetryBaselineResolver.CONFLICT_REASON
        ));
    }

    private static BreedingPopulationRetryBaselineResolver resolver(
            OwnerPopulationEntry owner,
            ClaimOccupancyEntry claim,
            UUID currentNpcUuid
    ) {
        OwnerPopulationIndex owners = new OwnerPopulationIndex();
        ClaimOccupancyIndex claims = new ClaimOccupancyIndex();
        CompanionIdentityResolver identities = new CompanionIdentityResolver();
        if (owner != null) {
            owners.reconcileCommittedEntry(owner);
        }
        if (claim != null) {
            claims.reconcileCommittedEntry(claim);
        }
        if (currentNpcUuid != null) {
            identities.remap(PROFILE_ID, null, currentNpcUuid);
        }
        return new BreedingPopulationRetryBaselineResolver(owners, claims, identities);
    }

    private static OwnerPopulationEntry owner(
            UUID ownerId,
            CompanionLifecycleState lifecycle,
            String worldName,
            long revision
    ) {
        return new OwnerPopulationEntry(PROFILE_ID, ownerId, worldName, lifecycle, revision);
    }

    private static ClaimOccupancyEntry claim(
            UUID ownerId,
            CompanionLifecycleState lifecycle,
            ClaimChunkCoordinate physicalChunk,
            long revision
    ) {
        return new ClaimOccupancyEntry(
                PROFILE_ID, ownerId, lifecycle, physicalChunk, revision
        );
    }
}
