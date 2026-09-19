package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.components.TameworkCompanionGroupsComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandCompanionGroupsTest {
    private final CommandGroupService groups = new CommandGroupService();
    private final CommandLinkedNpcRecordStore records = new CommandLinkedNpcRecordStore();

    // Opening an old flute must preserve its selection and merge organization across other flutes.
    @Test void mergesLegacyMembershipsWithoutChangingEitherFlutesSelection() {
        UUID npc = UUID.randomUUID();
        ItemStack first = legacy(npc, "Barn", true);
        ItemStack second = legacy(npc, "Travel", false);
        var state = CommandCompanionGroups.importLegacy(new TameworkCompanionGroupsComponent(), first, "first");
        state = CommandCompanionGroups.importLegacy(state, second, "second");
        var shared = first.withMetadata(TameworkMetadataKeys.COMMAND_GROUPS, Codec.STRING, state.definitions());
        var expected = groups.readGroups(shared).stream().map(g -> g.groupId).collect(java.util.stream.Collectors.toSet());
        assertEquals(2, expected.size());
        assertEquals(expected, Set.copyOf(state.groups(CommandCompanionGroups.entityKey(npc), null)));
        assertTrue(records.read(first).getFirst().active);
        assertFalse(records.read(second).getFirst().active);
    }

    // Deleting shared groups must remain deleted after revisiting an already migrated item.
    @Test void savedImportFencePreventsDeletedGroupResurrection() {
        UUID npc = UUID.randomUUID();
        ItemStack tool = legacy(npc, "Barn", true);
        var state = CommandCompanionGroups.importLegacy(new TameworkCompanionGroupsComponent(), tool, "tool");
        state.definitions("");
        state.retainGroups(Set.of());
        var saved = TameworkCompanionGroupsComponent.CODEC.encode(state);
        var restored = TameworkCompanionGroupsComponent.CODEC.decode(saved);
        var revisited = CommandCompanionGroups.importLegacy(restored, tool, "tool");
        assertEquals("", revisited.definitions());
        assertTrue(revisited.groups(CommandCompanionGroups.entityKey(npc), null).isEmpty());
    }

    // A profile-backed animal must retain UUID-era tags, with edits and clone writes isolated.
    @Test void savedMultiGroupMembershipMigratesToProfileWithoutMutatingPreviousState() {
        String alias = CommandCompanionGroups.entityKey(UUID.randomUUID());
        String profile = CommandCompanionGroups.profileKey(UUID.randomUUID().toString());
        var original = new TameworkCompanionGroupsComponent();
        original.groups(alias, null, List.of("barn", "travel"));
        var next = original.clone();
        next.groups(profile, alias, List.of("travel", "favorites"));
        var restored = TameworkCompanionGroupsComponent.CODEC.decode(TameworkCompanionGroupsComponent.CODEC.encode(next));
        assertEquals(List.of("travel", "favorites"), restored.groups(profile, alias));
        assertTrue(restored.groups(alias, null).isEmpty());
        assertEquals(List.of("barn", "travel"), original.groups(profile, alias));
    }

    private ItemStack legacy(UUID npc, String name, boolean active) {
        ItemStack stack = groups.createGroup(new MetadataStack(null), name, "#446688");
        String group = groups.readGroups(stack).getFirst().groupId;
        return records.write(stack, List.of(new LinkedNpcRecord(npc, null, null, null,
                "Sheep", null, "Sheep", null, active, false, group)));
    }

    private static final class MetadataStack extends ItemStack {
        MetadataStack(BsonDocument metadata) { super(); this.itemId="Tamework:CommandFlute"; this.quantity=1; this.metadata=metadata; }
        @Override public <T> ItemStack withMetadata(String key, Codec<T> codec, T value) {
            var next = metadata == null ? new BsonDocument() : metadata.clone();
            if (value == null) next.remove(key); else next.put(key, codec.encode(value));
            return new MetadataStack(next);
        }
    }
}
