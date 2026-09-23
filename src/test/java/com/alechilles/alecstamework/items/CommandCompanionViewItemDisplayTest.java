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
        String id = java.util.UUID.randomUUID().toString();
        var saved = new CompanionViewSettings("InWorld", true, "wolf", "Default", false,
                List.of("Wolf"), List.of());
        stack = CommandCompanionViewStore.choose(stack, id, saved);
        List<CommandCompanionViews.View> shared = List.of(
                new CommandCompanionViews.View(id, "Combat Beast", saved));
        stack = CommandCompanionViewItemDisplay.apply(null, stack, shared);

        String name = render(stack.getDisplayName());
        String description = render(stack.getDisplayDescription());
        String baseDescription = render(stack.getItem().getDescriptionTranslationMessage());
        assertTrue(name.endsWith(" - Combat Beast"));
        assertTrue(description.startsWith("Filters:\n"));
        assertTrue(description.contains("Status: In World"));
        assertTrue(description.contains("Nearby only"));
        assertTrue(description.contains("Search: wolf"));
        assertTrue(description.indexOf("\n\n") > description.indexOf("Search: wolf"));
        assertTrue(description.endsWith(baseDescription));

        Message header = messageWithText(stack.getDisplayDescription(), "Filters:");
        assertEquals("#F6C453", header.getColor());
        assertEquals(Boolean.TRUE, header.getFormattedMessage().bold);
        assertEquals("#AFB6B0", messageWithText(stack.getDisplayDescription(), "Search: wolf").getColor());
        assertEquals("#F3E7C9", messageWithText(stack.getDisplayDescription(), "wolf").getColor());

        stack = CommandCompanionViewStore.writeCurrent(stack,
                CommandCompanionViewStore.read(stack).current().withSearch("draft"));
        stack = CommandCompanionViewItemDisplay.apply(null, stack, shared);
        assertEquals(description, render(stack.getDisplayDescription()));

        shared = List.of(new CommandCompanionViews.View(id, "Combat Beast",
                CommandCompanionViewStore.read(stack).current()));
        stack = CommandCompanionViewItemDisplay.apply(null, stack, shared);
        assertTrue(render(stack.getDisplayDescription()).contains("Search: draft"));
        assertFalse(render(stack.getDisplayDescription()).contains("Search: wolf"));

        shared = List.of(new CommandCompanionViews.View(id, "Patrol", shared.getFirst().settings()));
        stack = CommandCompanionViewItemDisplay.apply(null, stack, shared);
        assertTrue(render(stack.getDisplayName()).endsWith(" - Patrol"));

        stack = CommandCompanionViewStore.choose(stack, CommandCompanionViewStore.ALL_ID, null);
        stack = CommandCompanionViewItemDisplay.apply(null, stack, shared);
        assertNull(stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC));
    }

    @Test
    void foreignDisplayOverrideIsPreserved() {
        ItemStack stack = new MetadataStack(null).withMetadata(ItemDisplayMetadata.KEYED_CODEC,
                new ItemDisplayMetadata(Message.raw("Custom name"), Message.raw("Custom detail")));
        String id = java.util.UUID.randomUUID().toString();
        stack = CommandCompanionViewStore.choose(stack, id, CompanionViewSettings.defaults());

        ItemStack result = CommandCompanionViewItemDisplay.apply(null, stack,
                List.of(new CommandCompanionViews.View(id, "Combat Beast", CompanionViewSettings.defaults())));

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

    private static Message messageWithText(Message message, String expected) {
        if (expected.equals(render(message))) return message;
        for (Message child : message.getChildren()) {
            Message match = messageWithTextOrNull(child, expected);
            if (match != null) return match;
        }
        var messageParams = message.getFormattedMessage().messageParams;
        if (messageParams != null) {
            for (var child : messageParams.values()) {
                Message match = messageWithTextOrNull(new Message(child), expected);
                if (match != null) return match;
            }
        }
        throw new AssertionError("No message segment found for text: " + expected);
    }

    private static Message messageWithTextOrNull(Message message, String expected) {
        if (expected.equals(render(message))) return message;
        for (Message child : message.getChildren()) {
            Message match = messageWithTextOrNull(child, expected);
            if (match != null) return match;
        }
        var messageParams = message.getFormattedMessage().messageParams;
        if (messageParams != null) {
            for (var child : messageParams.values()) {
                Message match = messageWithTextOrNull(new Message(child), expected);
                if (match != null) return match;
            }
        }
        return null;
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
