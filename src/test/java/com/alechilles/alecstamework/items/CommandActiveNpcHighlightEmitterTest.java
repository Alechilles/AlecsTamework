package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.packets.entities.SpawnModelParticles;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
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

        boolean emitted = emitter.emit(new NetworkId(42), viewer, "#112233", null);

        assertTrue(emitted);
        assertEquals(1, sent.size());
        assertSame(viewer, sent.getFirst().viewer());
        SpawnModelParticles packet = sent.getFirst().packet();
        assertEquals(42, packet.entityId);
        assertEquals(1, packet.modelParticles.length);
        assertEquals(CommandActiveNpcHighlightEmitter.PARTICLE_SYSTEM_ID,
                packet.modelParticles[0].systemId);
        assertEquals(0x11, Byte.toUnsignedInt(packet.modelParticles[0].color.red));
        assertEquals(0x22, Byte.toUnsignedInt(packet.modelParticles[0].color.green));
        assertEquals(0x33, Byte.toUnsignedInt(packet.modelParticles[0].color.blue));
    }

    private record SentPacket(Ref<EntityStore> viewer, SpawnModelParticles packet) {
    }
}
