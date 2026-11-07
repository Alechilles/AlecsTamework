package com.alechilles.alecstamework.testsupport;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import sun.misc.Unsafe;

/**
 * A real engine ECS in a plain test JVM: a fresh {@link ComponentRegistry}, the components and
 * systems a test registers, one {@link Store}, and {@link Store#tick} with a fixed step. No
 * server, world, assets or plugin boot, so a test runs the system's real query, archetype chunks
 * and command-buffer drain in milliseconds.
 *
 * <p>Register every component and system first; the store is made on the first spawn or tick.
 * Systems must take their component types through their constructor (or from a static the test
 * fills): a system that reads {@code World}, chunk stores, asset maps or players, or that depends
 * on vanilla system groups, needs a live server (Rune_MCP rig scenarios) instead.</p>
 *
 * <p>Built-in types reached through {@code EntityModule.get()} (such as
 * {@code Dirty.getComponentType()}) are served by {@link #entityModuleType}, which installs a
 * stand-in {@code EntityModule} for the fixture's lifetime and restores the real static on close.
 * It sets private engine fields by name, so a renamed field fails loudly here.</p>
 */
public final class EcsFixture implements AutoCloseable {
    /** One server tick at about 30 TPS. */
    public static final float TICK_SECONDS = 1f / 30f;

    private final ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
    @Nullable private Store<EntityStore> store;
    @Nullable private EntityModule module;
    @Nullable private Object previousModule;
    private boolean moduleInstalled;

    @Nonnull
    public <T extends Component<EntityStore>> ComponentType<EntityStore, T> component(
            @Nonnull Class<? super T> type, @Nonnull Supplier<T> supplier) {
        requireNotStarted();
        return registry.registerComponent(type, supplier);
    }

    public void system(@Nonnull ISystem<EntityStore> system) {
        requireNotStarted();
        registry.registerSystem(system);
    }

    /**
     * Serves {@code type} from {@code EntityModule.get()} through the engine field {@code field}
     * (for example {@code "dirtyComponentType"} for {@code Dirty.getComponentType()}).
     */
    public void entityModuleType(@Nonnull String field, @Nonnull ComponentType<EntityStore, ?> type) {
        try {
            if (!moduleInstalled) {
                Field instance = field(EntityModule.class, "instance");
                previousModule = instance.get(null);
                module = (EntityModule) unsafe().allocateInstance(EntityModule.class);
                instance.set(null, module);
                moduleInstalled = true;
            }
            field(EntityModule.class, field).set(module, type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EntityModule has no field '" + field + "' in this engine version", e);
        }
    }

    @Nonnull
    public Store<EntityStore> store() {
        if (store == null) {
            store = registry.addStore(new EntityStore(null), EmptyResourceStorage.get());
        }
        return store;
    }

    /** Adds an entity built by {@code components} (call {@code holder.addComponent(type, value)}). */
    @Nonnull
    public Ref<EntityStore> spawn(@Nonnull Consumer<Holder<EntityStore>> components) {
        Holder<EntityStore> holder = registry.newHolder();
        components.accept(holder);
        return store().addEntity(holder, AddReason.SPAWN);
    }

    /** Runs {@code ticks} store ticks (systems, then the command buffers they wrote). */
    public void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            store().tick(TICK_SECONDS);
        }
    }

    @Nullable
    public <T extends Component<EntityStore>> T get(@Nonnull Ref<EntityStore> ref,
                                                    @Nonnull ComponentType<EntityStore, T> type) {
        return store().getComponent(ref, type);
    }

    @Override
    public void close() throws Exception {
        try {
            if (store != null) {
                registry.removeStore(store);
            }
            registry.shutdown();    // stops the registry's holder-reference worker thread
        } finally {
            if (moduleInstalled) {
                field(EntityModule.class, "instance").set(null, previousModule);
            }
        }
    }

    private void requireNotStarted() {
        if (store != null) {
            throw new IllegalStateException("register components and systems before the first spawn or tick");
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }
}
