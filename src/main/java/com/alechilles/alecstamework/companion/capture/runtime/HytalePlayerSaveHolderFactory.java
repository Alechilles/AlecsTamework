package com.alechilles.alecstamework.companion.capture.runtime;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;

/**
 * Builds the live-component holder expected by Hytale's player config saver.
 *
 * <p>Hytale's own periodic player saver uses a shallow holder on the world
 * thread. Cloning the complete serializable entity is both unnecessary and
 * unsafe because not every live player component has a direct clone codec.</p>
 */
final class HytalePlayerSaveHolderFactory {
    private HytalePlayerSaveHolderFactory() {
    }

    static Holder<EntityStore> create(
            Store<EntityStore> store,
            Ref<EntityStore> actor
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(actor, "actor");
        store.assertThread();
        Archetype<EntityStore> archetype = store.getArchetype(actor);
        if (archetype == null) {
            throw new IllegalStateException(
                    "Player entity has no live archetype"
            );
        }
        @SuppressWarnings("unchecked")
        Component<EntityStore>[] components =
                (Component<EntityStore>[]) new Component<?>[
                        archetype.length()
                ];
        for (int index = archetype.getMinIndex();
             index < archetype.length(); index++) {
            ComponentType<EntityStore, ?> type = archetype.get(index);
            if (type != null) {
                components[index] = component(store, actor, type);
            }
        }
        return store.getRegistry().newHolder(archetype, components);
    }

    private static <T extends Component<EntityStore>> T component(
            Store<EntityStore> store,
            Ref<EntityStore> actor,
            ComponentType<EntityStore, T> type
    ) {
        return store.getComponent(actor, type);
    }
}
