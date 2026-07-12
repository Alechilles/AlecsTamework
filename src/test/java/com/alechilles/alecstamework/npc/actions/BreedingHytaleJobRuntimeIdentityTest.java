package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Constructor;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Locks post-add owner-derived initialization to the owner reserved before spawn. */
class BreedingHytaleJobRuntimeIdentityTest {
    @Test
    void delayedParentOwnerChangesCannotReplaceTheReservedChildOwner() {
        UUID reservedOwnerId = UUID.randomUUID();
        PreparedBreedingPopulationBatch.ReservedChild reserved =
                new PreparedBreedingPopulationBatch.ReservedChild(
                        "child-0000",
                        "profile-child",
                        UUID.randomUUID(),
                        reservedOwnerId,
                        "Reserved Owner"
                );

        BreedingOffspringProgressionService.OwnerSnapshot actual =
                BreedingPreparedChildSpawnService.reservedOwner(reserved);

        assertEquals(reservedOwnerId, actual.ownerId());
        assertEquals("Reserved Owner", actual.ownerName());
    }

    /** Regression: prepared children need the same planned UUID in legacy and ECS identity. */
    @Test
    void reservedNpcUuidIsInstalledAsLegacyIdentityBeforeSpawn() {
        UUID plannedNpcUuid = UUID.randomUUID();
        PreparedBreedingPopulationBatch.ReservedChild reserved =
                new PreparedBreedingPopulationBatch.ReservedChild(
                        "child-0000",
                        "profile-child",
                        plannedNpcUuid,
                        UUID.randomUUID(),
                        "Reserved Owner"
                );
        NPCEntity npc = new NPCEntity();

        BreedingPreparedChildSpawnService.installReservedLegacyUuid(npc, reserved);

        assertEquals(plannedNpcUuid, npc.getUuid());
    }

    @Test
    void absentPlannedLifecycleDoesNotGainCurrentConfigFallback() throws Exception {
        PlannedChild child = new PlannedChild(
                "child-role", "adult-role", null, null, "livestock"
        );

        assertNull(BreedingPreparedChildSpawnService.lifecycleFamily(emptyConfig(), child));
    }

    @Test
    void unresolvedPersistedLifecycleDoesNotSubstituteCurrentConfig() throws Exception {
        PlannedChild child = new PlannedChild(
                "child-role", "adult-role", null, "removed-family:removed-line", "livestock"
        );

        assertNull(BreedingPreparedChildSpawnService.lifecycleFamily(emptyConfig(), child));
    }

    private TwBreedingConfig emptyConfig() throws Exception {
        Constructor<TwBreedingConfig> constructor = TwBreedingConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
