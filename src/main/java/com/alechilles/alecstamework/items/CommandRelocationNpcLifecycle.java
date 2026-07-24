package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Coordinates command-relocation work at NPC and world lifecycle boundaries.
 *
 * <p>This collaborator only tracks live routing and retry scheduling. Canonical dormant
 * transitions are authored separately from positive death or destructive-removal evidence.</p>
 */
final class CommandRelocationNpcLifecycle {
    private final Map<UUID, PendingRelocation> pendingByNpc;
    private final CommandRelocationNpcTracker npcTracker;
    private final ApplyScheduler applyScheduler;

    CommandRelocationNpcLifecycle(@Nonnull Map<UUID, Vector3d> lastKnownByNpc,
                                  @Nonnull Map<UUID, World> knownWorldByNpc,
                                  @Nonnull Map<UUID, PendingRelocation> pendingByNpc,
                                  @Nonnull ApplyScheduler applyScheduler) {
        this.pendingByNpc = Objects.requireNonNull(pendingByNpc, "pendingByNpc");
        this.npcTracker = new CommandRelocationNpcTracker(lastKnownByNpc, knownWorldByNpc);
        this.applyScheduler = Objects.requireNonNull(applyScheduler, "applyScheduler");
    }

    void onNpcAdded(@Nullable Ref<EntityStore> reference,
                    @Nullable Store<EntityStore> store) {
        CommandRelocationNpcTracker.TrackedNpc tracked = npcTracker.onAdded(reference, store);
        if (tracked == null || tracked.world() == null) {
            return;
        }
        PendingRelocation pending = pendingByNpc.get(tracked.npcUuid());
        if (pending != null
                && Objects.equals(pending.destinationWorldName, tracked.world().getName())) {
            applyScheduler.schedule(tracked.world(), tracked.npcUuid());
        }
    }

    void onNpcRemoved(@Nullable Ref<EntityStore> reference,
                      @Nullable RemoveReason reason,
                      @Nullable Store<EntityStore> store,
                      @Nullable UUID npcUuidHint) {
        npcTracker.onRemoved(reference, reason, store, npcUuidHint);
    }

    @FunctionalInterface
    interface ApplyScheduler {
        void schedule(@Nonnull World world, @Nonnull UUID npcUuid);
    }
}
