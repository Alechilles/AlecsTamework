package com.hypixel.hytale.component;

import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Minimal in-memory component accessor for adapter tests that need valid-looking entity refs.
 *
 * <p>The production ECS store constructor is package-private, so this test helper lives beside it
 * and overrides only the read operations exercised by the damage adapters.</p>
 */
public final class TestEntityComponentStore extends Store<EntityStore> implements AutoCloseable {
    private final Map<Ref<EntityStore>, Map<ComponentType<EntityStore, ?>, Component<EntityStore>>> components =
            new IdentityHashMap<>();

    public TestEntityComponentStore(@Nonnull EntityStore externalData) {
        super(new ComponentRegistry<>(), 0, externalData, null);
    }

    @Nonnull
    public Ref<EntityStore> createReference() {
        Ref<EntityStore> reference = new AlwaysValidRef(this);
        components.put(reference, new IdentityHashMap<>());
        return reference;
    }

    public <T extends Component<EntityStore>> void put(@Nonnull Ref<EntityStore> reference,
                                                        @Nonnull ComponentType<EntityStore, T> type,
                                                        @Nullable T component) {
        Map<ComponentType<EntityStore, ?>, Component<EntityStore>> byType = components.get(reference);
        if (byType == null) {
            throw new IllegalArgumentException("Unknown test entity reference.");
        }
        if (component == null) {
            byType.remove(type);
        } else {
            byType.put(type, component);
        }
    }

    @Nonnull
    @Override
    @SuppressWarnings("unchecked")
    public Archetype<EntityStore> getArchetype(
            @Nonnull Ref<EntityStore> reference
    ) {
        Map<ComponentType<EntityStore, ?>, Component<EntityStore>> byType =
                components.get(reference);
        if (byType == null) {
            throw new IllegalArgumentException(
                    "Unknown test entity reference."
            );
        }
        ComponentType<EntityStore, ?>[] types = byType.keySet().toArray(
                ComponentType[]::new
        );
        return Archetype.of(types);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Component<EntityStore>> T getComponent(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull ComponentType<EntityStore, T> type) {
        Map<ComponentType<EntityStore, ?>, Component<EntityStore>> byType = components.get(reference);
        return byType == null ? null : (T) byType.get(type);
    }

    @Override
    public void close() {
        getRegistry().shutdown();
    }

    private static final class AlwaysValidRef extends Ref<EntityStore> {
        private AlwaysValidRef(@Nonnull Store<EntityStore> store) {
            super(store);
        }

        @Override
        public boolean isValid() {
            return true;
        }
    }
}
