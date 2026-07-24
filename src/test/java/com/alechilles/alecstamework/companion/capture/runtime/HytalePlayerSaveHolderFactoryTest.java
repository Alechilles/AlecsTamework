package com.alechilles.alecstamework.companion.capture.runtime;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/** Protects actor-receipt saves from cloning codec-less live components. */
class HytalePlayerSaveHolderFactoryTest {

    @Test
    void playerSaveHolderUsesLiveComponentsWithoutCloning() {
        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(null)) {
            ComponentType<EntityStore, CloneRejectingComponent> type =
                    store.getRegistry().registerComponent(
                            CloneRejectingComponent.class,
                            CloneRejectingComponent::new
                    );
            Ref<EntityStore> actor = store.createReference();
            CloneRejectingComponent component =
                    new CloneRejectingComponent();
            store.put(actor, type, component);

            Holder<EntityStore> holder =
                    HytalePlayerSaveHolderFactory.create(store, actor);

            assertSame(
                    component,
                    holder.getComponent(type),
                    "The July 24 capture-release failure was caused by "
                            + "copySerializableEntity cloning a component "
                            + "whose codec was null"
            );
        }
    }

    private static final class CloneRejectingComponent
            implements Component<EntityStore> {
        @Override
        public Component<EntityStore> clone() {
            throw new AssertionError(
                    "Player save holder construction must not clone components"
            );
        }
    }
}
