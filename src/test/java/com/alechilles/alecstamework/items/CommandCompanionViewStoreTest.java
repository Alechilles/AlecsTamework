package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.CompanionViewSettings;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCompanionViewStoreTest {
    @Test
    void savesIndependentPerFlutePresetAndKeepsUnrelatedMetadata() {
        ItemStack first = new MetadataStack(new BsonDocument("unrelated", new BsonString("kept")));
        ItemStack second = new MetadataStack(null);
        CompanionViewSettings settings = new CompanionViewSettings(
                "All", true, "farm", "Species", false, List.of("Chicken"), List.of("barn"));

        first = CommandCompanionViewStore.writeCurrent(first, settings);
        first = CommandCompanionViewStore.save(first, " Farm animals ", false);
        CommandCompanionViewStore.Snapshot firstSnapshot = CommandCompanionViewStore.read(first);

        assertEquals("kept", first.getMetadata().getString("unrelated").getValue());
        assertEquals(settings, firstSnapshot.current());
        assertEquals(1, firstSnapshot.views().size());
        assertEquals("Farm animals", firstSnapshot.views().getFirst().name());
        assertEquals("", CommandCompanionViewStore.read(second).selectedId());
        assertTrue(CommandCompanionViewStore.read(second).views().isEmpty());
    }

    @Test
    void reopeningRestoresTheChosenPresetAfterMetadataRoundTrip() {
        ItemStack stack = new MetadataStack(null);
        var original = CompanionViewSettings.defaults().withSearch("farm").withExtraFilters(false, List.of("Chicken"), List.of("barn"));
        stack = CommandCompanionViewStore.save(CommandCompanionViewStore.writeCurrent(stack, original), "Farm", false);
        String id = CommandCompanionViewStore.read(stack).selectedId();
        stack = CommandCompanionViewStore.writeCurrent(stack, original.withSearch("temporary"));
        stack = new MetadataStack(BsonDocument.parse(stack.getMetadata().toJson()));
        stack = CommandCompanionViewStore.choose(stack, CommandCompanionViewStore.read(stack).selectedId());
        assertEquals(original, CommandCompanionViewStore.read(stack).current());
        assertEquals(id, CommandCompanionViewStore.read(stack).selectedId());
    }

    @Test
    void draftEditsDoNotReplaceSavedPresetUntilExplicitUpdate() {
        ItemStack stack = new MetadataStack(null);
        stack = CommandCompanionViewStore.writeCurrent(stack,
                CompanionViewSettings.defaults().withState("All").withSearch("first"));
        stack = CommandCompanionViewStore.save(stack, "First", false);
        String id = CommandCompanionViewStore.read(stack).selectedId();

        stack = CommandCompanionViewStore.writeCurrent(stack,
                CommandCompanionViewStore.read(stack).current().withSearch("draft"));
        assertEquals("first", CommandCompanionViewStore.read(stack).views().getFirst().settings().search());

        stack = CommandCompanionViewStore.save(stack, "ignored", true);
        CommandCompanionViewStore.Snapshot updated = CommandCompanionViewStore.read(stack);
        assertEquals(id, updated.selectedId());
        assertEquals("First", updated.views().getFirst().name());
        assertEquals("draft", updated.views().getFirst().settings().search());
    }

    @Test
    void choosingBuiltinsAndDeletingCustomViewSynchronizesLegacyPreferences() {
        ItemStack stack = new MetadataStack(null);
        stack = CommandCompanionViewStore.writeCurrent(stack,
                new CompanionViewSettings("Stored", true, "goat", "Group", false, List.of(), List.of()));
        stack = CommandCompanionViewStore.save(stack, "Stored goats", false);
        String customId = CommandCompanionViewStore.read(stack).selectedId();

        stack = CommandCompanionViewStore.choose(stack, CommandCompanionViewStore.SELECTED_ID);
        assertTrue(CommandCompanionViewStore.read(stack).current().selectedOnly());
        assertEquals("All", CommandCompanionPreferences.state(stack));
        assertFalse(CommandCompanionPreferences.nearby(stack));
        assertEquals("Default", new CommandPanelPreferenceService().resolveSortValue(stack));
        assertEquals("", new CommandPanelPreferenceService().resolveNameFilter(stack));

        stack = CommandCompanionViewStore.choose(stack, customId);
        assertEquals("goat", CommandCompanionViewStore.read(stack).current().search());
        stack = CommandCompanionViewStore.deleteSelected(stack);
        assertEquals(CommandCompanionViewStore.ALL_ID, CommandCompanionViewStore.read(stack).selectedId());
        assertEquals("All", CommandCompanionViewStore.read(stack).current().state());
        assertTrue(CommandCompanionViewStore.read(stack).views().isEmpty());
    }

    @Test
    void malformedOptionalDocumentFallsBackToDefaultsAndUnknownViewDoesNothing() {
        BsonDocument malformed = new BsonDocument("Tamework.Command.CompanionViews",
                new BsonDocument("current", new BsonString("bad")));
        ItemStack stack = new MetadataStack(malformed);

        assertEquals(CompanionViewSettings.defaults(), CommandCompanionViewStore.read(stack).current());
        assertEquals(stack, CommandCompanionViewStore.choose(stack, "unknown"));
    }

    private static final class MetadataStack extends ItemStack {
        private MetadataStack(BsonDocument metadata) {
            super();
            this.itemId = "test:flute";
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
            if (value == null) {
                next.remove(key);
            } else {
                next.put(key, codec.encode(value));
            }
            return new MetadataStack(next.isEmpty() ? null : next);
        }
    }
}
