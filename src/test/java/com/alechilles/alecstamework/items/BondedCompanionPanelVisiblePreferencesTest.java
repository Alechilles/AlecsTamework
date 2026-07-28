package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
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
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Visible preferences for profile-first bonded command cards. */
class BondedCompanionPanelVisiblePreferencesTest {
    private static final UUID OWNER = UUID.fromString(
            "75000000-0000-0000-0000-000000000001");

    @Test
    void bondedNameSortChangesVisibleRowsWithoutChangingDurableSourceOrder()
            throws Exception {
        try (Fixture fixture = fixture()) {
            var snapshot = fixture.source.buildSnapshot(
                    fixture.player, fixture.store,
                    stack(Map.of(TameworkMetadataKeys.COMMAND_PANEL_SORT,
                            "Name")), bondedConfig(), "horn-tool");

            assertEquals(List.of("Alpha", "Zulu"), snapshot.entries().stream()
                    .map(entry -> entry.displayName()).toList());
            assertEquals(2, snapshot.featurePresentations().size());
        }
    }

    @Test
    void bondedNameFilterChangesOnlyVisibleRowsAndRetainsActionSnapshot()
            throws Exception {
        try (Fixture fixture = fixture()) {
            var snapshot = fixture.source.buildSnapshot(
                    fixture.player, fixture.store,
                    stack(Map.of(TameworkMetadataKeys.COMMAND_PANEL_FILTER_NAME,
                            "alp")), bondedConfig(), "horn-tool");

            assertEquals(List.of("Alpha"), snapshot.entries().stream()
                    .map(entry -> entry.displayName()).toList());
            assertEquals(2, snapshot.featurePresentations().size());
        }
    }

    private Fixture fixture() throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        TestEntityComponentStore store =
                new TestEntityComponentStore(entityStore);
        entityStore.store = store;
        Ref<EntityStore> playerRef = store.createReference();
        Player player = (Player) unsafe().allocateInstance(Player.class);
        player.setLegacyUUID(OWNER);
        player.loadIntoWorld(world);
        player.setReference(playerRef);
        var api = BondedPanelTestFixtures.api(List.of(
                profile("a-profile", "Zulu"),
                profile("b-profile", "Alpha")));
        BondedCompanionPanelEntrySourceService bonded =
                new BondedCompanionPanelEntrySourceService(
                        BondedPanelTestFixtures.cache(api),
                        new BondedCompanionPanelRecordSource(),
                        new BondedCompanionPanelFeaturePresentationSource(
                                () -> 10L));
        CommandPanelEntrySourceService source =
                new CommandPanelEntrySourceService(
                        null, new CommandPanelPreferenceService(), null,
                        null, null, null, bonded);
        source.warmBondedRoster(OWNER, "hydragon:dragons");
        return new Fixture(player, store, source);
    }

    private BondedCompanionProfileView profile(String id, String name) {
        return new BondedCompanionProfileView(
                id, OWNER, "hydragon:dragons", "hydragon:dragon",
                "Bonded_Nordic_Drake", name, "Nordic Drake", "Male",
                1L, BondedCompanionStateView.STORED,
                true, false, false, Map.of(), null, 0L, null);
    }

    private TwCommandItemConfig bondedConfig() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("""
                {
                  "RosterStorage":"BondedCompanions",
                  "BondedRosterId":"hydragon:dragons"
                }
                """), new ExtraInfo());
    }

    private ItemStack stack(Map<String, String> metadata) {
        BsonDocument document = new BsonDocument();
        metadata.forEach((key, value) -> document.put(key,
                new BsonString(value)));
        return new MetadataItemStack("test:horn", document);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private record Fixture(
            Player player,
            TestEntityComponentStore store,
            CommandPanelEntrySourceService source) implements AutoCloseable {
        @Override
        public void close() {
            store.close();
        }
    }

    /** Asset-store-free stack that preserves real BSON preference reads. */
    private static final class MetadataItemStack extends ItemStack {
        private MetadataItemStack(String itemId, BsonDocument metadata) {
            super();
            this.itemId = itemId;
            this.quantity = 1;
            this.metadata = metadata;
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
                    new com.hypixel.hytale.server.core.universe.world
                            .WorldConfig());
        }

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
