package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.components.TameworkCompanionViewsComponent;
import com.alechilles.alecstamework.ui.CompanionViewSettings;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCompanionViewStoreTest {
    @Test
    void twoFlutesShareSavedDefinitionsButKeepSelectionsAndDraftsSeparate() {
        String farmId = UUID.randomUUID().toString();
        String patrolId = UUID.randomUUID().toString();
        var farm = CompanionViewSettings.defaults().withState("All").withSearch("farm");
        var patrol = CompanionViewSettings.defaults().withState("Stored").withSearch("wolf");
        List<CommandCompanionViews.View> shared = List.of(
                new CommandCompanionViews.View(farmId, "Farm", farm),
                new CommandCompanionViews.View(patrolId, "Patrol", patrol));
        ItemStack first = CommandCompanionViewStore.choose(
                new MetadataStack(new BsonDocument("unrelated", new BsonString("kept"))), farmId, farm);
        ItemStack second = CommandCompanionViewStore.choose(new MetadataStack(null), patrolId, patrol);

        first = CommandCompanionViewStore.writeCurrent(first, farm.withSearch("unsaved"));
        second = CommandCompanionViewService.restore(second, shared);

        assertEquals("kept", first.getMetadata().getString("unrelated").getValue());
        assertEquals(farmId, CommandCompanionViewStore.read(first).selectedId());
        assertEquals("unsaved", CommandCompanionViewStore.read(first).current().search());
        assertEquals(patrolId, CommandCompanionViewStore.read(second).selectedId());
        assertEquals("wolf", CommandCompanionViewStore.read(second).current().search());
    }

    @Test
    void reopeningUsesSharedDefinitionAndDeletedSelectionFallsBackToAll() {
        String id = UUID.randomUUID().toString();
        var original = CompanionViewSettings.defaults().withState("All").withSearch("farm");
        ItemStack stack = CommandCompanionViewStore.choose(new MetadataStack(null), id, original);
        stack = CommandCompanionViewStore.writeCurrent(stack, original.withSearch("unsaved"));
        stack = new MetadataStack(BsonDocument.parse(stack.getMetadata().toJson()));

        var changed = original.withSearch("shared update");
        stack = CommandCompanionViewService.restore(stack,
                List.of(new CommandCompanionViews.View(id, "Farm", changed)));
        assertEquals(changed, CommandCompanionViewStore.read(stack).current());
        assertEquals(id, CommandCompanionViewStore.read(stack).selectedId());

        stack = CommandCompanionViewService.restore(stack, List.of());
        assertEquals(CommandCompanionViewStore.ALL_ID, CommandCompanionViewStore.read(stack).selectedId());
        assertEquals("All", CommandCompanionViewStore.read(stack).current().state());
    }

    @Test
    void playerComponentRoundTripPreservesSharedViews() {
        var settings = new CompanionViewSettings("Stored", true, "chicken", "Species", false,
                List.of("Chicken"), List.of("barn"));
        var saved = List.of(new CommandCompanionViews.View(UUID.randomUUID().toString(), "Farm", settings));
        var component = new TameworkCompanionViewsComponent();
        component.views(CommandCompanionViews.encode(saved));

        var restored = TameworkCompanionViewsComponent.CODEC.decode(
                TameworkCompanionViewsComponent.CODEC.encode(component));

        assertEquals(saved, CommandCompanionViews.decode(restored.views()));
    }

    @Test
    void malformedSharedDataCannotBeTreatedAsAnEmptyLibrary() {
        assertNull(CommandCompanionViews.decode("broken"));
        assertTrue(CommandCompanionViews.decode("").isEmpty());
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
            if (value == null) next.remove(key);
            else next.put(key, codec.encode(value));
            return new MetadataStack(next.isEmpty() ? null : next);
        }
    }
}
