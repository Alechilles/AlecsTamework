package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.BreedingChildProjectionMarker;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the one-attempt boundary once a prepared child may already be live. */
class BreedingOffspringSpawnFallbackTest {
    @Test
    void ambiguousAddOutcomeStopsFallbackBeforeAnotherSpawnAttempt() {
        assertTrue(BreedingOffspringSpawnService.terminalPreparedAttempt(
                BreedingPreparedSpawnResult.ambiguous("post-add-unknown")
        ));
    }

    @Test
    void provenAbsenceMayTryTheNextPlacementCandidate() {
        assertFalse(BreedingOffspringSpawnService.terminalPreparedAttempt(
                BreedingPreparedSpawnResult.failed("placement-rejected")
        ));
    }

    @Test
    void holderPreparationFailureAndSuccessAreTerminal() {
        assertTrue(BreedingOffspringSpawnService.terminalPreparedAttempt(
                new BreedingPreparedSpawnResult(null, "holder-rejected", true, false)
        ));
    }

    @Test
    void preparedIdentityRequiresComponentLegacyUuidAndExactMarker() {
        UUID plannedNpcUuid = UUID.randomUUID();
        TameworkProjectionIdentityComponent expected = marker(plannedNpcUuid, "child-0000");
        NPCEntity npc = npc(plannedNpcUuid);

        assertTrue(BreedingOffspringSpawnService.matchesPreparedIdentity(
                plannedNpcUuid,
                expected,
                new UUIDComponent(plannedNpcUuid),
                npc,
                expected.clone()
        ));
    }

    /** Regression: a legacy UUID mismatch remains ambiguous and cannot be committed. */
    @Test
    void legacyUuidMismatchCannotSatisfyPreparedIdentity() {
        UUID plannedNpcUuid = UUID.randomUUID();
        TameworkProjectionIdentityComponent expected = marker(plannedNpcUuid, "child-0000");

        assertFalse(BreedingOffspringSpawnService.matchesPreparedIdentity(
                plannedNpcUuid,
                expected,
                new UUIDComponent(plannedNpcUuid),
                npc(UUID.randomUUID()),
                expected.clone()
        ));
    }

    @Test
    void uuidComponentOrMarkerMismatchCannotSatisfyPreparedIdentity() {
        UUID plannedNpcUuid = UUID.randomUUID();
        TameworkProjectionIdentityComponent expected = marker(plannedNpcUuid, "child-0000");
        NPCEntity npc = npc(plannedNpcUuid);

        assertFalse(BreedingOffspringSpawnService.matchesPreparedIdentity(
                plannedNpcUuid,
                expected,
                new UUIDComponent(UUID.randomUUID()),
                npc,
                expected.clone()
        ));
        assertFalse(BreedingOffspringSpawnService.matchesPreparedIdentity(
                plannedNpcUuid,
                expected,
                new UUIDComponent(plannedNpcUuid),
                npc,
                marker(plannedNpcUuid, "child-0001")
        ));
    }

    private static NPCEntity npc(UUID uuid) {
        NPCEntity npc = new NPCEntity();
        npc.setLegacyUUID(uuid);
        return npc;
    }

    private static TameworkProjectionIdentityComponent marker(UUID uuid, String childKey) {
        return BreedingChildProjectionMarker.create(
                "breeding:attempt", childKey, "profile-child", uuid
        );
    }
}
