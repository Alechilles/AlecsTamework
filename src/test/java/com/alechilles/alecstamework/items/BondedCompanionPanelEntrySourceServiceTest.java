package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.api.BondedCompanionLeaseView;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonDocument;
import com.hypixel.hytale.codec.ExtraInfo;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Covers live panel progression boundaries that require ECS component state. */
class BondedCompanionPanelEntrySourceServiceTest {
    private static final UUID LIVE = UUID.fromString(
            "71000000-0000-0000-0000-000000000099");

    @Test
    void missingLiveLevelingComponentRetainsTheDurableProgression() throws Exception {
        Tamework previous = tameworkInstance();
        try (TestEntityComponentStore store = new TestEntityComponentStore(
                new EntityStore(null))) {
            Ref<EntityStore> npcRef = store.createReference();
            installLevelingType();

            var profile = profile(BondedCompanionStateView.ACTIVE, "world-a");
            var projection = BondedCompanionPanelEntrySourceService.liveProgression(
                    npcRef, store, profile.roleId());
            var updated = BondedCompanionPanelLiveProfileOverlay.withProgression(
                    profile, projection);

            assertEquals("10", updated.snapshotPresentationData().get("level"));
            assertEquals("50.0", updated.snapshotPresentationData().get("currentXp"));
        } finally {
            setTameworkInstance(previous);
        }
    }

    @Test
    void missingTalentComponentAppliesLiveLevelingButPreservesDurableTalents()
            throws Exception {
        try (ProgressionFixture fixture = new ProgressionFixture(false)) {
            fixture.store.put(fixture.npcRef, fixture.levelingType,
                    new TameworkLevelingComponent("live-leveling", 12, 43.5, 143.5));
            var profile = profileWithTalents();

            var updated = BondedCompanionPanelLiveProfileOverlay.withProgression(
                    profile, BondedCompanionPanelEntrySourceService.liveProgression(
                            fixture.npcRef, fixture.store, profile.roleId()));

            assertEquals("2", updated.snapshotPresentationData().get("level"));
            assertEquals("43.5", updated.snapshotPresentationData().get("currentXp"));
            assertEquals("saved-talents", updated.snapshotPresentationData().get(
                    "talentConfigId"));
            assertEquals("5", updated.snapshotPresentationData().get(
                    "talentSpentPoints"));
        }
    }

    @Test
    void unresolvedTalentConfigAppliesLiveLevelingButPreservesDurableTalents()
            throws Exception {
        try (ProgressionFixture fixture = new ProgressionFixture(true)) {
            fixture.store.put(fixture.npcRef, fixture.levelingType,
                    new TameworkLevelingComponent("live-leveling", 12, 43.5, 143.5));
            fixture.store.put(fixture.npcRef, fixture.talentsType,
                    new TameworkTalentsComponent("missing-talents", 9, new String[0]));
            var profile = profileWithTalents();

            var updated = BondedCompanionPanelLiveProfileOverlay.withProgression(
                    profile, BondedCompanionPanelEntrySourceService.liveProgression(
                            fixture.npcRef, fixture.store, profile.roleId()));

            assertEquals("2", updated.snapshotPresentationData().get("level"));
            assertEquals("43.5", updated.snapshotPresentationData().get("currentXp"));
            assertEquals("saved-talents", updated.snapshotPresentationData().get(
                    "talentConfigId"));
            assertEquals("5", updated.snapshotPresentationData().get(
                    "talentSpentPoints"));
        }
    }

    @Test
    void exactActiveReferenceRejectsInactiveWrongWorldAndForeignStoreEntities()
            throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entities = new TestEntityStore(world);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entities);
             TestEntityComponentStore foreign = new TestEntityComponentStore(entities)) {
            entities.store = store;
            Ref<EntityStore> live = store.createReference();
            Ref<EntityStore> foreignRef = foreign.createReference();
            world.ref = live;
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.loadIntoWorld(world);

            assertSame(live, exactActiveReference(player, store,
                    profile(BondedCompanionStateView.ACTIVE, "world-a")));
            assertNull(exactActiveReference(player, store,
                    profile(BondedCompanionStateView.STORED, "world-a")));
            assertNull(exactActiveReference(player, store,
                    profile(BondedCompanionStateView.DEAD, "world-a")));
            assertNull(exactActiveReference(player, store,
                    profile(BondedCompanionStateView.ACTIVE, "world-b")));
            world.ref = foreignRef;
            assertNull(exactActiveReference(player, store,
                    profile(BondedCompanionStateView.ACTIVE, "world-a")));
        }
    }

    private static Ref<EntityStore> exactActiveReference(
            Player player, Store<EntityStore> store,
            com.alechilles.alecstamework.api.BondedCompanionProfileView profile
    ) throws Exception {
        Method method = BondedCompanionPanelEntrySourceService.class
                .getDeclaredMethod("exactActiveReference", Player.class,
                        Store.class,
                        com.alechilles.alecstamework.api.BondedCompanionProfileView.class);
        method.setAccessible(true);
        return (Ref<EntityStore>) method.invoke(null, player, store, profile);
    }

    private static com.alechilles.alecstamework.api.BondedCompanionProfileView profile(
            BondedCompanionStateView state, String worldKey) {
        return new com.alechilles.alecstamework.api.BondedCompanionProfileView(
                "profile", BondedPanelTestFixtures.OWNER, "roster", "family",
                "role", null, null, null, 1L, state, false, true, false,
                Map.of("level", "10", "currentXp", "50.0"),
                state == BondedCompanionStateView.ACTIVE
                        ? new BondedCompanionLeaseView("lease", LIVE, worldKey,
                        0L, 0L) : null,
                0L, null);
    }

    private static com.alechilles.alecstamework.api.BondedCompanionProfileView
    profileWithTalents() {
        return new com.alechilles.alecstamework.api.BondedCompanionProfileView(
                "profile", BondedPanelTestFixtures.OWNER, "roster", "family",
                "role", null, null, null, 1L, BondedCompanionStateView.ACTIVE,
                false, true, false, Map.of("level", "10", "currentXp", "50.0",
                        "talentConfigId", "saved-talents", "talentSpentPoints", "5"),
                new BondedCompanionLeaseView("lease", LIVE, "world-a", 0L, 0L),
                0L, null);
    }

    private static void installLevelingType() throws Exception {
        Tamework instance = (Tamework) unsafe().allocateInstance(Tamework.class);
        Field type = Tamework.class.getDeclaredField("levelingComponentType");
        type.setAccessible(true);
        type.set(instance, new ComponentType<EntityStore, TameworkLevelingComponent>());
        setTameworkInstance(instance);
    }

    private static final class ProgressionFixture implements AutoCloseable {
        private final TestEntityComponentStore store = new TestEntityComponentStore(
                new EntityStore(null));
        private final Ref<EntityStore> npcRef = store.createReference();
        private final ComponentType<EntityStore, TameworkLevelingComponent> levelingType =
                new ComponentType<>();
        private final ComponentType<EntityStore, TameworkTalentsComponent> talentsType;
        private final Tamework previousTamework = tameworkInstance();
        private final Object previousAssetStore = staticField(TwLevelingConfig.class,
                "ASSET_STORE").get(null);

        private ProgressionFixture(boolean registerTalents) throws Exception {
            talentsType = registerTalents ? new ComponentType<>() : null;
            Tamework instance = (Tamework) unsafe().allocateInstance(Tamework.class);
            setField(instance, "levelingComponentType", levelingType);
            setField(instance, "talentsComponentType", talentsType);
            setTameworkInstance(instance);
            TwLevelingConfig config = TwLevelingConfig.CODEC.decode(
                    BsonDocument.parse("{\"Enabled\":true,\"Levels\":{\"MaxLevel\":20,\"BaseXp\":100.0,\"GrowthFactor\":1.0}}"), new ExtraInfo());
            setField(config, "id", "live-leveling");
            setField(config.getLevels(), "maxLevel", 20);
            setField(config.getLevels(), "baseXp", 100.0);
            setField(config.getLevels(), "growthFactor", 1.0);
            staticField(TwLevelingConfig.class, "ASSET_STORE").set(null,
                    new TestLevelingAssetStore(new DefaultAssetMap<>(Map.of(
                            "live-leveling", config))));
            TwLevelingConfig.clearRoleCache();
        }

        @Override
        public void close() throws Exception {
            staticField(TwLevelingConfig.class, "ASSET_STORE").set(null, previousAssetStore);
            TwLevelingConfig.clearRoleCache();
            setTameworkInstance(previousTamework);
            store.close();
        }
    }

    private static Field staticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class TestLevelingAssetStore extends AssetStore<String,
            TwLevelingConfig, DefaultAssetMap<String, TwLevelingConfig>> {
        private TestLevelingAssetStore(DefaultAssetMap<String, TwLevelingConfig> map) {
            super(new Builder(map));
        }

        @Override protected com.hypixel.hytale.event.IEventBus getEventBus() { return null; }
        @Override public void addFileMonitor(String pack, Path path) { }
        @Override public void removeFileMonitor(Path path) { }
        @Override protected void handleRemoveOrUpdate(Set<String> removed,
                Map<String, TwLevelingConfig> changed,
                AssetUpdateQuery query) { }

        private static final class Builder extends AssetStore.Builder<String,
                TwLevelingConfig, DefaultAssetMap<String, TwLevelingConfig>, Builder> {
            private final DefaultAssetMap<String, TwLevelingConfig> map;
            private Builder(DefaultAssetMap<String, TwLevelingConfig> map) {
                super(String.class, TwLevelingConfig.class, map);
                this.map = map;
                setPath("Tamework/Leveling");
                setCodec(TwLevelingConfig.CODEC);
                setKeyFunction(TwLevelingConfig::getId);
            }
            @Override public AssetStore<String, TwLevelingConfig,
                    DefaultAssetMap<String, TwLevelingConfig>> build() {
                return new TestLevelingAssetStore(map);
            }
        }
    }

    private static Tamework tameworkInstance() throws Exception {
        Field instance = Tamework.class.getDeclaredField("instance");
        instance.setAccessible(true);
        return (Tamework) instance.get(null);
    }

    private static void setTameworkInstance(Tamework value) throws Exception {
        Field instance = Tamework.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class TestEntityStore extends EntityStore {
        private TestEntityComponentStore store;

        private TestEntityStore(World world) {
            super(world);
        }

        @Override
        public TestEntityComponentStore getStore() {
            return store;
        }
    }

    private static final class TestWorld extends World {
        private Ref<EntityStore> ref;

        private TestWorld() throws java.io.IOException {
            super("unused", Path.of("."),
                    new com.hypixel.hytale.server.core.universe.world.WorldConfig());
        }

        @Override
        public String getName() {
            return "world-a";
        }

        @Override
        public Ref<EntityStore> getEntityRef(UUID uuid) {
            return LIVE.equals(uuid) ? ref : null;
        }
    }
}
