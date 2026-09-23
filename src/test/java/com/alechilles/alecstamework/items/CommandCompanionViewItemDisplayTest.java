package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.ui.CompanionViewSettings;
import com.hypixel.hytale.assetstore.TestItemAssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCompanionViewItemDisplayTest {
    private Field itemAssetStore;
    private Object previousItemAssetStore;

    @BeforeEach
    void initializeItemAssets() throws Exception {
        itemAssetStore = Item.class.getDeclaredField("ASSET_STORE");
        itemAssetStore.setAccessible(true);
        previousItemAssetStore = itemAssetStore.get(null);
        itemAssetStore.set(null, new TestItemAssetStore(new DefaultAssetMap<>(Map.of(
                "Animal_Control_Flute", new Item("Animal_Control_Flute")))));
    }

    @AfterEach
    void restoreItemAssets() throws Exception {
        itemAssetStore.set(null, previousItemAssetStore);
    }

    @Test
    void tooltipTracksSelectedSavedViewButIgnoresDraftEdits() {
        ItemStack stack = new MetadataStack(null);
        stack = CommandCompanionViewStore.writeCurrent(stack,
                new CompanionViewSettings("InWorld", true, "wolf", "Default", false,
                        List.of("Wolf"), List.of()));
        stack = CommandCompanionViewStore.save(stack, "Combat Beast", false);
        stack = CommandCompanionViewItemDisplay.apply(null, stack);

        String name = render(stack.getDisplayName());
        String description = render(stack.getDisplayDescription());
        assertTrue(name.endsWith(" - Combat Beast"));
        assertTrue(description.contains("Filters:\n"));
        assertTrue(description.contains("Status: In World"));
        assertTrue(description.contains("Nearby only"));
        assertTrue(description.contains("Search: wolf"));

        stack = CommandCompanionViewStore.writeCurrent(stack,
                CommandCompanionViewStore.read(stack).current().withSearch("draft"));
        stack = CommandCompanionViewItemDisplay.apply(null, stack);
        assertEquals(description, render(stack.getDisplayDescription()));

        stack = CommandCompanionViewStore.save(stack, "ignored", true);
        stack = CommandCompanionViewItemDisplay.apply(null, stack);
        assertTrue(render(stack.getDisplayDescription()).contains("Search: draft"));
        assertFalse(render(stack.getDisplayDescription()).contains("Search: wolf"));

        stack = CommandCompanionViewStore.rename(stack, "Patrol");
        stack = CommandCompanionViewItemDisplay.apply(null, stack);
        assertTrue(render(stack.getDisplayName()).endsWith(" - Patrol"));

        stack = CommandCompanionViewStore.choose(stack, CommandCompanionViewStore.ALL_ID);
        stack = CommandCompanionViewItemDisplay.apply(null, stack);
        assertNull(stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC));
    }

    @Test
    void foreignDisplayOverrideIsPreserved() {
        ItemStack stack = new MetadataStack(null).withMetadata(ItemDisplayMetadata.KEYED_CODEC,
                new ItemDisplayMetadata(Message.raw("Custom name"), Message.raw("Custom detail")));
        stack = CommandCompanionViewStore.save(stack, "Combat Beast", false);

        ItemStack result = CommandCompanionViewItemDisplay.apply(null, stack);

        assertSame(stack, result);
        assertEquals("Custom name", render(result.getDisplayName()));
        assertEquals("Custom detail", render(result.getDisplayDescription()));
    }

    private static String render(Message message) {
        if (message == null) return "";
        StringBuilder text = new StringBuilder();
        if (message.getRawText() != null) {
            text.append(message.getRawText());
        } else if (message.getMessageId() != null) {
            String translated = LocalizedText.resolve("en-US", message.getMessageId());
            var params = message.getFormattedMessage().params;
            if (params != null) {
                for (var entry : params.entrySet()) {
                    translated = translated.replace("{" + entry.getKey() + "}",
                            ((com.hypixel.hytale.protocol.StringParamValue) entry.getValue()).value);
                }
            }
            var messageParams = message.getFormattedMessage().messageParams;
            if (messageParams != null) {
                for (var entry : messageParams.entrySet()) {
                    translated = translated.replace("{" + entry.getKey() + "}",
                            render(new Message(entry.getValue())));
                }
            }
            text.append(translated);
        }
        for (Message child : message.getChildren()) text.append(render(child));
        return text.toString();
    }

    private static final class MetadataStack extends ItemStack {
        private MetadataStack(BsonDocument metadata) {
            super();
            this.itemId = "Animal_Control_Flute";
            this.quantity = 1;
            this.metadata = metadata;
        }

        @Override
        public ItemStack withMetadata(BsonDocument metadata) {
            return new MetadataStack(metadata);
        }

        @Override
        public <T> ItemStack withMetadata(String key, Codec<T> codec, T value) {
            BsonDocument next = metadata == null ? new BsonDocument() : metadata.clone();
            if (value == null) next.remove(key);
            else next.put(key, codec.encode(value));
            return new MetadataStack(next.isEmpty() ? null : next);
        }
    }
}
