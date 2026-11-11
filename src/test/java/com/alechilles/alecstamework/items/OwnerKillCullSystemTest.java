package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.access.AccessLevel;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import org.junit.jupiter.api.Test;

/** Only a killing blow by a player who may cull, on a death that still drops items, can count as a cull. */
class OwnerKillCullSystemTest {
    @Test
    void aKillByTheOwnerOrAManagerCounts() {
        assertTrue(OwnerKillCullSystem.countsAsOwnerKill(AccessLevel.OWNER, DeathConfig.ItemsLossMode.ALL));
        assertTrue(OwnerKillCullSystem.countsAsOwnerKill(AccessLevel.MANAGER, DeathConfig.ItemsLossMode.ALL));
    }

    @Test
    void aKillByAPlayerWhoMayNotCullIsUntouched() {
        assertFalse(OwnerKillCullSystem.countsAsOwnerKill(AccessLevel.HANDLER, DeathConfig.ItemsLossMode.ALL));
        assertFalse(OwnerKillCullSystem.countsAsOwnerKill(AccessLevel.NONE, DeathConfig.ItemsLossMode.ALL));
    }

    @Test
    void aRevivableDeathThatAlreadyKeepsItsItemsIsUntouched() {
        assertFalse(OwnerKillCullSystem.countsAsOwnerKill(AccessLevel.OWNER, DeathConfig.ItemsLossMode.NONE));
    }
}
