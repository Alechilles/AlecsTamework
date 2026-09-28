package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkProjectileImpactEffectSystemTest {
    /** A hatch-only payload must survive copying and emit one deferred spawn at impact. */
    @Test
    void hatchPayloadQueuesOneSpawnUsingTheFinalPositionSnapshot() {
        List<Spawn> spawns = new ArrayList<>();
        exerciseRemoval(RemoveReason.REMOVE, "{\"SpawnNpcRole\":\"egg_minion\"}", spawns);
        assertEquals(List.of(new Spawn("egg_minion", new Vector3d(4, 20.05, 6))), spawns);
    }

    @Test
    void worldUnloadDoesNotHatchSavedProjectiles() {
        List<Spawn> spawns = new ArrayList<>();
        exerciseRemoval(RemoveReason.UNLOAD, "{\"SpawnNpcRole\":\"egg_minion\"}", spawns);
        assertTrue(spawns.isEmpty());
    }

    private static void exerciseRemoval(RemoveReason reason, String json, List<Spawn> spawns) {
        ComponentType<EntityStore, TameworkProjectileImpactEffectComponent> impactType = new ComponentType<>();
        ComponentType<EntityStore, ProjectileComponent> projectileType = new ComponentType<>();
        ComponentType<EntityStore, TransformComponent> transformType = new ComponentType<>();
        try (TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
            var reference = store.createReference();
            var impact = TameworkProjectileImpactEffectComponent.CODEC.decode(BsonDocument.parse(json), new ExtraInfo());
            var transform = new TransformComponent(new Vector3d(4, 20.05, 6), new Rotation3f());
            store.put(reference, impactType, impact.clone());
            store.put(reference, projectileType, new ProjectileComponent("egg"));
            store.put(reference, transformType, transform);
            var buffer = new DeferredBuffer(store);
            var system = new TameworkProjectileImpactEffectSystem(impactType, projectileType, transformType,
                    (roleId, position, callbackStore) -> spawns.add(new Spawn(roleId, position)));

            system.onEntityRemove(reference, reason, store, buffer);
            assertTrue(spawns.isEmpty(), "NPC creation must wait until the command buffer is consumed");
            transform.getPosition().set(100, 100, 100);
            buffer.flush(store);
        }
    }

    private record Spawn(String roleId, Vector3d position) {
    }

    private static final class DeferredBuffer extends CommandBuffer<EntityStore> {
        private final List<Consumer<Store<EntityStore>>> commands = new ArrayList<>();

        private DeferredBuffer(Store<EntityStore> store) {
            super(store);
        }

        @Override
        public void run(Consumer<Store<EntityStore>> command) {
            commands.add(command);
        }

        private void flush(Store<EntityStore> store) {
            commands.forEach(command -> command.accept(store));
        }
    }
}
