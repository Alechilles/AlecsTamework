package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
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
