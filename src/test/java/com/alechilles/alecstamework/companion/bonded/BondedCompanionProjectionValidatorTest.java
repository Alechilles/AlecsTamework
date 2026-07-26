package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact marker, lease, UUID, and world matching contract for bonded projections. */
class BondedCompanionProjectionValidatorTest {
    private final BondedCompanionProjectionValidator validator =
            new BondedCompanionProjectionValidator();

    @Test
    void acceptsOnlyTheExactBondedProfileLeaseUuidAndWorld() {
        var lease = lease(uuid(10), "world-a");
        var exact = projection(uuid(10), "world-a", marker("profile-a", "lease-a"));

        var result = validator.validate(lease, List.of(
                projection(uuid(10), "world-a", marker("profile-a", "lease-b")),
                projection(uuid(11), "world-a", marker("profile-a", "lease-a")),
                projection(uuid(10), "world-b", marker("profile-a", "lease-a")),
                projection(uuid(10), "world-a", genericMarker()),
                exact
        ));

        assertEquals(BondedCompanionProjectionValidator.Status.DUPLICATE,
                result.status());
        assertEquals(exact, result.validProjection());
        assertEquals(List.of(uuid(11), uuid(10)), result.exactDuplicates()
                .stream().map(BondedCompanionProjectionValidator.Projection::npcUuid)
                .toList());
        assertEquals(List.of("world-a", "world-b"), result.exactDuplicates()
                .stream().map(BondedCompanionProjectionValidator.Projection::worldKey)
                .toList());
        assertFalse(result.exactDuplicates().stream().anyMatch(projection ->
                "lease-b".equals(projection.marker().getBondedLeaseToken())));
    }

    @Test
    void distinguishesMissingWrongWorldUuidMismatchAndExactDuplicates() {
        var lease = lease(uuid(10), "world-a");

        assertEquals(BondedCompanionProjectionValidator.Status.MISSING,
                validator.validate(lease, List.of()).status());
        assertEquals(BondedCompanionProjectionValidator.Status.WRONG_WORLD,
                validator.validate(lease, List.of(
                        projection(uuid(10), "world-b", marker("profile-a", "lease-a"))
                )).status());
        assertEquals(BondedCompanionProjectionValidator.Status.UUID_MISMATCH,
                validator.validate(lease, List.of(
                        projection(uuid(11), "world-a", marker("profile-a", "lease-a"))
                )).status());
        assertEquals(BondedCompanionProjectionValidator.Status.DUPLICATE,
                validator.validate(lease, List.of(
                        projection(uuid(10), "world-a", marker("profile-a", "lease-a")),
                        projection(uuid(10), "world-a", marker("profile-a", "lease-a"))
                )).status());
    }

    @Test
    void bondedMarkerCarriesNoGenericAliasFields() {
        TameworkProjectionIdentityComponent marker = marker("profile-a", "lease-a");

        assertTrue(marker.isBondedCompanion());
        assertEquals("profile-a", marker.getProfileId());
        assertEquals("lease-a", marker.getBondedLeaseToken());
        assertEquals(null, marker.getSlotKey());
        assertEquals(null, marker.getSourceNpcUuid());
        assertEquals(0L, marker.getGeneration());
    }

    private BondedCompanionProjectionValidator.LeaseExpectation lease(
            UUID npcUuid, String worldKey
    ) {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                uuid(1), "roster-a", "profile-a", "lease-a", npcUuid,
                worldKey, -2_000L, -1_000L,
                BondedCompanionProjectionValidator.LeasePhase.LIVE
        );
    }

    private BondedCompanionProjectionValidator.Projection projection(
            UUID npcUuid, String worldKey,
            TameworkProjectionIdentityComponent marker
    ) {
        return new BondedCompanionProjectionValidator.Projection(
                npcUuid, worldKey, marker, null
        );
    }

    private TameworkProjectionIdentityComponent marker(String profile, String lease) {
        return TameworkProjectionIdentityComponent.bondedCompanion(profile, lease);
    }

    private TameworkProjectionIdentityComponent genericMarker() {
        return new TameworkProjectionIdentityComponent(
                "profile-a", "lease-a",
                TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                null, null, 0L
        );
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
