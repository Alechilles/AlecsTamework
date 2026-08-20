package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.entities.SpawnModelParticles;
import com.hypixel.hytale.protocol.packets.world.CancelParticleSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandActiveNpcHighlightEmitterTest {
    @Test
    void emissionTargetsOnlyTheRequestedViewerWithTheRequestedColor() {
        List<SentPacket> sent = new ArrayList<>();
        CommandActiveNpcHighlightEmitter emitter = new CommandActiveNpcHighlightEmitter(
                (viewer, packet, store) -> sent.add(new SentPacket(viewer, packet))
        );
        Ref<EntityStore> viewer = new Ref<>(null, 7);

        boolean emitted = emitter.emit(
                new NetworkId(42),
                viewer,
                "#112233",
                new CommandActiveNpcHighlightAnchor("Head", new Vector3f(0.0f, 0.25f, 0.0f)),
                null
        );

        assertTrue(emitted);
        assertEquals(1, sent.size());
        assertSame(viewer, sent.getFirst().viewer());
        SpawnModelParticles packet = (SpawnModelParticles) sent.getFirst().packet();
        assertEquals(42, packet.entityId);
        assertEquals(1, packet.modelParticles.length);
        assertEquals(CommandActiveNpcHighlightEmitter.PARTICLE_SYSTEM_ID,
                packet.modelParticles[0].systemId);
        assertEquals("Head", packet.modelParticles[0].targetNodeName);
        assertEquals(0.25f, packet.modelParticles[0].positionOffset.y());
        assertEquals(0x11, Byte.toUnsignedInt(packet.modelParticles[0].color.red));
        assertEquals(0x22, Byte.toUnsignedInt(packet.modelParticles[0].color.green));
        assertEquals(0x33, Byte.toUnsignedInt(packet.modelParticles[0].color.blue));
        assertTrue(packet.modelParticles[0].clearParticlesOnRemove);
    }

    @Test
    void cancellationTargetsOnlyTheRequestedViewerAndHighlightSystem() {
        List<SentPacket> sent = new ArrayList<>();
        CommandActiveNpcHighlightEmitter emitter = new CommandActiveNpcHighlightEmitter(
                (viewer, packet, store) -> sent.add(new SentPacket(viewer, packet))
        );
        Ref<EntityStore> viewer = new Ref<>(null, 9);

        assertTrue(emitter.cancel(viewer, null));

        assertEquals(1, sent.size());
        assertSame(viewer, sent.getFirst().viewer());
        CancelParticleSystems packet = (CancelParticleSystems) sent.getFirst().packet();
        assertEquals(List.of(CommandActiveNpcHighlightEmitter.PARTICLE_SYSTEM_ID),
                List.of(packet.particleSystemIds));
        assertTrue(packet.instant);
    }

    private record SentPacket(Ref<EntityStore> viewer, Packet packet) {
    }
}
