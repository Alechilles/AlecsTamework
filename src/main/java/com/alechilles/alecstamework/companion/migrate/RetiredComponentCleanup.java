package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.flow.CompanionOwnershipSystems;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Strips the entity components that 3.x and 4.x saved and 5.0 no longer uses (plan 7 R15, spec
 * 12.4). The components stay registered with their codecs so old saves decode; this removes them
 * from each entity as it loads, and the change makes the entity save without them.
 *
 * <p>Cost: every query here is on the retired component types, so only entities that still carry
 * one are visited. A world that never ran 3.x or 4.x has none and pays nothing.</p>
 *
 * <p>Ordering: {@link CompanionOwnershipSystems.OnAdd} and the startup admission pass read the
 * retired projection identity from the store to match an old body to its imported record (R14).
 * {@link OnAdd} therefore runs after that system and strips through the callback's command
 * buffer, and {@link #stripLoadedWorlds} must be called after the startup admission pass was
 * queued. The projection identity gets one more rule ({@link #stripsProjection}).</p>
 *
 * <p>The retired chunk component {@code TameworkCoopCaptureReceipts} is not handled here: the
 * coop schedule system strips it from loaded coop blocks through its own command buffer.</p>
 */
public final class RetiredComponentCleanup {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final List<ComponentType<EntityStore, ?>> retired;
    private final ComponentType<EntityStore, TameworkProjectionIdentityComponent> projection;
    private final ComponentType<EntityStore, TameworkCompanionComponent> stamp;
    private final boolean legacyMatching;
    private final Query<EntityStore> query;

    /**
     * @param retired        retired types with no reader left; removed wherever they are found
     * @param projection     the retired projection identity type; removed once its body is stamped
     * @param stamp          the companion stamp type
     * @param legacyMatching true on a world imported from 3.x or 4.x, where old bodies are still
     *                       matched by their projection identity
     */
    @SuppressWarnings("unchecked")
    public RetiredComponentCleanup(@Nonnull List<ComponentType<EntityStore, ?>> retired,
                                   @Nonnull ComponentType<EntityStore, TameworkProjectionIdentityComponent> projection,
                                   @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stamp,
                                   boolean legacyMatching) {
        this.retired = List.copyOf(retired);
        this.projection = Objects.requireNonNull(projection, "projection");
        this.stamp = Objects.requireNonNull(stamp, "stamp");
        this.legacyMatching = legacyMatching;
        List<Query<EntityStore>> any = new ArrayList<>(this.retired);
        any.add(projection);
        this.query = Query.or(any.toArray(new Query[0]));
    }

    /**
     * Whether the projection identity may be removed from a body. On an imported world an
     * unstamped body is matched again every time it loads (a body left alone because its record
     * was unreadable or another body held the profile), and the projection identity is the first
     * thing that match reads, so it stays until the body carries a stamp. A body stamped while it
     * loads keeps the marker for that session (the stamp is applied after the add callbacks) and
     * loses it on its next load; nothing reads the marker of a stamped body. Where no matching
     * runs, nothing reads it and it goes at once.
     */
    static boolean stripsProjection(boolean stamped, boolean legacyMatching) {
        return stamped || !legacyMatching;
    }

    /** Queues removals for every retired component on {@code ref}; returns how many. */
    private int strip(Ref<EntityStore> ref, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        if (ref == null || !ref.isValid()) {
            return 0;
        }
        int stripped = 0;
        for (ComponentType<EntityStore, ?> type : retired) {
            if (store.getComponent(ref, type) != null) {
                buffer.tryRemoveComponent(ref, type);
                stripped++;
            }
        }
        if (store.getComponent(ref, projection) != null
                && stripsProjection(store.getComponent(ref, stamp) != null, legacyMatching)) {
            buffer.tryRemoveComponent(ref, projection);
            stripped++;
        }
        return stripped;
    }

    /** The entity systems to register, after {@link CompanionOwnershipSystems.OnAdd}. */
    @Nonnull
    public RefSystem<EntityStore> addSystem() {
        return new OnAdd(this);
    }

    /**
     * Strips entities that were in a store before the systems registered. Queues one pass on each
     * world's thread and returns at once. Call it after the companion startup admission pass was
     * queued: world tasks run in order, and that pass reads the projection identity.
     */
    public void stripLoadedWorlds(@Nonnull Collection<World> worlds) {
        for (World world : worlds) {
            if (world == null) {
                continue;
            }
            try {
                world.execute(() -> stripLoaded(world));
            } catch (RuntimeException notAccepting) {
                // The world stopped; its entities are stripped when they load again.
            }
        }
    }

    /** World thread only. */
    private void stripLoaded(World world) {
        try {
            Store<EntityStore> store = world.getEntityStore() == null ? null : world.getEntityStore().getStore();
            if (store == null) {
                return;
            }
            int[] stripped = new int[1];
            store.forEachChunk(query, (chunk, buffer) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    stripped[0] += strip(chunk.getReferenceTo(i), store, buffer);
                }
            });
            if (stripped[0] > 0) {
                LOGGER.at(Level.INFO).log("Removed %d retired Tamework components from loaded entities in world %s",
                        stripped[0], world.getName());
            }
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log(
                    "Could not remove retired Tamework components in world %s", world.getName());
        }
    }

    /** An entity that carries a retired component entered a store. */
    private static final class OnAdd extends RefSystem<EntityStore> {
        private final RetiredComponentCleanup cleanup;
        // The ownership add system reads the projection identity of the same entity first.
        private final Set<Dependency<EntityStore>> dependencies =
                Set.of(new SystemDependency<>(Order.AFTER, CompanionOwnershipSystems.OnAdd.class));

        private OnAdd(RetiredComponentCleanup cleanup) {
            this.cleanup = cleanup;
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            cleanup.strip(ref, store, buffer);
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
        }

        @Override
        @Nonnull
        public Query<EntityStore> getQuery() {
            return cleanup.query;
        }

        @Override
        @Nonnull
        public Set<Dependency<EntityStore>> getDependencies() {
            return dependencies;
        }
    }
}
