package com.alechilles.alecstamework.items;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure checkpoint evidence for before -> receipt -> after source-spend recovery. */
class CaptureSourceSpendCrashCheckpointTest {
    @Test
    void receiptDisambiguatesSingletonRemovalAndStackDecrement() {
        UUID attemptId = UUID.randomUUID();
        ItemStack singleton = stack(1);
        ItemStack singletonReceipt = SpawnerCaptureSourceReceipt.mark(singleton, attemptId);

        assertTrue(SpawnerCaptureSourceReceipt.belongsTo(singletonReceipt, attemptId));
        assertEquals(SpawnerSourceFingerprint.of(singleton),
                SpawnerSourceFingerprint.of(
                        SpawnerCaptureSourceReceipt.original(singletonReceipt)));
        assertTrue(SpawnerCaptureSourceReceipt.after(singletonReceipt).isEmpty());

        ItemStack stack = stack(3);
        ItemStack stackReceipt = SpawnerCaptureSourceReceipt.mark(stack, attemptId);
        assertEquals(SpawnerSourceFingerprint.afterConsumingOne(stack),
                SpawnerSourceFingerprint.of(
                        SpawnerCaptureSourceReceipt.after(stackReceipt)));
    }

    /** Asset-independent stack double; production ItemStack constructors require a live asset store. */
    private static ItemStack stack(int quantity) {
        return new TestItemStack("Draconic_Stone", quantity, 0.0D, 0.0D, null);
    }

    private static final class TestItemStack extends ItemStack {
        private TestItemStack(String itemId, int quantity, double durability,
                              double maxDurability, BsonDocument metadata) {
            super();
            this.itemId = itemId;
            this.quantity = quantity;
            this.durability = durability;
            this.maxDurability = maxDurability;
            this.metadata = metadata == null ? null : metadata.clone();
        }

        @Override
        public <T> ItemStack withMetadata(String key, Codec<T> codec, T value) {
            BsonDocument updated = metadata == null ? new BsonDocument() : metadata.clone();
            if (value == null) {
                updated.remove(key);
            } else {
                BsonValue encoded = codec.encode(value);
                if (encoded == null || encoded.isNull()) updated.remove(key);
                else updated.put(key, encoded);
            }
            return copy(updated.isEmpty() ? null : updated, quantity);
        }

        @Override
        public ItemStack withMetadata(BsonDocument updated) {
            return copy(updated, quantity);
        }

        @Override
        public ItemStack withQuantity(int updatedQuantity) {
            return updatedQuantity == 0 ? ItemStack.EMPTY : copy(metadata, updatedQuantity);
        }

        private ItemStack copy(BsonDocument updatedMetadata, int updatedQuantity) {
            return new TestItemStack(
                    itemId, updatedQuantity, durability, maxDurability, updatedMetadata);
        }
    }
}
