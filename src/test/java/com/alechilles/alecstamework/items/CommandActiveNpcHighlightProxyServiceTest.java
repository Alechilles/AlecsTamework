package com.alechilles.alecstamework.items;

import com.hypixel.hytale.builtin.mounts.MountSystems;
import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandActiveNpcHighlightProxyServiceTest {
    /** A removed NPC must not leave a helper with the invalid mount that crashes TrackerUpdate. */
    @Test
    void parentRemovalDetachesHighlightBeforeNextTrackingUpdate() {
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        ComponentType<EntityStore, MountedComponent> mountedType = registry.registerComponent(
                MountedComponent.class,
                () -> new MountedComponent(null, new Vector3f(), MountController.Minecart));
        ComponentType<EntityStore, MountedByComponent> mountedByType = registry.registerComponent(
                MountedByComponent.class, MountedByComponent::new);
        registry.registerSystem(new MountSystems.TrackedMounted(mountedType, mountedByType));
        registry.registerSystem(new MountSystems.RemoveMountedBy(mountedByType, mountedType));
        Store<EntityStore> store = registry.addStore(null, null);
        try {
            Ref<EntityStore> parent = store.addEntity(registry.newHolder(), AddReason.SPAWN);
            Ref<EntityStore> proxy = CommandActiveNpcHighlightProxyService.spawnMountedProxy(
                    store, registry.newHolder(), mountedType,
                    new MountedComponent(parent, new Vector3f(), MountController.Minecart));
            assertNotNull(proxy);
            assertNotNull(store.getComponent(proxy, mountedType));

            store.removeEntity(parent, RemoveReason.REMOVE);

            assertTrue(proxy.isValid());
            assertNull(store.getComponent(proxy, mountedType),
                    "Native parent removal must detach the highlight without waiting for its sweep");
        } finally {
            registry.removeStore(store);
            registry.shutdown();
        }
    }
}
