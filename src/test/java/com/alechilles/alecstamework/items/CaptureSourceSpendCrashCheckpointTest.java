package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure checkpoint evidence for before -> receipt -> after source-spend recovery. */
class CaptureSourceSpendCrashCheckpointTest {
    @Test
    void receiptDisambiguatesSingletonRemovalAndStackDecrement() {
        UUID attemptId = UUID.randomUUID();
        ItemStack singleton = new ItemStack("Draconic_Stone", 1);
        ItemStack singletonReceipt = SpawnerCaptureSourceReceipt.mark(singleton, attemptId);

        assertTrue(SpawnerCaptureSourceReceipt.belongsTo(singletonReceipt, attemptId));
        assertEquals(SpawnerSourceFingerprint.of(singleton),
                SpawnerSourceFingerprint.of(
                        SpawnerCaptureSourceReceipt.original(singletonReceipt)));
        assertTrue(SpawnerCaptureSourceReceipt.after(singletonReceipt).isEmpty());

        ItemStack stack = new ItemStack("Draconic_Stone", 3);
        ItemStack stackReceipt = SpawnerCaptureSourceReceipt.mark(stack, attemptId);
        assertEquals(SpawnerSourceFingerprint.afterConsumingOne(stack),
                SpawnerSourceFingerprint.of(
                        SpawnerCaptureSourceReceipt.after(stackReceipt)));
    }
}
