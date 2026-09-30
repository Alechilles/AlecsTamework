package com.alechilles.alecstamework.companion.live;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * What the body system reports to the flows (implemented in phase 3). All calls run in the
 * ECS callback on the owning world thread; implementations write entities only through the
 * command buffer and do no file I/O.
 */
public interface CompanionBodyCallbacks {
    /**
     * The body is the current holder. {@code raised} means its generation was newer than the
     * record's. With {@code raised} the implementation must move the record to {@code LIVE} with
     * this body's world, position and NPC UUID, not only raise the generation. It runs after the
     * index lock is released, so when two newer bodies arrive the last callback to run wins.
     */
    void onAccepted(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                    @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull TameworkCompanionComponent stamp, boolean raised);

    /** No record exists; create one from this owned, tamed body (spec 8.1). */
    void onAdopt(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                 @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull TameworkCompanionComponent stamp);

    /** The registered body for {@code profileId} is being removed (UNLOAD or REMOVE; spec 6.8 part 2). */
    void onLoadedBodyRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<EntityStore> store,
                             @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull UUID profileId);

    /**
     * A newer body was accepted for {@code profileId} and replaced {@code displacedBody} as the
     * registered body. The displaced body may belong to another world's store: implementations
     * remove it on its owning world thread via {@code world.execute} with stable ids, and never
     * directly from this callback's store unless it is the same store.
     */
    void onDisplaced(@Nonnull Ref<EntityStore> displacedBody, @Nonnull UUID profileId);

    /**
     * The fence returned {@link FenceAction#REMOVE} or {@link FenceAction#IGNORE} for a body of
     * {@code profileId}. A diagnostics seam only: the system has already queued any removal.
     */
    void onFenced(@Nonnull FenceAction action, @Nonnull UUID profileId);
}
