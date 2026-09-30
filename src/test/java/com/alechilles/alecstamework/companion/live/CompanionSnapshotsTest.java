package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompanionSnapshotsTest {
    @Test
    void captureWrapsTheSerializedEntityWithItsWorldAndGameTime() {
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType =
                registry.registerComponent(TameworkNpcNameComponent.class, "TameworkNpcName", TameworkNpcNameComponent.CODEC);
        Store<EntityStore> store = registry.addStore(null, null);
        try {
            Holder<EntityStore> holder = registry.newHolder();
            holder.addComponent(nameType, new TameworkNpcNameComponent("Wooly", null, 0L, TameworkNpcNameComponent.NameSource.Player));
            Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);
            UUID profile = UUID.randomUUID();

            SnapshotEnvelope envelope = new CompanionSnapshots(registry::serialize)
                    .capture(ref, store, profile, 7, "default", -42_000L);

            assertNotNull(envelope);
            assertEquals(profile, envelope.profileId());
            assertEquals(7, envelope.generation());
            assertEquals("default", CompanionSnapshots.world(envelope));
            assertEquals(-42_000L, CompanionSnapshots.gameTimeMs(envelope));
            Holder<EntityStore> restored = registry.deserialize(CompanionSnapshots.entity(envelope));
            assertEquals("Wooly", restored.getComponent(nameType).getName());
        } finally {
            registry.removeStore(store);
            registry.shutdown();
        }
    }

    @Test
    void aSerializerFailureReturnsNoSnapshotInsteadOfThrowing() {
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        Store<EntityStore> store = registry.addStore(null, null);
        try {
            Ref<EntityStore> ref = store.addEntity(registry.newHolder(), AddReason.SPAWN);
            CompanionSnapshots snapshots = new CompanionSnapshots(h -> { throw new IllegalStateException("broken codec"); });

            assertNull(snapshots.capture(ref, store, UUID.randomUUID(), 0, "default", 0L));
        } finally {
            registry.removeStore(store);
            registry.shutdown();
        }
    }
}
