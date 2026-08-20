package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.packets.entities.SpawnModelParticles;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelParticle;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3f;

/** Sends one finite model-attached highlight to one controlling player. */
final class CommandActiveNpcHighlightEmitter {
    static final String PARTICLE_SYSTEM_ID = "Tamework_Command_Active_Highlight";
    private static final String FALLBACK_COLOR = "#C9A653";

    private final PacketSink packetSink;

    CommandActiveNpcHighlightEmitter() {
        this(CommandActiveNpcHighlightEmitter::sendToViewer);
    }

    CommandActiveNpcHighlightEmitter(@Nonnull PacketSink packetSink) {
        this.packetSink = packetSink;
    }

    boolean emit(@Nullable NetworkId networkId,
                 @Nullable Ref<EntityStore> viewerRef,
                 @Nullable String colorHex,
                 @Nonnull CommandActiveNpcHighlightAnchor anchor,
                 @Nullable Store<EntityStore> store) {
        if (networkId == null || viewerRef == null || !viewerRef.isValid()) {
            return false;
        }
        Vector3f offset = anchor.positionOffset();
        ModelParticle modelParticle = new ModelParticle();
        modelParticle.setSystemId(PARTICLE_SYSTEM_ID);
        modelParticle.setTargetNodeName(anchor.targetNodeName());
        modelParticle.setPositionOffset(new Vector3f(offset));
        modelParticle.setDetachedFromModel(false);
        com.hypixel.hytale.protocol.ModelParticle packetParticle = modelParticle.toPacket();
        packetParticle.color = parseColor(colorHex);
        return packetSink.send(
                viewerRef,
                new SpawnModelParticles(
                        networkId.getId(),
                        new com.hypixel.hytale.protocol.ModelParticle[]{packetParticle}
                ),
                store
        );
    }

    @Nonnull
    private static Color parseColor(@Nullable String colorHex) {
        String value = colorHex != null && colorHex.matches("#[0-9A-Fa-f]{6}")
                ? colorHex
                : FALLBACK_COLOR;
        return new Color(
                (byte) Integer.parseInt(value.substring(1, 3), 16),
                (byte) Integer.parseInt(value.substring(3, 5), 16),
                (byte) Integer.parseInt(value.substring(5, 7), 16)
        );
    }

    private static boolean sendToViewer(@Nonnull Ref<EntityStore> viewerRef,
                                        @Nonnull SpawnModelParticles packet,
                                        @Nullable Store<EntityStore> store) {
        if (store == null) {
            return false;
        }
        PlayerRef playerRef = store.getComponent(viewerRef, PlayerRef.getComponentType());
        if (playerRef == null || playerRef.getPacketHandler() == null) {
            return false;
        }
        playerRef.getPacketHandler().write(packet);
        return true;
    }

    @FunctionalInterface
    interface PacketSink {
        boolean send(@Nonnull Ref<EntityStore> viewerRef,
                     @Nonnull SpawnModelParticles packet,
                     @Nullable Store<EntityStore> store);
    }
}
