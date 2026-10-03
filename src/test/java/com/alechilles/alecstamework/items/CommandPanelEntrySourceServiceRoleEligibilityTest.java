package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcPanelPageState;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Role policy must use the canonical role cached for an owned companion. */
class CommandPanelEntrySourceServiceRoleEligibilityTest {
    private static final UUID OWNER = UUID.fromString(
            "76000000-0000-0000-0000-000000000001");
    private static final UUID ALLOWED = UUID.fromString(
            "76000000-0000-0000-0000-000000000002");
    private static final UUID UNSUPPORTED = UUID.fromString(
            "76000000-0000-0000-0000-000000000003");

    @Test
    void ownedEligibilityUsesCachedRoleInsteadOfSpeciesPresentation() throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Ref<EntityStore> playerRef = store.createReference();
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(OWNER);
            player.loadIntoWorld(world);
            player.setReference(null);

            ItemStack stack = new CommandLinkedNpcRecordStore().write(
                    new MetadataStack("test:flute", new BsonDocument()),
                    List.of(
                            record(ALLOWED, "Cow"),
                            record(UNSUPPORTED, "Chicken")));
            TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(
                    BsonDocument.parse("""
                            {"AllowedRoles":{"Mode":"Allowlist","Allowlist":["Cow"]}}
                            """), new ExtraInfo());
            UUID storedId = UUID.randomUUID();
            var storedProfile = captured(null, storedId, "Cow");
            CompanionQueries companions = queries(List.of(live(OWNER, ALLOWED, "Cow"),
                    live(OWNER, UNSUPPORTED, "Chicken"), storedProfile));
            // A capture whose owner was cleared is known to this viewer through the item's link.
            stack = new CommandLinkedNpcRecordStore().write(stack, List.of(
                    record(ALLOWED, "Cow"), record(UNSUPPORTED, "Chicken"),
                    new LinkedNpcRecord(storedId, storedProfile.profileId().toString(), null, null, null,
                            "Companion", null, "Cow", null, true, false, null)));
            var persistence = new CommandPersistenceView(companions);
            var names = new CommandNpcNameResolver();
            var policy = new CommandLinkPolicyService();
            var linked = new CommandLinkedPanelEntryService(new CommandLinkedNpcRecordStore(),
                    null, names, null, persistence, policy, new CommandGroupService(), null);
            CommandPanelEntrySourceService source = new CommandPanelEntrySourceService(linked,
                    new CommandPanelPreferenceService(), policy, names, null, null, null,
                    new CommandOwnedPanelRecordSource(companions));
            var snapshot = source.buildSnapshot(player, store, stack, config, "flute");
            List<LinkedNpcEntry> entries = snapshot.entries();
            var captured = entries.stream().filter(row -> storedId.equals(row.npcUuid())).findFirst().orElseThrow();
            assertTrue(captured.captured(), "A capture whose owner was cleared must reach the Stored filter.");
            assertFalse(captured.selectionSupported());
            assertTrue(captured.active(), "The captured card retains its flute selection while command control is unavailable.");
            org.junit.jupiter.api.Assertions.assertEquals(
                    CommandCompanionGroups.profileKey(storedProfile.profileId().toString()), captured.companionKey(),
                    "Captured group edits must use the same stable profile key after release.");
            assertTrue(snapshot.featurePresentations().get(storedId).managesRosterRow(),
                    "The informational captured card must suppress generic mutation actions.");

            assertTrue(entries.stream().filter(row -> ALLOWED.equals(row.npcUuid()))
                    .findFirst().orElseThrow().selectionSupported());
            assertFalse(entries.stream().filter(row -> UNSUPPORTED.equals(row.npcUuid()))
                    .findFirst().orElseThrow().selectionSupported());

            // A text filter must not change the group-selection summary of the full roster.
            var filtered = source.buildSnapshot(player, store,
                    new CommandPanelPreferenceService().setSpeciesFilter(stack, "Chicken"), config, "flute");
            org.junit.jupiter.api.Assertions.assertEquals(List.of(UNSUPPORTED),
                    filtered.entries().stream().map(LinkedNpcEntry::npcUuid).toList());
            org.junit.jupiter.api.Assertions.assertEquals(
                    CommandGroupAssignPageService.resolveGroupActivationValue(snapshot.selectionEntries(), List.of()),
                    CommandGroupAssignPageService.resolveGroupActivationValue(filtered.selectionEntries(), List.of()));
            assertTrue(filtered.selectionEntries().stream().anyMatch(row -> ALLOWED.equals(row.npcUuid())));
        }
    }

    @Test
    void ownedPaginationLimitsDetailedCardsWhileFiltersKeepTheFullSelectionRoster() throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(OWNER);
            player.loadIntoWorld(world);
            List<LinkedNpcRecord> records = new java.util.ArrayList<>();
            List<CompanionRecord> profiles = new java.util.ArrayList<>();
            for (int index = 0; index < 120; index++) {
                UUID id = new UUID(0L, index + 10L);
                String role = index % 2 == 0 ? "Cow" : "Chicken";
                records.add(record(id, role));
                profiles.add(index < 60 ? live(OWNER, id, role) : captured(OWNER, id, role));
            }
            ItemStack stack = new CommandLinkedNpcRecordStore().write(
                    new MetadataStack("test:flute", new BsonDocument()), records);
            stack = CommandCompanionPreferences.state(stack, "All");
            CompanionQueries companions = queries(profiles);
            var persistence = new CommandPersistenceView(companions);
            var names = new CommandNpcNameResolver();
            var policy = new CommandLinkPolicyService();
            var linked = new CommandLinkedPanelEntryService(new CommandLinkedNpcRecordStore(),
                    null, names, null, persistence, policy, new CommandGroupService(), null);
            var source = new CommandPanelEntrySourceService(linked, new CommandPanelPreferenceService(),
                    policy, names, null, null, null, new CommandOwnedPanelRecordSource(companions));
            TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(new BsonDocument(), new ExtraInfo());
            LinkedNpcPanelPageState page = new LinkedNpcPanelPageState();
            page.setPageSize(50);

            var first = source.buildSnapshot(player, store, stack, config, "flute", page);
            org.junit.jupiter.api.Assertions.assertEquals(50, first.entries().size(),
                    "Only one page may reach detailed card assembly.");
            org.junit.jupiter.api.Assertions.assertEquals(120, first.selectionEntries().size(),
                    "Group selection still describes every owned companion.");
            assertTrue(page.move(1));
            var second = source.buildSnapshot(player, store, stack, config, "flute", page);
            org.junit.jupiter.api.Assertions.assertEquals(50, second.entries().size());

            ItemStack filteredStack = new CommandPanelPreferenceService().setSpeciesFilter(stack, "Chicken");
            var filtered = source.buildSnapshot(player, store, filteredStack, config, "flute", page);
            org.junit.jupiter.api.Assertions.assertEquals(10, filtered.entries().size(),
                    "The clamped second page contains only the remaining filtered cards.");
            org.junit.jupiter.api.Assertions.assertEquals(120, filtered.selectionEntries().size(),
                    "Filtering cards must not shrink group-selection inputs.");

            ItemStack storedStack = CommandCompanionPreferences.state(stack, "Stored");
            var stored = source.buildSnapshot(player, store, storedStack, config, "flute", page);
            org.junit.jupiter.api.Assertions.assertEquals(10, stored.entries().size(),
                    "The stored-state filter must apply before the clamped page window.");
            org.junit.jupiter.api.Assertions.assertEquals(120, stored.selectionEntries().size());
            ItemStack viewStack = CommandCompanionViewStore.writeCurrent(stack,
                    new com.alechilles.alecstamework.ui.CompanionViewSettings(
                            "Stored", false, "", "Species", false, List.of("chicken"), List.of()));
            page.setPageSize(10);
            var viewPage = source.buildSnapshot(player, store, viewStack, config, "flute", page);
            org.junit.jupiter.api.Assertions.assertEquals(30, page.totalEntries());
            org.junit.jupiter.api.Assertions.assertEquals(10, viewPage.entries().size());
            org.junit.jupiter.api.Assertions.assertTrue(viewPage.entries().stream()
                    .allMatch(entry -> entry.captured() && "chicken".equals(entry.speciesId())));
            org.junit.jupiter.api.Assertions.assertEquals(120, page.rosterEntries().size(),
                    "View options and selection counts must still include animals outside the saved view.");
            assertTrue(page.move(1));
            assertTrue(page.move(1));
            var lastViewPage = source.buildSnapshot(player, store, viewStack, config, "flute", page);
            org.junit.jupiter.api.Assertions.assertEquals(10, lastViewPage.entries().size());
            org.junit.jupiter.api.Assertions.assertFalse(page.move(1));
        }
    }

    private static LinkedNpcRecord record(UUID id, String roleId) {
        return new LinkedNpcRecord(id, null, null, "Companion", null, roleId);
    }

    private static CompanionRecord live(UUID owner, UUID npc, String role) {
        return CompanionTransitions.newLive(UUID.randomUUID(), 0, body(owner, npc, role));
    }

    private static CompanionRecord captured(UUID owner, UUID npc, String role) {
        return CompanionTransitions.newItem(UUID.randomUUID(), body(owner, npc, role), owner, null);
    }

    private static CompanionTransitions.BodyFacts body(UUID owner, UUID npc, String role) {
        return new CompanionTransitions.BodyFacts(npc, owner, null, role, role, "default", 0, 0, 0, List.of(),
                CompanionSummary.EMPTY);
    }

    private static CompanionQueries queries(List<CompanionRecord> records) {
        CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (before, after) -> { });
        for (CompanionRecord record : records) {
            index.insert(record);
        }
        return new CompanionQueries(index, new LoadedBodies<>());
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class MetadataStack extends ItemStack {
        private MetadataStack(String itemId, BsonDocument metadata) {
            super();
            this.itemId = itemId;
            this.quantity = 1;
            this.metadata = metadata;
        }

        @Override
        public ItemStack withMetadata(BsonDocument metadata) {
            return new MetadataStack(itemId, metadata);
        }

        @Override
        public <T> ItemStack withMetadata(String key,
                                          com.hypixel.hytale.codec.Codec<T> codec,
                                          T value) {
            BsonDocument next = metadata == null ? new BsonDocument() : metadata.clone();
            if (value == null) {
                next.remove(key);
            } else {
                next.put(key, codec.encode(value));
            }
            return new MetadataStack(itemId, next);
        }
    }

    private static final class TestEntityStore extends EntityStore {
        private TestEntityComponentStore store;

        private TestEntityStore(World world) {
            super(world);
            ((TestWorld) world).entityStore = this;
        }

        @Override
        public TestEntityComponentStore getStore() {
            return store;
        }
    }

    private static final class TestWorld extends World {
        private EntityStore entityStore;

        private TestWorld() throws java.io.IOException {
            super("unused", Path.of("."),
                    new com.hypixel.hytale.server.core.universe.world.WorldConfig());
        }

        @Override
        public Ref<EntityStore> getEntityRef(UUID id) { return null; }

        @Override
        public String getName() {
            return "world-a";
        }

        @Override
        public EntityStore getEntityStore() {
            return entityStore;
        }
    }
}
