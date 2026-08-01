package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.api.BondedCompanionLeaseView;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
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
import java.util.UUID;
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

            assertFalse(BondedCompanionPanelEntrySourceService
                    .hasLiveLevelingComponent(npcRef, store),
                    "a missing live component must not synthesize level 1 / 0 XP over durable level 10");
        } finally {
            setTameworkInstance(previous);
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

    private static void installLevelingType() throws Exception {
        Tamework instance = (Tamework) unsafe().allocateInstance(Tamework.class);
        Field type = Tamework.class.getDeclaredField("levelingComponentType");
        type.setAccessible(true);
        type.set(instance, new ComponentType<EntityStore, TameworkLevelingComponent>());
        setTameworkInstance(instance);
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
