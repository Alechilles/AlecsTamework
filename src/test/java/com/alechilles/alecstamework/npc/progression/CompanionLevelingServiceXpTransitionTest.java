package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
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
import sun.misc.Unsafe;

class CompanionLevelingServiceXpTransitionTest {
    private static final UUID NPC_UUID = UUID.fromString(
            "51000000-0000-0000-0000-000000000001");
    private static final String ROLE_ID = "Test_Companion";

    @Test
    void appliedAwardReturnsAndPublishesTheSameTransition() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            CompanionLevelingService.AwardResult result = fixture.award();

            assertTrue(result.applied());
            assertNotNull(result.transition());
            assertSame(result.transition(), fixture.publishedTransition());
            assertEquals(5.0, result.transition().awardedXp());
            assertEquals(5.0, fixture.leveling().getTotalXp());
        }
    }

    @Test
    void missingNpcIdentityDoesNotApplyOrMutateXp() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            CompanionLevelingService.AwardResult result = fixture.award();

            assertFalse(result.applied());
            assertEquals(0.0, fixture.leveling().getTotalXp());
            assertEquals(0, fixture.publishedCount());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Tamework previousTamework = tameworkInstance();
        private final SimpleClaimsDamageHytaleFixture.HytaleModuleScope hytaleScope =
                SimpleClaimsDamageHytaleFixture.HytaleModuleScope.install();
        private final Object previousAssetStore = staticField(
                TwLevelingConfig.class, "ASSET_STORE").get(null);
        private final ComponentType<EntityStore, TameworkLevelingComponent> levelingType =
                new ComponentType<>();
        private final ComponentType<EntityStore, TameworkTamedComponent> tamedType =
                new ComponentType<>();
        private final TestEntityComponentStore store = new TestEntityComponentStore(
                new EntityStore(null));
        private final Ref<EntityStore> npcRef = store.createReference();
        private final AtomicReference<CompanionXpTransition> published = new AtomicReference<>();

        private Fixture(boolean withNpcIdentity) throws Exception {
            Tamework tamework = tameworkInstance();
            setField(tamework, "levelingComponentType", levelingType);
            setField(tamework, "tamedComponentType", tamedType);
            installConfig();

            store.put(npcRef, tamedType, new TameworkTamedComponent(true));
            store.put(npcRef, levelingType,
                    new TameworkLevelingComponent("test-leveling", 1, 0.0, 0.0));
            if (withNpcIdentity) {
                NPCEntity npc = new NPCEntity();
                npc.setLegacyUUID(NPC_UUID);
                npc.setRoleName(ROLE_ID);
                store.put(npcRef, NPCEntity.getComponentType(), npc);
            }
        }

        private CompanionLevelingService.AwardResult award() {
            return CompanionLevelingService.awardXp(
                    npcRef,
                    store,
                    null,
                    ROLE_ID,
                    com.alechilles.alecstamework.api.CompanionXpSource.CUSTOM,
                    5.0,
                    null,
                    published::set
            );
        }

        private TameworkLevelingComponent leveling() {
            return store.getComponent(npcRef, levelingType);
        }

        private int publishedCount() {
            return published.get() == null ? 0 : 1;
        }

        private CompanionXpTransition publishedTransition() {
            return published.get();
        }

        @Override
        public void close() throws Exception {
            store.close();
            staticField(TwLevelingConfig.class, "ASSET_STORE").set(null, previousAssetStore);
            TwLevelingConfig.clearRoleCache();
            setTameworkInstance(previousTamework);
            hytaleScope.close();
        }

        private static void installConfig() throws Exception {
            TwLevelingConfig config = TwLevelingConfig.CODEC.decode(
                    BsonDocument.parse("""
                            {"Enabled":true,"RoleIds":["Test_Companion"],"Levels":{"MaxLevel":20,"BaseXp":100.0,"GrowthFactor":1.0}}
                            """),
                    new com.hypixel.hytale.codec.ExtraInfo());
            setField(config, "id", "test-leveling");
            staticField(TwLevelingConfig.class, "ASSET_STORE").set(null,
                    new TestLevelingAssetStore(new DefaultAssetMap<>(Map.of(
                            "test-leveling", config))));
            TwLevelingConfig.clearRoleCache();
        }
    }

    private static final class TestLevelingAssetStore extends AssetStore<String,
            TwLevelingConfig, DefaultAssetMap<String, TwLevelingConfig>> {
        private TestLevelingAssetStore(DefaultAssetMap<String, TwLevelingConfig> map) {
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
                                            Map<String, TwLevelingConfig> changed,
                                            AssetUpdateQuery query) {
        }

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

            @Override
            public AssetStore<String, TwLevelingConfig,
                    DefaultAssetMap<String, TwLevelingConfig>> build() {
                return new TestLevelingAssetStore(map);
            }
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

    private static Tamework tameworkInstance() throws Exception {
        return (Tamework) staticField(Tamework.class, "instance").get(null);
    }

    private static void setTameworkInstance(Tamework value) throws Exception {
        staticField(Tamework.class, "instance").set(null, value);
    }

    @SuppressWarnings("unused")
    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
