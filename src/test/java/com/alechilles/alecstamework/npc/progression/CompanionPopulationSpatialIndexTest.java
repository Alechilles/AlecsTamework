package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies exact population queries over the store-scoped spatial snapshot. */
class CompanionPopulationSpatialIndexTest {
    private static final ComponentType<EntityStore, NPCEntity> NPC_TYPE = new ComponentType<>();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM_TYPE = new ComponentType<>();

    @Test
    void countsNearbyWithExactThreeDimensionalRadiusAndFamilyMapping() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install()) {
            TwBreedingConfig breedingConfig = breedingConfig();
            UUID sourceId = UUID.randomUUID();

            try (TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
                addNpc(store, sourceId, "Cat_Adult", 0.0, 0.0, 0.0);
                addNpc(store, UUID.randomUUID(), "Cat_Child", 3.0, 4.0, 0.0);
                addNpc(store, UUID.randomUUID(), "Cat_Adult", 0.0, 0.0, 5.0);
                addNpc(store, UUID.randomUUID(), "Cat_Adult", 0.0, 0.0, 5.01);
                addNpc(store, UUID.randomUUID(), "Dog_Adult", 1.0, 0.0, 0.0);

                CompanionPopulationSpatialIndex index = index(() -> 1_000L);
                assertEquals(2, index.countNearby(
                        store,
                        sourceId,
                        new Vector3d(0.0, 0.0, 0.0),
                        5.0,
                        "cat_adult",
                        breedingConfig
                ));
            }
        }
    }

    @Test
    void reusesStoreSnapshotAcrossQueriesUntilExpiry() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install()) {
            AtomicLong now = new AtomicLong(1_000L);
            CompanionPopulationSpatialIndex index = index(now::get);
            TwBreedingConfig breedingConfig = breedingConfig();
            UUID firstSource = UUID.randomUUID();
            UUID secondSource = UUID.randomUUID();

            try (TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
                addNpc(store, firstSource, "Cat_Adult", 0.0, 0.0, 0.0);
                addNpc(store, secondSource, "Dog_Adult", 32.0, 0.0, 0.0);

                assertEquals(0, index.countNearby(
                        store, firstSource, new Vector3d(0.0, 0.0, 0.0), 2.0, "cat_adult", breedingConfig));
                addNpc(store, UUID.randomUUID(), "Dog_Adult", 33.0, 0.0, 0.0);

                assertEquals(0, index.countNearby(
                        store, secondSource, new Vector3d(32.0, 0.0, 0.0), 8.0, "dog_adult", breedingConfig));
                now.set(6_001L);
                assertEquals(1, index.countNearby(
                        store, secondSource, new Vector3d(32.0, 0.0, 0.0), 8.0, "dog_adult", breedingConfig));
            }
        }
    }

    @Test
    void largeRadiusUsesBoundedOccupiedBucketPlanAndKeepsExactCount() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install()) {
            TwBreedingConfig breedingConfig = breedingConfig();
            UUID sourceId = UUID.randomUUID();
            CompanionPopulationSpatialIndex.QueryPlan plan =
                    CompanionPopulationSpatialIndex.planForQuery(
                            new Vector3d(0.0, 0.0, 0.0), 1_024.0);

            assertEquals(257L * 257L * 257L, plan.localCellVisits());
            assertTrue(plan.localCellVisits() > plan.maxCellVisits());
            assertTrue(plan.usesOccupiedBuckets());

            CompanionPopulationSpatialIndex.QueryPlan smallPlan =
                    CompanionPopulationSpatialIndex.planForQuery(
                            new Vector3d(0.0, 0.0, 0.0), 8.0);
            assertEquals(27L, smallPlan.localCellVisits());
            assertFalse(smallPlan.usesOccupiedBuckets());

            try (TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
                addNpc(store, sourceId, "Cat_Adult", 0.0, 0.0, 0.0);
                addNpc(store, UUID.randomUUID(), "Cat_Adult", 1_024.0, 0.0, 0.0);
                addNpc(store, UUID.randomUUID(), "Dog_Adult", 1.0, 0.0, 0.0);
                addNpc(store, UUID.randomUUID(), "Cat_Adult", 1_024.01, 0.0, 0.0);

                assertEquals(1, index(() -> 1_000L).countNearby(
                        store,
                        sourceId,
                        new Vector3d(0.0, 0.0, 0.0),
                        1_024.0,
                        "cat_adult",
                        breedingConfig
                ));
            }
        }
    }

    @Test
    void isolatesStoresAndRebuildsAfterExactStoreRemoval() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install()) {
            CompanionPopulationSpatialIndex index = index(() -> 1_000L);
            TwBreedingConfig breedingConfig = breedingConfig();
            UUID sourceId = UUID.randomUUID();

            try (TestEntityComponentStore first = new TestEntityComponentStore(new EntityStore(null));
                 TestEntityComponentStore second = new TestEntityComponentStore(new EntityStore(null))) {
                addNpc(first, sourceId, "Cat_Adult", 0.0, 0.0, 0.0);
                addNpc(second, sourceId, "Cat_Adult", 0.0, 0.0, 0.0);
                addNpc(second, UUID.randomUUID(), "Cat_Adult", 1.0, 0.0, 0.0);

                assertEquals(0, index.countNearby(
                        first, sourceId, new Vector3d(0.0, 0.0, 0.0), 4.0, "cat_adult", breedingConfig));
                assertEquals(1, index.countNearby(
                        second, sourceId, new Vector3d(0.0, 0.0, 0.0), 4.0, "cat_adult", breedingConfig));

                addNpc(first, UUID.randomUUID(), "Cat_Adult", 1.0, 0.0, 0.0);
                assertEquals(0, index.countNearby(
                        first, sourceId, new Vector3d(0.0, 0.0, 0.0), 4.0, "cat_adult", breedingConfig));
                index.remove(first);
                assertEquals(1, index.countNearby(
                        first, sourceId, new Vector3d(0.0, 0.0, 0.0), 4.0, "cat_adult", breedingConfig));
                assertEquals(1, index.countNearby(
                        second, sourceId, new Vector3d(0.0, 0.0, 0.0), 4.0, "cat_adult", breedingConfig));
            }
        }
    }

    private static Ref<EntityStore> addNpc(TestEntityComponentStore store,
                                            UUID uuid,
                                            String roleId,
                                            double x,
                                            double y,
                                            double z) {
        Ref<EntityStore> reference = store.createReference();
        NPCEntity npc = new NPCEntity();
        npc.setLegacyUUID(uuid);
        npc.setRoleName(roleId);
        store.put(reference, NPC_TYPE, npc);
        store.put(reference, TRANSFORM_TYPE,
                new TransformComponent(new Vector3d(x, y, z), new Rotation3f()));
        return reference;
    }

    private static CompanionPopulationSpatialIndex index(java.util.function.LongSupplier clock) {
        return new CompanionPopulationSpatialIndex(clock, NPC_TYPE, TRANSFORM_TYPE);
    }

    private static TwBreedingConfig breedingConfig() throws Exception {
        var ctor = TwBreedingConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        TwBreedingConfig config = ctor.newInstance();
        TwBreedingConfig.OffspringLifecycleSettings lifecycle =
                new TwBreedingConfig.OffspringLifecycleSettings();
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "adultRoleId", "Cat_Adult");
        setField(family, "babyRoleId", "Cat_Child");
        setField(lifecycle, "families", new TwBreedingConfig.RoleFamily[] {family});
        setField(config, "offspringLifecycle", lifecycle);
        return config;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
