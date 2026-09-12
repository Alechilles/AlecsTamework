package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.protocol.GameMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanionFollowFlockServiceTest {
    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void qualifiedFollowSubstatesRetainAuthorityButHoldWithTheSameMasterDoesNot() {
        // StateSupport emits root.substate. Rejecting Follow.Default disables all formations.
        assertTrue(allowed(OWNER, GameMode.Adventure, "Follow.Default", true, true));
        assertTrue(allowed(OWNER, GameMode.Adventure, "Follow.90", true, true));
        assertFalse(allowed(OWNER, GameMode.Adventure, "Hold.Default", true, true));
        assertFalse(allowed(OWNER, GameMode.Adventure, "FollowOther.Default", true, true));
    }

    @Test
    void staleTargetsAndLostOwnershipCannotAdmitACompanionToTheFlock() {
        assertFalse(allowed(UUID.randomUUID(), GameMode.Adventure, "Follow.Default", true, true));
        assertFalse(allowed(OWNER, GameMode.Adventure, "Follow.Default", false, true));
        assertFalse(allowed(OWNER, GameMode.Adventure, "Follow.Default", true, false));
        assertFalse(allowed(OWNER, GameMode.Creative, "Follow.Default", true, true));
    }

    private static boolean allowed(UUID owner, GameMode mode, String state, boolean tamed, boolean targetMatches) {
        return CompanionFollowFlockService.hasFollowAuthority(tamed, owner, OWNER, mode, state, targetMatches);
    }
}
