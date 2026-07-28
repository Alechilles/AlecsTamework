package com.hypixel.hytale.component;

import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.query.Query;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
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

    @Override
    public <T extends Component<EntityStore>> void putComponent(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull ComponentType<EntityStore, T> type,
            @Nonnull T component) {
        put(reference, type, component);
    }

    @Override
    public <T extends Component<EntityStore>> void removeComponent(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull ComponentType<EntityStore, T> type) {
        put(reference, type, null);
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
    public void forEachChunk(
            @Nonnull Query<EntityStore> query,
            @Nonnull BiConsumer<ArchetypeChunk<EntityStore>,
                    CommandBuffer<EntityStore>> consumer) {
        consumer.accept(new TestChunk(this,
                new ArrayList<>(components.keySet())), null);
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

    /** Read-only chunk projection for behavior tests that scan Query.any(). */
    private static final class TestChunk extends ArchetypeChunk<EntityStore> {
        private final TestEntityComponentStore store;
        private final List<Ref<EntityStore>> references;

        private TestChunk(TestEntityComponentStore store,
                          List<Ref<EntityStore>> references) {
            super(store, Archetype.of());
            this.store = store;
            this.references = List.copyOf(references);
        }

        @Override
        public int size() {
            return references.size();
        }

        @Override
        public Ref<EntityStore> getReferenceTo(int index) {
            return references.get(index);
        }

        @Override
        public <T extends Component<EntityStore>> T getComponent(
                int index, ComponentType<EntityStore, T> type) {
            return store.getComponent(references.get(index), type);
        }
    }
}
