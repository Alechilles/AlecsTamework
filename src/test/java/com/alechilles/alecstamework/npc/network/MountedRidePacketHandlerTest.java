package com.alechilles.alecstamework.npc.network;

import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
