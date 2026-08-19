package com.alechilles.alecstamework.npc.network;

import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountedRidePacketHandlerTest {
    /** Protects F dismount without treating primary/secondary item actions as dismount input. */
    @Test
    void detectsOnlyInitialUseInteractionAsDismountInput() {
        SyncInteractionChain use = update(true, InteractionType.Use);
        SyncInteractionChain primary = update(true, InteractionType.Primary);
        SyncInteractionChain continuedUse = update(false, InteractionType.Use);

        assertTrue(MountedRidePacketHandler.containsInitialUse(packet(use)));
        assertTrue(MountedRidePacketHandler.containsInitialUse(packet(primary, use)));
        assertFalse(MountedRidePacketHandler.containsInitialUse(packet(primary)));
        assertFalse(MountedRidePacketHandler.containsInitialUse(packet(continuedUse)));
        assertFalse(MountedRidePacketHandler.containsInitialUse(packet()));
    }

    @Test
    void staleSessionCleanupLeavesReplacementAliasesIntact() {
        UUID playerUuid = UUID.randomUUID();
        UUID riderUuid = UUID.randomUUID();
        UUID firstMountUuid = UUID.randomUUID();
        UUID replacementMountUuid = UUID.randomUUID();

        MountedRidePacketHandler.registerRide(playerUuid, riderUuid, firstMountUuid, null);
        MountedRidePacketHandler.RideSession oldSession =
                MountedRidePacketHandler.currentRideSession(playerUuid);
        MountedRidePacketHandler.registerRide(playerUuid, riderUuid, replacementMountUuid, null);
        MountedRidePacketHandler.RideSession replacementSession =
                MountedRidePacketHandler.currentRideSession(playerUuid);
        try {
            assertNotSame(oldSession, replacementSession);
            assertFalse(oldSession.mailbox().offerMouseInteraction(
                    new MountedRideInputMailbox.MouseInteractionSnapshot(true, true, 1, 1)
            ));
            assertFalse(MountedRidePacketHandler.unregisterRide(oldSession));
            assertSame(replacementSession, MountedRidePacketHandler.currentRideSession(playerUuid));
            assertSame(replacementSession, MountedRidePacketHandler.currentRideSession(riderUuid));
        } finally {
            if (replacementSession != null) {
                MountedRidePacketHandler.unregisterRide(replacementSession);
            }
        }
    }

    @Test
    void currentSessionCleanupRemovesEveryAlias() {
        UUID playerUuid = UUID.randomUUID();
        UUID riderUuid = UUID.randomUUID();
        UUID mountUuid = UUID.randomUUID();

        MountedRidePacketHandler.registerRide(playerUuid, riderUuid, mountUuid, null);
        MountedRidePacketHandler.RideSession session =
                MountedRidePacketHandler.currentRideSession(playerUuid);
        try {
            assertTrue(MountedRidePacketHandler.unregisterRide(session));
            assertNull(MountedRidePacketHandler.currentRideSession(playerUuid));
            assertNull(MountedRidePacketHandler.currentRideSession(riderUuid));
        } finally {
            MountedRidePacketHandler.unregisterRide(playerUuid);
        }
    }

    private static SyncInteractionChain update(boolean initial, InteractionType type) {
        SyncInteractionChain update = new SyncInteractionChain();
        update.initial = initial;
        update.interactionType = type;
        return update;
    }

    private static SyncInteractionChains packet(SyncInteractionChain... updates) {
        return new SyncInteractionChains(updates);
    }
}
