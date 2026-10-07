package com.alechilles.alecstamework.npc.network;

import com.alechilles.alecstamework.npc.movement.NativeSwimRiderComponent;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.server.core.io.handlers.IPacketHandler;
import com.hypixel.hytale.server.core.universe.Universe;

/** Copies native wish input; passive velocity must never be interpreted as a held W key. */
final class NativeSwimInputCapture {
    void capture(ClientMovement packet, IPacketHandler handler) {
        var player = handler.getPlayerRef();
        if (player == null || player.getWorldUuid() == null) return;
        var world = Universe.get().getWorld(player.getWorldUuid());
        if (world == null) return;
        var playerId = player.getUuid();
        Double forward = packet.wishMovement == null ? null : packet.wishMovement.z;
        Double vertical = packet.wishMovement == null ? null : packet.wishMovement.y;
        var states = packet.riderMovementStates != null ? packet.riderMovementStates : packet.movementStates;
        Boolean crouching = states == null ? null : states.crouching || states.forcedCrouching;
        Boolean jumping = states == null ? null : states.jumping || states.swimJumping;
        long receivedAt = System.currentTimeMillis();
        // Only stable identity and primitive input cross the packet/world boundary.
        try {
            world.execute(() -> {
                var ref = world.getEntityRef(playerId);
                var type = NativeSwimRiderComponent.getComponentType();
                if (ref == null || !ref.isValid() || type == null) return;
                var store = world.getEntityStore().getStore();
                var rider = store.getComponent(ref, type);
                if (rider == null || rider.settings == null) return;
                rider.captureInput(forward, vertical, crouching, jumping, receivedAt);
                store.putComponent(ref, type, rider);
            });
        } catch (RuntimeException ignored) {
            // The world is stopping and takes no more tasks; this input sample is dropped.
        }
    }
}
