package com.alechilles.alecstamework.npc.network;

import com.alechilles.alecstamework.npc.movement.NativeSwimRiderComponent;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.server.core.io.handlers.IPacketHandler;
import com.hypixel.hytale.server.core.universe.Universe;

/** Copies native wish input; passive velocity must never be interpreted as a held W key. */
final class NativeSwimInputCapture {
    void capture(ClientMovement packet, IPacketHandler handler) {
        var player = handler.getPlayerRef();
        if (player == null || packet.wishMovement == null || player.getWorldUuid() == null) return;
        var world = Universe.get().getWorld(player.getWorldUuid());
        if (world == null) return;
        var playerId = player.getUuid();
        double forward = packet.wishMovement.z;
        if (!Double.isFinite(forward)) return;
        double input = Math.max(-1.0, Math.min(1.0, forward));
        long receivedAt = System.currentTimeMillis();
        // Only stable identity and primitive input cross the packet/world boundary.
        world.execute(() -> {
            var ref = world.getEntityRef(playerId);
            var type = NativeSwimRiderComponent.getComponentType();
            if (ref == null || !ref.isValid() || type == null) return;
            var store = world.getEntityStore().getStore();
            var rider = store.getComponent(ref, type);
            if (rider == null || rider.settings == null) return;
            rider.forward = input;
            rider.lastInputMs = receivedAt;
            store.putComponent(ref, type, rider);
        });
    }
}
