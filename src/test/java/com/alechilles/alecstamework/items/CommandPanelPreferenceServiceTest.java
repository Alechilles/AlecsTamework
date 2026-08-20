package com.alechilles.alecstamework.items;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPanelPreferenceServiceTest {
    private final CommandPanelPreferenceService service = new CommandPanelPreferenceService();

    @Test
    void activeHighlightDefaultsOffAndPersistsTheEnabledChoice() {
        ItemStack stack = new MetadataItemStack("Tamework:CommandFlute", null);

        assertFalse(service.resolveActiveHighlightEnabled(stack));

        ItemStack enabled = service.setActiveHighlightEnabled(stack, true);

        assertTrue(service.resolveActiveHighlightEnabled(enabled));
    }

    /** Asset-store-free stack that keeps real BSON metadata semantics. */
    private static final class MetadataItemStack extends ItemStack {
        private MetadataItemStack(String itemId, BsonDocument metadata) {
            super();
            this.itemId = itemId;
            this.quantity = 1;
            this.metadata = metadata;
        }

        @Override
        public <T> ItemStack withMetadata(String key, Codec<T> codec, T value) {
            BsonDocument next = metadata == null ? new BsonDocument() : metadata.clone();
            if (value == null) {
                next.remove(key);
            } else {
                next.put(key, codec.encode(value));
            }
            return new MetadataItemStack(itemId, next.isEmpty() ? null : next);
        }
    }
}
