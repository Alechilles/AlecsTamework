package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.AnimalAgeStage;
import com.alechilles.alecstamework.api.HusbandryOutcomeContext;
import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.HusbandryOutcomeProvider;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The animal's happiness and age stage reach the husbandry outcome provider. */
class HusbandryOutcomeAnimalStateTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void harvestContextCarriesTheAnimalsHappinessAndAgeStage() throws Exception {
        try (Fixture f = new Fixture("Tamework_Cow", 82)) {
            HusbandryOutcomeContext context = f.resolveHarvest();
            assertNotNull(context);
            assertEquals(HusbandryOutcomeKind.HARVEST_YIELD, context.kind());
            assertEquals(82, context.happiness(), 0.000001);
            // No aging is configured for the role, so its age stage is unknown.
            assertNull(context.ageStage());
        }
    }

    @Test
    void harvestContextReportsAJuvenileBeforeTheAnimalIsGrown() throws Exception {
        try (Fixture f = new Fixture("Tamework_Cow_Baby", 40)) {
            HusbandryOutcomeContext context = f.resolveHarvest();
            assertNotNull(context);
            assertEquals(40, context.happiness(), 0.000001);
            assertEquals(AnimalAgeStage.JUVENILE, context.ageStage());
        }
    }

    @Test
    void contextWithoutALiveAnimalLeavesTheStateUnknown() throws Exception {
        try (HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry()) {
            AtomicReference<HusbandryOutcomeContext> seen = new AtomicReference<>();
            registry.register(context -> {
                seen.set(context);
                return HusbandryOutcomeModifiers.identity();
            });
            HusbandryOutcomeRuntime.install(registry);
            try {
                HusbandryOutcomeRuntime.resolve(HusbandryOutcomeKind.HARVEST_YIELD,
                        null, null, "Tamework_Cow", "Milk_Bucket");
            } finally {
                HusbandryOutcomeRuntime.clear(registry);
            }
            assertNotNull(seen.get());
            assertNull(seen.get().happiness());
            assertNull(seen.get().ageStage());
        }
    }

    @Test
    void compatibilityConstructorsLeaveTheAnimalStateUnknown() {
        HusbandryOutcomeContext withTool = new HusbandryOutcomeContext(HusbandryOutcomeKind.HARVEST_YIELD,
                OWNER_ID, OWNER_ID, COMPANION_ID, "Tamework_Cow", "example:cow", Set.of("example:cattle"),
                "Milk_Bucket", null);
        HusbandryOutcomeContext legacy = new HusbandryOutcomeContext(HusbandryOutcomeKind.HARVEST_YIELD,
                OWNER_ID, COMPANION_ID, "Tamework_Cow", "example:cow", Set.of("example:cattle"), "Milk_Bucket");

        assertNull(withTool.happiness());
        assertNull(withTool.ageStage());
        assertNull(legacy.happiness());
        assertNull(legacy.ageStage());
        assertEquals(withTool.companionId(), legacy.companionId());
    }

    @Test
    void registryPassesTheAnimalStateToTheProvider() {
        try (HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry()) {
            AtomicReference<HusbandryOutcomeContext> seen = new AtomicReference<>();
            registry.register(context -> {
                seen.set(context);
                return HusbandryOutcomeModifiers.identity();
            });
            registry.resolve(new HusbandryOutcomeContext(HusbandryOutcomeKind.HARVEST_YIELD, OWNER_ID, OWNER_ID,
                    COMPANION_ID, "Tamework_Cow", "example:cow", Set.of("example:cattle"), "Milk_Bucket", null,
                    91.5, AnimalAgeStage.PRIME));

            assertEquals(91.5, seen.get().happiness(), 0.000001);
            assertEquals(AnimalAgeStage.PRIME, seen.get().ageStage());
        }
    }

    @Test
    void providerThatIgnoresTheAnimalStateKeepsWorking() {
        HusbandryOutcomeProvider oldStyle = context ->
                context.kind() == HusbandryOutcomeKind.HARVEST_YIELD && "Milk_Bucket".equals(context.productId())
                        ? new HusbandryOutcomeModifiers(1.0, 1.0, 0.5, 0.0, 1.0)
                        : HusbandryOutcomeModifiers.identity();
        try (HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry()) {
            registry.register(oldStyle);
            HusbandryOutcomeModifiers withState = registry.resolve(new HusbandryOutcomeContext(
                    HusbandryOutcomeKind.HARVEST_YIELD, OWNER_ID, OWNER_ID, COMPANION_ID, "Tamework_Cow",
                    "example:cow", Set.of(), "Milk_Bucket", null, 85.0, AnimalAgeStage.ADULT));
            HusbandryOutcomeModifiers withoutState = registry.resolve(new HusbandryOutcomeContext(
                    HusbandryOutcomeKind.HARVEST_YIELD, OWNER_ID, COMPANION_ID, "Tamework_Cow",
                    "example:cow", Set.of(), "Milk_Bucket"));

            assertEquals(0.5, withState.bonusOutputChance(), 0.000001);
            assertEquals(withState, withoutState);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final HytaleModuleScope scope = HytaleModuleScope.install();
        private final Object previousAssets = field(TwHappinessConfig.class, "ASSET_STORE").get(null);
        private final Object tamework = field(Tamework.class, "instance").get(null);
        private final Object previousHappinessType = field(Tamework.class, "happinessComponentType").get(tamework);
        private final ComponentType<EntityStore, TameworkHappinessComponent> type = new ComponentType<>();
        private final TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null));
        private final Ref<EntityStore> ref = store.createReference();
        private final HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry();
        private final AtomicReference<HusbandryOutcomeContext> seen = new AtomicReference<>();

        Fixture(String roleId, double happiness) throws Exception {
            field(Tamework.class, "happinessComponentType").set(tamework, type);
            TwHappinessConfig config = TwHappinessConfig.CODEC.decode(BsonDocument.parse("""
                    {"Enabled":true,"Equilibrium":{"BaseSetpoint":50,"ConvergencePerMinute":8},
                    "Modifiers":{"Hunger":{"Enabled":false},"Thirst":{"Enabled":false},
                    "Population":{"Enabled":false},"OwnerNearbyOffset":0}}
                    """), new ExtraInfo());
            field(TwHappinessConfig.class, "id").set(config, "test-happiness");
            field(TwHappinessConfig.class, "ASSET_STORE").set(null,
                    new TestAssets(new DefaultAssetMap<>(Map.of("test-happiness", config))));
            TwHappinessConfig.clearRoleCache();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(COMPANION_ID);
            npc.setRoleName(roleId);
            store.put(ref, NPCEntity.getComponentType(), npc);
            store.put(ref, type, new TameworkHappinessComponent("test-happiness", happiness,
                    System.currentTimeMillis()));
            registry.register(context -> {
                seen.set(context);
                return HusbandryOutcomeModifiers.identity();
            });
            HusbandryOutcomeRuntime.install(registry);
        }

        HusbandryOutcomeContext resolveHarvest() {
            HusbandryYieldResolver.resolveHarvest(ref, store, null, "Milk_Bucket", null, OWNER_ID);
            return seen.get();
        }

        @Override
        public void close() throws Exception {
            HusbandryOutcomeRuntime.clear(registry);
            registry.close();
            store.close();
            field(TwHappinessConfig.class, "ASSET_STORE").set(null, previousAssets);
            field(Tamework.class, "happinessComponentType").set(tamework, previousHappinessType);
            TwHappinessConfig.clearRoleCache();
            scope.close();
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
