package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.google.gson.Gson;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.TestItemAssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Arrays;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises the real happiness service: timed care must not decay or charge twice on expiry. */
class CompanionHappinessTimedEffectsTest {
    @Test
    void bonusSurvivesConvergenceAndRefreshWithoutStacking() throws Exception {
        try (Fixture f = new Fixture(69, 12)) {
            f.pet();
            assertEquals(81, f.value(), 0.02);
            f.elapseMinute();
            assertEquals(81, f.value(), 0.02);
            f.pet();
            assertEquals(81, f.value(), 0.02);
            f.expire();
            assertEquals(69, f.value(), 0.02);
        }
    }

    @Test
    void expiryAfterUpperLimitAndReloadRestoresUnderlyingHappiness() throws Exception {
        try (Fixture f = new Fixture(96, 12)) {
            f.pet();
            assertEquals(100, f.value(), 0.02);
            f.reload();
            f.expire();
            assertEquals(96, f.value(), 0.02);
        }
    }

    @Test
    void negativeEffectSurvivesConvergenceWithoutExpiryRebound() throws Exception {
        try (Fixture f = new Fixture(5, -12)) {
            f.pet();
            assertEquals(0, f.value(), 0.02);
            f.elapseMinute();
            assertEquals(0, f.value(), 0.02);
            f.reload();
            f.expire();
            assertEquals(5, f.value(), 0.02);
        }
    }

    @Test
    void deterioratingConditionsStillLowerMoodWhileCareIsActive() throws Exception {
        try (Fixture f = new Fixture(69, 12)) {
            f.pet();
            f.setTarget(59);
            f.elapseMinute();
            assertEquals(73, f.value(), 0.02);
            assertEquals(71, CompanionHappinessService.resolveSnapshot(f.ref, f.store).target(), 0.02);
            f.expire();
            assertEquals(61, f.value(), 0.02);
            f.elapseMinute();
            assertEquals(59, f.value(), 0.02);
        }
    }

    @Test
    void cappedUnderlyingChangesSurviveCaptureSnapshotAndExpiry() throws Exception {
        try (Fixture f = new Fixture(96, 20)) {
            f.pet();
            f.setTarget(40);
            f.elapseMinute();
            assertEquals(100, f.value(), 0.02);
            var codec = new CoopResidentStateSnapshotCodec();
            var snapshot = codec.decode("{\"version\":\"1\",\"npcUuid\":\"00000000-0000-0000-0000-000000000001\","
                    + "\"happiness\":" + new Gson().toJson(f.state().clone()) + "}").snapshot();
            f.store.put(f.ref, f.type, codec.decode(codec.encode(snapshot)).snapshot().happiness());
            f.expire();
            assertEquals(88, f.value(), 0.02);
        }
    }

    @Test
    void expiredLegacyEffectsDoNotDeductFromSavedMoodAgain() throws Exception {
        try (Fixture f = new Fixture(69, 12)) {
            var legacy = TameworkHappinessComponent.CODEC.decode(BsonDocument.parse("""
                    {"ConfigId":"test-happiness","Value":69,"LastUpdateMs":0,
                    "ActiveImpulses":[{"Key":"pet","Value":12,"ExpiresAtMs":1}]}
                    """), new ExtraInfo());
            f.store.put(f.ref, f.type, legacy);
            CompanionHappinessService.reconcile(f.ref, f.store);
            assertEquals(69, f.value(), 0.02);
            f.reload();
            f.pet();
            f.elapseMinute();
            assertEquals(81, f.value(), 0.02);
            f.expire();
            assertEquals(69, f.value(), 0.02);
        }
    }

    @Test
    void captureItemReleasePreservesFlatEffectUntilItsOriginalExpiry() throws Exception {
        try (Fixture f = new Fixture(96, 12)) {
            f.pet();
            long originalExpiry = f.state().getActiveImpulses()[0].getExpiresAtMs();
            // Exercise the real item writer and the mapper used by release/orphan recovery.
            Class<?> writerType = Class.forName("com.alechilles.alecstamework.items.SpawnerNpcProgressionMetadataService");
            var writerCtor = writerType.getDeclaredConstructor();
            writerCtor.setAccessible(true);
            var write = writerType.getDeclaredMethod("applyHappinessMetadata", ItemStack.class, Ref.class, Store.class);
            write.setAccessible(true);
            ItemStack captured = (ItemStack) write.invoke(writerCtor.newInstance(),
                    new ItemStack("Test_Capture", 1), f.ref, f.store);
            Class<?> metadataType = Class.forName("com.alechilles.alecstamework.items.persistence.LegacyCapturedArtifactMetadata");
            var metadataCtor = metadataType.getDeclaredConstructor(BsonDocument.class);
            metadataCtor.setAccessible(true);
            Class<?> mapperType = Class.forName("com.alechilles.alecstamework.items.persistence.LegacyCapturedArtifactProgressionMapper");
            var mapperCtor = mapperType.getDeclaredConstructor();
            mapperCtor.setAccessible(true);
            var read = mapperType.getDeclaredMethod("happiness", metadataType, long.class);
            read.setAccessible(true);
            var restored = (TameworkHappinessComponent) read.invoke(mapperCtor.newInstance(),
                    metadataCtor.newInstance(BsonDocument.parse(captured.getMetadata().toJson())), System.currentTimeMillis());
            assertEquals(originalExpiry, restored.getActiveImpulses()[0].getExpiresAtMs());
            f.store.put(f.ref, f.type, restored);
            f.elapseMinute();
            assertEquals(100, f.value(), 0.02);
            f.expire();
            assertEquals(96, f.value(), 0.02);
        }
    }

    @Test
    void singleFoodEffectReplacesPriorFoodAndNeutralMealClearsIt() throws Exception {
        try (Fixture f = new Fixture(50, 0)) {
            f.enableSingleFoodEffect(Map.of("Food_A", 6.0, "Food_B", -4.0));
            f.feed("Food_A");
            assertEquals(1, f.foodEffectCount());
            assertEquals("feed:item:food_a", f.foodEffectKey());

            f.feed("Food_B");
            assertEquals(1, f.foodEffectCount());
            assertEquals("feed:item:food_b", f.foodEffectKey());

            f.feed("Food_Neutral");
            assertEquals(0, f.foodEffectCount());
        }
    }

    @Test
    void singleFoodEffectTreatsExplicitZeroFoodProfileAsNeutralInsteadOfLegacyPenalty() throws Exception {
        try (Fixture f = new Fixture(50, 0)) {
            f.installCompatibleZeroFoodProfile();
            f.enableSingleFoodEffect(Map.of("Legacy_Food", 6.0, "Compatible_Food", -8.0));
            f.feed("Legacy_Food");
            assertEquals(1, f.foodEffectCount());

            f.feed("Compatible_Food");
            assertEquals(0, f.foodEffectCount());
        }

        try (Fixture f = new Fixture(50, 0)) {
            f.installCompatibleZeroFoodProfile();
            f.setFeedItemImpulses(Map.of("Compatible_Food", -8.0));
            f.feed("Compatible_Food");
            assertEquals("feed:item:compatible_food", f.foodEffectKey());
            assertEquals(-8.0, f.foodEffectValue(), 0.000001);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final SimpleClaimsDamageHytaleFixture.HytaleModuleScope scope =
                SimpleClaimsDamageHytaleFixture.HytaleModuleScope.install();
        private final Object previousAssets = field(TwHappinessConfig.class, "ASSET_STORE").get(null);
        private final Object previousFoodAssets = field(TwFoodConfig.class, "ASSET_STORE").get(null);
        private final Object previousItems = field(Item.class, "ASSET_STORE").get(null);
        private final ComponentType<EntityStore, TameworkHappinessComponent> type = new ComponentType<>();
        private final TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null));
        private final Ref<EntityStore> ref = store.createReference();
        private final TwHappinessConfig config;

        Fixture(double base, double gain) throws Exception {
            field(Item.class, "ASSET_STORE").set(null,
                    new TestItemAssetStore(new DefaultAssetMap<>(Map.of("Test_Capture", new Item("Test_Capture")))));
            Object tamework = field(Tamework.class, "instance").get(null);
            field(Tamework.class, "happinessComponentType").set(tamework, type);
            config = TwHappinessConfig.CODEC.decode(BsonDocument.parse("""
                    {"Enabled":true,"Equilibrium":{"BaseSetpoint":%s,"ConvergencePerMinute":8},
                    "Impulses":{"GainOnPet":%s,"FeedImpulseDurationMinutes":15},
                    "Modifiers":{"Hunger":{"Enabled":false},"Thirst":{"Enabled":false},
                    "Population":{"Enabled":false},"OwnerNearbyOffset":0}}
                    """.formatted(base, gain)), new ExtraInfo());
            field(TwHappinessConfig.class, "id").set(config, "test-happiness");
            field(TwHappinessConfig.class, "ASSET_STORE").set(null,
                    new TestAssets(new DefaultAssetMap<>(Map.of("test-happiness", config))));
            TwHappinessConfig.clearRoleCache();
            store.put(ref, type, new TameworkHappinessComponent("test-happiness", base, System.currentTimeMillis()));
        }

        TameworkHappinessComponent state() { return store.getComponent(ref, type); }
        double value() { return state().getValue(); }
        void pet() { CompanionHappinessService.applyPetGain(ref, store); }
        void feed(String itemId) { CompanionHappinessService.applyFeedGain(ref, store, itemId); }
        void enableSingleFoodEffect(Map<String, Double> impulses) throws Exception {
            field(config.getImpulses().getClass(), "singleFoodEffect").set(config.getImpulses(), true);
            field(config.getImpulses().getClass(), "feedItemImpulses").set(config.getImpulses(), impulses);
        }
        void setFeedItemImpulses(Map<String, Double> impulses) throws Exception {
            field(config.getImpulses().getClass(), "feedItemImpulses").set(config.getImpulses(), impulses);
        }
        void installCompatibleZeroFoodProfile() throws Exception {
            TwFoodConfig foodConfig = TwFoodConfig.CODEC.decode(BsonDocument.parse("""
                    {"Enabled":true,"RoleIds":["Test_Food_Role"],
                    "Foods":{"Compatible":["Compatible_Food"]},
                    "Happiness":{"Compatible":0}}
                    """), new ExtraInfo());
            field(TwFoodConfig.class, "id").set(foodConfig, "test-food");
            field(TwFoodConfig.class, "ASSET_STORE").set(null,
                    new FoodAssets(new DefaultAssetMap<>(Map.of("test-food", foodConfig))));
            TwFoodConfig.clearRoleCache();
            NPCEntity npc = new NPCEntity();
            npc.setRoleName("Test_Food_Role");
            store.put(ref, NPCEntity.getComponentType(), npc);
        }
        int foodEffectCount() {
            return (int) Arrays.stream(state().getActiveImpulses())
                    .filter(effect -> effect.getKey().startsWith("feed:item:") || effect.getKey().startsWith("feed:food:"))
                    .count();
        }
        String foodEffectKey() {
            return Arrays.stream(state().getActiveImpulses())
                    .map(TameworkHappinessComponent.ActiveImpulse::getKey)
                    .filter(key -> key.startsWith("feed:item:") || key.startsWith("feed:food:"))
                    .findFirst()
                    .orElse(null);
        }
        double foodEffectValue() {
            return Arrays.stream(state().getActiveImpulses())
                    .filter(effect -> effect.getKey().startsWith("feed:item:") || effect.getKey().startsWith("feed:food:"))
                    .mapToDouble(TameworkHappinessComponent.ActiveImpulse::getValue)
                    .findFirst()
                    .orElse(0.0);
        }
        void setTarget(double target) throws Exception {
            field(config.getEquilibrium().getClass(), "baseSetpoint").set(config.getEquilibrium(), target);
        }
        void elapseMinute() {
            state().setLastUpdateMs(System.currentTimeMillis() - 60_000);
            CompanionHappinessService.reconcile(ref, store);
        }
        void expire() {
            for (var effect : state().getActiveImpulses()) {
                effect.setExpiresAtMs(System.currentTimeMillis() - 1);
            }
            CompanionHappinessService.reconcile(ref, store);
        }
        void reload() {
            var decoded = TameworkHappinessComponent.CODEC.decode(
                    TameworkHappinessComponent.CODEC.encode(state(), new ExtraInfo()), new ExtraInfo());
            store.put(ref, type, decoded);
        }
        public void close() throws Exception {
            store.close();
            field(TwHappinessConfig.class, "ASSET_STORE").set(null, previousAssets);
            field(TwFoodConfig.class, "ASSET_STORE").set(null, previousFoodAssets);
            field(Item.class, "ASSET_STORE").set(null, previousItems);
            TwHappinessConfig.clearRoleCache();
            TwFoodConfig.clearRoleCache();
            scope.close();
        }
    }

    private static final class FoodAssets extends AssetStore<String, TwFoodConfig,
            DefaultAssetMap<String, TwFoodConfig>> {
        FoodAssets(DefaultAssetMap<String, TwFoodConfig> map) { super(new Builder(map)); }
        protected IEventBus getEventBus() { return null; }
        public void addFileMonitor(String pack, Path path) { }
        public void removeFileMonitor(Path path) { }
        protected void handleRemoveOrUpdate(Set<String> removed, Map<String, TwFoodConfig> changed,
                                            AssetUpdateQuery query) { }
        private static final class Builder extends AssetStore.Builder<String, TwFoodConfig,
                DefaultAssetMap<String, TwFoodConfig>, Builder> {
            private final DefaultAssetMap<String, TwFoodConfig> map;
            Builder(DefaultAssetMap<String, TwFoodConfig> map) {
                super(String.class, TwFoodConfig.class, map);
                this.map = map;
                setPath("Tamework/Food");
                setCodec(TwFoodConfig.CODEC);
                setKeyFunction(TwFoodConfig::getId);
            }
            public AssetStore<String, TwFoodConfig, DefaultAssetMap<String, TwFoodConfig>> build() {
                return new FoodAssets(map);
            }
        }
    }

    private static final class TestAssets extends AssetStore<String, TwHappinessConfig,
            DefaultAssetMap<String, TwHappinessConfig>> {
        TestAssets(DefaultAssetMap<String, TwHappinessConfig> map) { super(new Builder(map)); }
        protected IEventBus getEventBus() { return null; }
        public void addFileMonitor(String pack, Path path) { }
        public void removeFileMonitor(Path path) { }
        protected void handleRemoveOrUpdate(Set<String> removed, Map<String, TwHappinessConfig> changed,
                                            AssetUpdateQuery query) { }
        private static final class Builder extends AssetStore.Builder<String, TwHappinessConfig,
                DefaultAssetMap<String, TwHappinessConfig>, Builder> {
            private final DefaultAssetMap<String, TwHappinessConfig> map;
            Builder(DefaultAssetMap<String, TwHappinessConfig> map) {
                super(String.class, TwHappinessConfig.class, map);
                this.map = map;
                setPath("Tamework/Happiness");
                setCodec(TwHappinessConfig.CODEC);
                setKeyFunction(TwHappinessConfig::getId);
            }
            public AssetStore<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> build() {
                return new TestAssets(map);
            }
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
