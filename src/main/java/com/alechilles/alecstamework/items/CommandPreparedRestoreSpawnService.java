package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.ownership.CompanionSpawnAdmissionRequest;
import com.alechilles.alecstamework.ownership.CompanionSpawnPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.CompanionSpawnPreparationResult;
import com.alechilles.alecstamework.ownership.PreparedCompanionSpawnBatch;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Schedules one dead/lost replacement through the shared pre-add population authority. */
final class CommandPreparedRestoreSpawnService {
    boolean schedule(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull NPCPlugin npcPlugin,
            int roleIndex,
            @Nonnull Vector3d position,
            @Nonnull Rotation3f rotation,
            @Nonnull CompanionSpawnAdmissionRequest request,
            @Nonnull Callbacks callbacks
    ) {
        Objects.requireNonNull(callbacks, "callbacks");
        CompanionSpawnPopulationAdmissionService admission = resolveAdmissionService();
        if (admission == null) {
            callbacks.onDenied("spawn-population-authority-unavailable");
            return true;
        }
        admission.prepareAsync(request).whenComplete((preparation, failure) -> dispatch(
                world,
                () -> apply(world, store, npcPlugin, roleIndex, position, rotation,
                        admission, preparation, failure, callbacks),
                () -> cancelPrepared(admission, preparation)
        ));
        return true;
    }

    /** Applies a population reservation that was durably held before a paid cost was consumed. */
    boolean schedulePrepared(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull NPCPlugin npcPlugin,
            int roleIndex,
            @Nonnull Vector3d position,
            @Nonnull Rotation3f rotation,
            @Nonnull PreparedCompanionSpawnBatch batch,
            @Nonnull Callbacks callbacks
    ) {
        Objects.requireNonNull(callbacks, "callbacks");
        CompanionSpawnPopulationAdmissionService admission = resolveAdmissionService();
        if (admission == null) {
            callbacks.onDenied("spawn-population-authority-unavailable");
            return true;
        }
        dispatch(world, () -> {
            Store<EntityStore> liveStore = world.getEntityStore() == null
                    ? null : world.getEntityStore().getStore();
            if (liveStore == null) {
                admission.cancelRemainingAsync(batch, "paid-revival-store-unavailable");
                callbacks.onDenied("paid-revival-store-unavailable");
                return;
            }
            spawnPrepared(world, liveStore, npcPlugin, roleIndex, position, rotation,
                    admission, batch, callbacks);
        }, () -> admission.cancelRemainingAsync(batch, "paid-revival-world-unavailable"));
        return true;
    }

    private void apply(
            World world,
            Store<EntityStore> store,
            NPCPlugin npcPlugin,
            int roleIndex,
            Vector3d position,
            Rotation3f rotation,
            CompanionSpawnPopulationAdmissionService admission,
            @Nullable CompanionSpawnPreparationResult preparation,
            @Nullable Throwable failure,
            Callbacks callbacks
    ) {
        if (failure != null || preparation == null || !preparation.allowed()
                || preparation.preparedBatch() == null) {
            callbacks.onDenied(preparation == null
                    ? "spawn-population-prepare-failed"
                    : preparation.reason());
            return;
        }
        PreparedCompanionSpawnBatch batch = preparation.preparedBatch();
        Store<EntityStore> liveStore = world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        if (liveStore == null) {
            admission.cancelRemainingAsync(batch, "command-restore-store-unavailable");
            callbacks.onDenied("command-restore-store-unavailable");
            return;
        }
        spawnPrepared(world, liveStore, npcPlugin, roleIndex, position, rotation,
                admission, batch, callbacks);
    }

    private void spawnPrepared(
            World world, Store<EntityStore> liveStore, NPCPlugin npcPlugin, int roleIndex,
            Vector3d position, Rotation3f rotation,
            CompanionSpawnPopulationAdmissionService admission,
            PreparedCompanionSpawnBatch batch, Callbacks callbacks) {
        new CompanionPreparedSpawnService(admission).spawnAndCommit(
                world, liveStore, npcPlugin, roleIndex, position, rotation, batch, 0,
                new CompanionPreparedSpawnService.Callbacks() {
                    @Override
                    public boolean finalizeSource(CompanionPreparedSpawnService.SpawnedCompanion live) {
                        return callbacks.finalizeSource(live);
                    }

                    @Override
                    public void onSpawned(CompanionPreparedSpawnService.SpawnedCompanion live) {
                        callbacks.onSpawned(live);
                    }

                    @Override
                    public void onDenied(String reason) {
                        callbacks.onDenied(reason);
                    }

                    @Override
                    public void onDurabilityDegraded(String reason) {
                        callbacks.onDurabilityDegraded(reason);
                    }
                }
        );
    }

    @Nullable
    private static CompanionSpawnPopulationAdmissionService resolveAdmissionService() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null || plugin.getOwnerPopulationRuntime() == null
                ? null
                : plugin.getOwnerPopulationRuntime().companionSpawnAdmissionService();
    }

    private static void cancelPrepared(
            CompanionSpawnPopulationAdmissionService admission,
            @Nullable CompanionSpawnPreparationResult preparation
    ) {
        if (preparation != null && preparation.preparedBatch() != null) {
            admission.cancelRemainingAsync(preparation.preparedBatch(), "command-restore-world-unavailable");
        }
    }

    private static void dispatch(World world, Runnable task, Runnable rejected) {
        LeaseBoundWorldDispatcher.execute(world, task, rejected);
    }

    interface Callbacks {
        default boolean finalizeSource(@Nonnull CompanionPreparedSpawnService.SpawnedCompanion live) {
            return true;
        }

        void onSpawned(@Nonnull CompanionPreparedSpawnService.SpawnedCompanion live);

        void onDenied(@Nonnull String reason);

        default void onDurabilityDegraded(@Nonnull String reason) {
        }
    }
}
