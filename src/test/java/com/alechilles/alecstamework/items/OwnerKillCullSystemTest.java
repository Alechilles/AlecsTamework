package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Only the owner's own killing blow on a death that still drops items can count as a cull. */
class OwnerKillCullSystemTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void theOwnersKillCounts() {
        assertTrue(OwnerKillCullSystem.countsAsOwnerKill(OWNER, OWNER, DeathConfig.ItemsLossMode.ALL));
    }

    @Test
    void anotherPlayersKillAndAKillWithNoPlayerAreUntouched() {
        assertFalse(OwnerKillCullSystem.countsAsOwnerKill(OWNER, OTHER, DeathConfig.ItemsLossMode.ALL));
        assertFalse(OwnerKillCullSystem.countsAsOwnerKill(OWNER, null, DeathConfig.ItemsLossMode.ALL));
    }

    @Test
    void aRevivableDeathThatAlreadyKeepsItsItemsIsUntouched() {
        assertFalse(OwnerKillCullSystem.countsAsOwnerKill(OWNER, OWNER, DeathConfig.ItemsLossMode.NONE));
    }
}
