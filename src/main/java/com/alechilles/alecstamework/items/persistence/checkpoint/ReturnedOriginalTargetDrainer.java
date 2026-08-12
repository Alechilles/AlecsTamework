package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.compat.HytaleChunkAccess;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nullable;

/** Drains and rolls back a generated target before preferring an original. */
final class ReturnedOriginalTargetDrainer {
    CompletionStage<DrainedTarget> drain(
            Universe universe,
            ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan,
            LoadedNpcIdentityIndex.Probe probe
    ) {
        if (probe.status() == LoadedNpcIdentityIndex.ProbeStatus.ABSENT) {
            return CompletableFuture.completedFuture(DrainedTarget.NONE);
        }
        if (probe.status()
                != LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION
                || probe.worldNames().size() != 1) {
            return failed("returned_original_target_conflict");
        }
        World world = universe == null ? null
                : universe.getWorld(probe.worldNames().getFirst());
        if (world == null) {
            return failed("returned_original_target_world_missing");
        }
        CompletableFuture<DrainedTarget> completion =
                new CompletableFuture<>();
        LeaseBoundWorldDispatcher.execute(
                world,
                () -> drain(world, plan, completion),
                () -> completion.completeExceptionally(
                        new IllegalStateException(
                                "returned_original_target_dispatch_failed"
                        )
                )
        );
        return completion;
    }

    CompletionStage<Void> rollback(DrainedTarget drained) {
        if (drained == null || drained == DrainedTarget.NONE) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        LeaseBoundWorldDispatcher.execute(
                drained.world(),
                () -> rollback(drained, completion),
                () -> completion.completeExceptionally(
                        new IllegalStateException(
                                "returned_original_target_rollback_failed"
                        )
                )
        );
        return completion;
    }

    private void drain(
            World world,
            ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan,
            CompletableFuture<DrainedTarget> completion
    ) {
        try {
            Ref<EntityStore> reference = world.getEntityRef(
                    plan.checkpoint().alias().value()
            );
            Store<EntityStore> store = store(world);
            if (reference == null || !reference.isValid() || store == null) {
                completion.complete(DrainedTarget.NONE);
                return;
            }
            TransformComponent transform = store.getComponent(
                    reference, TransformComponent.getComponentType()
            );
            if (transform == null) {
                throw new IllegalStateException(
                        "returned_original_target_transform_missing"
                );
            }
            HytaleChunkAccess.markNeedsSaving(transform, store);
            Holder<EntityStore> holder = store.removeEntity(
                    reference, RemoveReason.UNLOAD
            );
            if (holder == null) {
                throw new IllegalStateException(
                        "returned_original_target_drain_failed"
                );
            }
            completion.complete(new DrainedTarget(world, holder));
        } catch (RuntimeException | LinkageError failure) {
            completion.completeExceptionally(failure);
        }
    }

    private void rollback(
            DrainedTarget drained,
            CompletableFuture<Void> completion
    ) {
        try {
            Store<EntityStore> store = store(drained.world());
            if (store == null) {
                throw new IllegalStateException(
                        "returned_original_target_store_missing"
                );
            }
            store.addEntity(drained.holder(), AddReason.LOAD);
            completion.complete(null);
        } catch (RuntimeException | LinkageError failure) {
            completion.completeExceptionally(failure);
        }
    }

    @Nullable
    private static Store<EntityStore> store(World world) {
        EntityStore external = world == null ? null : world.getEntityStore();
        return external == null ? null : external.getStore();
    }

    private static CompletionStage<DrainedTarget> failed(String code) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(code)
        );
    }

    record DrainedTarget(
            @Nullable World world,
            @Nullable Holder<EntityStore> holder
    ) {
        private static final DrainedTarget NONE =
                new DrainedTarget(null, null);
    }
}
