package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.live.CompanionBodySystem;
import com.alechilles.alecstamework.companion.live.FenceAction;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Collection;
import java.util.Objects;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One pass per world, run once right after the companion systems register. Worlds start loading
 * before Tamework starts, and the engine does not replay {@code onEntityAdded} for systems
 * registered later, so bodies already in a store are fenced and registered here with the same
 * logic the add systems use. Entities added after registration go through the systems as usual.
 *
 * <p>On a world imported from 3.x or 4.x this pass also matches the old bodies already loaded
 * (plan 7 R14), which reads the retired projection identity component: a startup strip of
 * retired components must run after this pass.</p>
 */
public final class CompanionStartupAdmission {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final CompanionBodySystem bodies;
    private final CompanionBodyLifecycle lifecycle;
    private final LoadedBodies<Ref<EntityStore>> loaded;
    private final ComponentType<EntityStore, TameworkCompanionComponent> stampType;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;
    @Nullable
    private final ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType;

    public CompanionStartupAdmission(@Nonnull CompanionBodySystem bodies, @Nonnull CompanionBodyLifecycle lifecycle,
                                     @Nonnull LoadedBodies<Ref<EntityStore>> loaded,
                                     @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stampType,
                                     @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
                                     @Nonnull ComponentType<EntityStore, TameworkTamedComponent> tamedType,
                                     @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent>
                                             projectionType) {
        this.bodies = Objects.requireNonNull(bodies, "bodies");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.stampType = Objects.requireNonNull(stampType, "stampType");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.tamedType = Objects.requireNonNull(tamedType, "tamedType");
        this.projectionType = projectionType;
    }

    /** Queues one pass on each world's thread; returns at once. */
    public void admitLoadedWorlds(@Nonnull Collection<World> worlds) {
        for (World world : worlds) {
            if (world == null) {
                continue;
            }
            try {
                world.execute(() -> admit(world));
            } catch (RuntimeException e) {
                LOGGER.at(Level.WARNING).withCause(e).log(
                        "Could not queue the companion startup pass for world %s", world.getName());
            }
        }
    }

    /** World thread only. */
    private void admit(World world) {
        try {
            Store<EntityStore> store = world.getEntityStore() == null ? null : world.getEntityStore().getStore();
            if (store == null) {
                return;
            }
            // NPCEntity's type is unavailable during plugin startup on some server load orders.
            Query<EntityStore> unstamped = CompanionOwnershipSystems.unstamped(
                    Objects.requireNonNull(NPCEntity.getComponentType(), "NPCEntity component type"),
                    ownerType, tamedType, stampType, projectionType);
            int[] counts = new int[3]; // admitted, fenced, tamed
            store.forEachChunk(stampType, (chunk, buffer) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    Ref<EntityStore> ref = chunk.getReferenceTo(i);
                    TameworkCompanionComponent stamp = store.getComponent(ref, stampType);
                    // Added after the systems registered: the body system already handled it.
                    if (stamp == null || stamp.getProfileId() == null
                            || ref.equals(loaded.get(stamp.getProfileId()))) {
                        continue;
                    }
                    FenceAction action = bodies.admit(ref, store, buffer);
                    if (action == FenceAction.ACCEPT || action == FenceAction.ACCEPT_AND_RAISE
                            || action == FenceAction.ADOPT) {
                        counts[0]++;
                    } else if (action != null) {
                        counts[1]++;
                    }
                }
            });
            store.forEachChunk(unstamped, (chunk, buffer) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    if (CompanionOwnershipSystems.OnAdd.admit(lifecycle, chunk.getReferenceTo(i), store, buffer)) {
                        counts[2]++;
                    }
                }
            });
            LOGGER.at(Level.INFO).log("Companion startup pass for world %s: %d bodies admitted, %d fenced, "
                    + "%d owned animals registered", world.getName(), counts[0], counts[1], counts[2]);
        } catch (RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log(
                    "Companion startup pass failed for world %s", world.getName());
        }
    }
}
