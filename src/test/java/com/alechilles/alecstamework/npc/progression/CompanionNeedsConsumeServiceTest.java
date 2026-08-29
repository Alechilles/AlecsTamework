package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.HusbandryOutcomeApi;
import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRegistry;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRuntime;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionNeedsConsumeServiceTest {
    @AfterEach
    void tearDown() {
        CompanionRuntimeClock.resetForTests();
    }

    @Test
    void resolvesCommittedContainerFoodWithActualItemAndValues() {
        List<NeedsSatisfactionOutcome> outcomes =
                NeedsSatisfactionOutcome.resolveCommitted(
                        10.0,
                        35.0,
                        20.0,
                        20.0,
                        Map.of("Food_Wheat", 1, "Food_Apple", 1),
                        false,
                        null
                );

        assertEquals(List.of(new NeedsSatisfactionOutcome(
                "hunger", "container", "Food_Apple", 10.0, 35.0, 25.0
        )), outcomes);
    }

    @Test
    void resolvesCommittedTroughWaterWithExactValues() {
        List<NeedsSatisfactionOutcome> outcomes =
                NeedsSatisfactionOutcome.resolveCommitted(
                        30.0,
                        30.0,
                        5.0,
                        45.0,
                        Map.of(),
                        false,
                        "water"
                );

        assertEquals(List.of(new NeedsSatisfactionOutcome(
                "thirst", "water", "water", 5.0, 45.0, 40.0
        )), outcomes);
    }

    @Test
    void combinedConsumeProducesOneFoodAndOneWaterOutcome() {
        List<NeedsSatisfactionOutcome> outcomes =
                NeedsSatisfactionOutcome.resolveCommitted(
                        10.0,
                        20.0,
                        15.0,
                        40.0,
                        Map.of("Food_Wheat", 1),
                        false,
                        "water"
                );

        assertEquals(2, outcomes.size());
        assertEquals("hunger", outcomes.get(0).needType());
        assertEquals("thirst", outcomes.get(1).needType());
        assertEquals("water", outcomes.get(1).resourceSource());
    }

    @Test
    void consumedFoodThatOnlyChangesHappinessStillProducesCareOutcome() {
        List<NeedsSatisfactionOutcome> outcomes =
                NeedsSatisfactionOutcome.resolveCommitted(
                        100.0,
                        100.0,
                        50.0,
                        50.0,
                        Map.of("Food_Wheat", 1),
                        true,
                        null
                );

        assertEquals(1, outcomes.size());
        assertEquals(0.0, outcomes.get(0).restoredAmount());
    }

    @Test
    void missingResourceAndNoStateChangeProduceNoOutcome() {
        assertTrue(NeedsSatisfactionOutcome.resolveCommitted(
                10.0, 10.0, 20.0, 20.0, Map.of(), false, null
        ).isEmpty());
        assertTrue(NeedsSatisfactionOutcome.resolveCommitted(
                10.0, 10.0, 20.0, 20.0,
                Map.of("Food_Wheat", 1), false, "water"
        ).isEmpty());
    }

    @Test
    void consumeOriginWithFiniteCoordinatesCanUseTargetFirstProbe() {
        assertTrue(CompanionNeedsConsumeService.canUseTargetFirstConsumeProbeForTests(
                new org.joml.Vector3d(1.5, 64.0, 2.5)
        ));
    }

    @Test
    void consumeOriginWithNaNCoordinateSkipsTargetFirstProbe() {
        assertFalse(CompanionNeedsConsumeService.canUseTargetFirstConsumeProbeForTests(
                new org.joml.Vector3d(Double.NaN, 64.0, 2.5)
        ));
    }

    @Test
    void feedRefillKeepsNormalRestorationBeforeTheProductionClamp() throws Exception {
        try (NeedsFixture fixture = new NeedsFixture()) {
            assertTrue(CompanionNeedsConsumeService.applyFeedInteractionRefill(
                    fixture.firstRef, fixture.store, null, null));
            assertTrue(CompanionNeedsConsumeService.applyFeedInteractionRefill(
                    fixture.clampedRef, fixture.store, null, null));
            assertEquals(90.0, fixture.firstNeeds().getHunger(), 0.000001);
            assertEquals(100.0, fixture.clampedNeeds().getHunger(), 0.000001);
        }
    }

    @Test
    void needsDecayOutcomeScalesBaseAndTraitDecayTogether() throws Exception {
        try (NeedsFixture fixture = new NeedsFixture()) {
            HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry();
            registry.register(context -> {
                assertEquals(HusbandryOutcomeKind.NEEDS_DECAY, context.kind());
                return new HusbandryOutcomeModifiers(0.70, 1.30, 0.0, 0.0, 1.0);
            });
            installRuntime(registry);
            try {
                fixture.prepareDecayState(fixture.firstRef, true);
                fixture.prepareDecayState(fixture.clampedRef, false);
                CompanionRuntimeClock.advanceByDeltaSeconds(30.002f);

                assertTrue(runNeedsUpdate(fixture.firstRef, fixture.store));
                assertTrue(runNeedsUpdate(fixture.clampedRef, fixture.store));
                assertEquals(94.4, fixture.firstNeeds().getHunger(), 0.000001);
                assertEquals(93.0, fixture.clampedNeeds().getHunger(), 0.000001);
            } finally {
                clearRuntime(registry);
                registry.close();
            }
        }
    }

    private static boolean runNeedsUpdate(Ref<EntityStore> npcRef,
                                           TestEntityComponentStore store) {
        return CompanionNeedsService.runNeedsUpdate(
                npcRef,
                store,
                null,
                0.0,
                0.0,
                false,
                false,
                null,
                null
        );
    }

    private static void installRuntime(HusbandryOutcomeApi api) throws Exception {
        invokeRuntime("install", api);
    }

    private static void clearRuntime(HusbandryOutcomeApi api) throws Exception {
        invokeRuntime("clear", api);
    }

    private static void invokeRuntime(String methodName, HusbandryOutcomeApi api) throws Exception {
        Method method = HusbandryOutcomeRuntime.class.getDeclaredMethod(
                methodName, HusbandryOutcomeApi.class);
        method.setAccessible(true);
        method.invoke(null, api);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field staticField(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }

    private static final class NeedsFixture implements AutoCloseable {
        private final SimpleClaimsDamageHytaleFixture.HytaleModuleScope hytaleScope;
        private final EntityStatsModule previousStatsModule;
        private final Object previousNeedsAssetStore;
        private final Object previousTraitAssetStore;
        private final ComponentType<EntityStore, TameworkNeedsComponent> needsType = new ComponentType<>();
        private final ComponentType<EntityStore, TameworkTraitsComponent> traitsType = new ComponentType<>();
        private final TestEntityComponentStore store;
        private final Ref<EntityStore> firstRef;
        private final Ref<EntityStore> clampedRef;

        private NeedsFixture() throws Exception {
            hytaleScope = SimpleClaimsDamageHytaleFixture.HytaleModuleScope.install();
            previousStatsModule = installStatsModule();
            previousNeedsAssetStore = staticField(TwNeedsConfig.class, "ASSET_STORE").get(null);
            previousTraitAssetStore = staticField(TwTraitConfig.class, "ASSET_STORE").get(null);
            setField(Tamework.getInstance(), "needsComponentType", needsType);
            setField(Tamework.getInstance(), "traitsComponentType", traitsType);
            store = new TestEntityComponentStore(new EntityStore(null));
            firstRef = store.createReference();
            clampedRef = store.createReference();
            installConfig();
            installTraitConfig();
            putNpc(firstRef, UUID.fromString("40000000-0000-0000-0000-000000000001"));
            putNpc(clampedRef, UUID.fromString("40000000-0000-0000-0000-000000000002"));
            store.put(firstRef, needsType,
                    new TameworkNeedsComponent("care-test", 80.0, 40.0, 0.0, 0L, 0L));
            store.put(clampedRef, needsType,
                    new TameworkNeedsComponent("care-test", 95.0, 40.0, 0.0, 0L, 0L));
            store.put(firstRef, traitsType,
                    new TameworkTraitsComponent(
                            "trait-test",
                            1L,
                            new TameworkTraitsComponent.TraitValue[] {
                                    new TameworkTraitsComponent.TraitValue("Trait_Decay", 0.8)
                            }
                    ));
        }

        private void putNpc(Ref<EntityStore> ref, UUID uuid) {
            NPCEntity npc = new NPCEntity();
            npc.setRoleName("RoleWithoutNeedsConfig");
            npc.setLegacyUUID(uuid);
            store.put(ref, NPCEntity.getComponentType(), npc);
        }

        private TameworkNeedsComponent firstNeeds() {
            return store.getComponent(firstRef, needsType);
        }

        private TameworkNeedsComponent clampedNeeds() {
            return store.getComponent(clampedRef, needsType);
        }

        private void prepareDecayState(Ref<EntityStore> ref, boolean traitAttached) {
            TameworkNeedsComponent component = store.getComponent(ref, needsType);
            component.setHunger(100.0);
            component.setThirst(100.0);
            component.setLastUpdateMs(1L);
            component.setLastPassiveSweepMs(1L);
            store.put(ref, needsType, component);
            if (!traitAttached && store.getComponent(ref, traitsType) != null) {
                store.tryRemoveComponent(ref, traitsType);
            }
        }

        private void installConfig() throws Exception {
            TwNeedsConfig config = TwNeedsConfig.CODEC.decode(
                    org.bson.BsonDocument.parse("""
                            {
                              "Enabled": true,
                              "Decay": {
                                "HungerPerMinute": 20.0,
                                "ThirstPerMinute": 20.0
                              },
                              "Timing": {
                                "Basis": "REAL_TIME"
                              },
                              "Values": {
                                "HungerDefault": 100.0,
                                "HungerMin": 0.0,
                                "HungerMax": 100.0,
                                "ThirstDefault": 100.0,
                                "ThirstMin": 0.0,
                                "ThirstMax": 100.0
                              },
                              "ManualRefill": {
                                "HungerGainOnFeedInteraction": 10.0
                              }
                            }
                            """), new com.hypixel.hytale.codec.ExtraInfo());
            setField(config, "id", "care-test");
            staticField(TwNeedsConfig.class, "ASSET_STORE").set(null,
                    new TestNeedsAssetStore(new DefaultAssetMap<>(Map.of("care-test", config))));
            TwNeedsConfig.clearRoleCache();
        }

        private void installTraitConfig() throws Exception {
            Constructor<TwTraitConfig> constructor = TwTraitConfig.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            TwTraitConfig config = constructor.newInstance();
            setField(config, "id", "trait-test");
            setField(config, "enabled", true);
            TwTraitConfig.TraitDefinition definition = new TwTraitConfig.TraitDefinition();
            setField(definition, "id", "Trait_Decay");
            setField(definition, "displayName", "Trait Decay");
            setField(definition, "effectKey", "NeedsDecayMultiplier");
            setField(config, "traits", new TwTraitConfig.TraitDefinition[] {definition});
            staticField(TwTraitConfig.class, "ASSET_STORE").set(null,
                    new TestTraitAssetStore(new DefaultAssetMap<>(Map.of("trait-test", config))));
            TwTraitConfig.clearRoleCache();
        }

        @Override
        public void close() throws Exception {
            store.close();
            staticField(TwNeedsConfig.class, "ASSET_STORE").set(null, previousNeedsAssetStore);
            staticField(TwTraitConfig.class, "ASSET_STORE").set(null, previousTraitAssetStore);
            TwNeedsConfig.clearRoleCache();
            TwTraitConfig.clearRoleCache();
            staticField(EntityStatsModule.class, "instance").set(null, previousStatsModule);
            hytaleScope.close();
        }

        private static EntityStatsModule installStatsModule() throws Exception {
            Field instanceField = staticField(EntityStatsModule.class, "instance");
            EntityStatsModule previous = (EntityStatsModule) instanceField.get(null);
            EntityStatsModule module = (EntityStatsModule) unsafe().allocateInstance(EntityStatsModule.class);
            setField(module, "entityStatMapComponentType", new ComponentType<>());
            instanceField.set(null, module);
            return previous;
        }
    }

    private static final class TestNeedsAssetStore extends AssetStore<String,
            TwNeedsConfig, DefaultAssetMap<String, TwNeedsConfig>> {
        private TestNeedsAssetStore(DefaultAssetMap<String, TwNeedsConfig> map) {
            super(new Builder(map));
        }

        @Override
        protected IEventBus getEventBus() {
            return null;
        }

        @Override
        public void addFileMonitor(String pack, Path path) {
        }

        @Override
        public void removeFileMonitor(Path path) {
        }

        @Override
        protected void handleRemoveOrUpdate(Set<String> removed,
                                            Map<String, TwNeedsConfig> changed,
                                            AssetUpdateQuery query) {
        }

        private static final class Builder extends AssetStore.Builder<String,
                TwNeedsConfig, DefaultAssetMap<String, TwNeedsConfig>, Builder> {
            private final DefaultAssetMap<String, TwNeedsConfig> map;

            private Builder(DefaultAssetMap<String, TwNeedsConfig> map) {
                super(String.class, TwNeedsConfig.class, map);
                this.map = map;
                setPath("Tamework/Needs");
                setCodec(TwNeedsConfig.CODEC);
                setKeyFunction(TwNeedsConfig::getId);
            }

            @Override
            public AssetStore<String, TwNeedsConfig,
                    DefaultAssetMap<String, TwNeedsConfig>> build() {
                return new TestNeedsAssetStore(map);
            }
        }
    }

    private static final class TestTraitAssetStore extends AssetStore<String,
            TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> {
        private TestTraitAssetStore(DefaultAssetMap<String, TwTraitConfig> map) {
            super(new Builder(map));
        }

        @Override
        protected IEventBus getEventBus() {
            return null;
        }

        @Override
        public void addFileMonitor(String pack, Path path) {
        }

        @Override
        public void removeFileMonitor(Path path) {
        }

        @Override
        protected void handleRemoveOrUpdate(Set<String> removed,
                                            Map<String, TwTraitConfig> changed,
                                            AssetUpdateQuery query) {
        }

        private static final class Builder extends AssetStore.Builder<String,
                TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>, Builder> {
            private final DefaultAssetMap<String, TwTraitConfig> map;

            private Builder(DefaultAssetMap<String, TwTraitConfig> map) {
                super(String.class, TwTraitConfig.class, map);
                this.map = map;
                setPath("Tamework/Traits");
                setCodec(TwTraitConfig.CODEC);
                setKeyFunction(TwTraitConfig::getId);
            }

            @Override
            public AssetStore<String, TwTraitConfig,
                    DefaultAssetMap<String, TwTraitConfig>> build() {
                return new TestTraitAssetStore(map);
            }
        }
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
