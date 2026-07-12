package com.alechilles.alecstamework.ownership;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for replay-stable destructive spawn-source evidence. */
class CompanionSpawnSourceFinalizationContextTest {
    @Test
    void roundTripsExactSourceIdentityAndCasEvidence() {
        UUID sourceNpc = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        String json = CompanionSpawnSourceFinalizationContext.extensionJson(
                CompanionSpawnSourceFinalizationContext.Kind.SPAWNER_ITEM,
                "spawner:" + sourceNpc,
                sourceNpc,
                player,
                4,
                "filled-fingerprint",
                "empty-fingerprint"
        );

        CompanionSpawnSourceFinalizationContext.Descriptor descriptor =
                CompanionSpawnSourceFinalizationContext.descriptor(json);

        assertTrue(CompanionSpawnSourceFinalizationContext.required(json));
        assertEquals(CompanionSpawnSourceFinalizationContext.Kind.SPAWNER_ITEM,
                descriptor.kind());
        assertEquals(sourceNpc, descriptor.sourceNpcUuid());
        assertEquals(player, descriptor.playerUuid());
        assertEquals(4, descriptor.hotbarSlot());
        assertEquals("filled-fingerprint", descriptor.expectedFingerprint());
        assertEquals("empty-fingerprint", descriptor.replacementFingerprint());
    }

    @Test
    void malformedDescriptorFailsBeforeDurablePreparation() {
        assertThrows(IllegalArgumentException.class, () ->
                CompanionSpawnSourceFinalizationContext.validateExtension(
                        "{\"spawnSourceFinalization\":{\"version\":1}}"
                ));
    }
}
