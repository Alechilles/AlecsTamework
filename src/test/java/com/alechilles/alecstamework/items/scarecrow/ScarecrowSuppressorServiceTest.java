package com.alechilles.alecstamework.items.scarecrow;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.suppression.component.SpawnSuppressionComponent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScarecrowSuppressorServiceTest {
    private ComponentRegistry<EntityStore> registry;
    private Store<EntityStore> store;
    private ComponentType<EntityStore, TransformComponent> transformType;
    private ComponentType<EntityStore, SpawnSuppressionComponent> suppressionType;
    private ComponentType<EntityStore, UUIDComponent> uuidType;

    @BeforeEach
    void setUp() {
        registry = new ComponentRegistry<>();
        transformType = registry.registerComponent(TransformComponent.class, TransformComponent::new);
        suppressionType = registry.registerComponent(
                SpawnSuppressionComponent.class,
                () -> new SpawnSuppressionComponent(ScarecrowIds.SUPPRESSION_ID)
        );
        uuidType = registry.registerComponent(UUIDComponent.class, UUIDComponent::randomUUID);
        store = registry.addStore(null, null);
    }

    @AfterEach
    void tearDown() {
        registry.removeStore(store);
        registry.shutdown();
    }

    @Test
    void ensureAtCreatesOnlyOneSuppressorForOneBlockPosition() {
        ScarecrowSuppressorService service = service();
        Vector3d position = new Vector3d(10.5, 21.5, 30.5);

        service.ensureAt(store, position);
        service.ensureAt(store, position);

        assertEquals(1, countSuppressors());
    }

    @Test
    void removeAtDeletesOnlyExactTameworkSuppressor() {
        ScarecrowSuppressorService service = service();
        Vector3d target = new Vector3d(10.5, 21.5, 30.5);
        Ref<EntityStore> matching = addSuppressor(target, ScarecrowIds.SUPPRESSION_ID);
        Ref<EntityStore> neighboring = addSuppressor(
                new Vector3d(11.5, 21.5, 30.5),
                ScarecrowIds.SUPPRESSION_ID
        );
        Ref<EntityStore> unrelated = addSuppressor(target, "Spawn_Camp");

        service.removeAt(store, target);

        assertFalse(matching.isValid());
        assertTrue(neighboring.isValid());
        assertTrue(unrelated.isValid());
    }

    /** Protects removal of pre-native-block suppressors stored just above the block floor. */
    @Test
    void removeAtDeletesLegacySuppressorInSameBlockCell() {
        ScarecrowSuppressorService service = service();
        Ref<EntityStore> legacy = addSuppressor(
                new Vector3d(10.5, 21.01, 30.5),
                ScarecrowIds.SUPPRESSION_ID
        );

        service.removeAt(store, new Vector3d(10.5, 21.5, 30.5));

        assertFalse(legacy.isValid());
    }

    private ScarecrowSuppressorService service() {
        return new ScarecrowSuppressorService(transformType, suppressionType, uuidType);
    }

    private Ref<EntityStore> addSuppressor(
            Vector3d position,
            String suppressionId
    ) {
        Holder<EntityStore> holder = registry.newHolder();
        holder.addComponent(transformType, new TransformComponent(position, Rotation3f.IDENTITY));
        holder.addComponent(suppressionType, new SpawnSuppressionComponent(suppressionId));
        holder.addComponent(uuidType, UUIDComponent.randomUUID());
        return store.addEntity(holder, AddReason.SPAWN);
    }

    private int countSuppressors() {
        AtomicInteger count = new AtomicInteger();
        store.forEachChunk(
                Query.and(transformType, suppressionType),
                (BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>>) (chunk, ignored) ->
                        count.addAndGet(chunk.size())
        );
        return count.get();
    }
}
