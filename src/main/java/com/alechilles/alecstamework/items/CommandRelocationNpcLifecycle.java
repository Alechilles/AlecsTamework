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
 * <p>Generated-world recovery runs synchronously after the terminal world's exact live-identity
 * location has been retired. Store shutdown does not dispatch ordinary NPC-removal callbacks, so
 * the world-removal boundary must submit Lost while complete snapshots are still cached.</p>
 */
final class CommandRelocationNpcLifecycle {
    private final Map<UUID, PendingRelocation> pendingByNpc;
    private final CommandRelocationNpcTracker npcTracker;
    private final CommandRelocationDropReporter dropReporter;
    private final ApplyScheduler applyScheduler;
    private final PendingRemover pendingRemover;
    private final AdmissionCanceller admissionCanceller;

    CommandRelocationNpcLifecycle(@Nonnull Map<UUID, Vector3d> lastKnownByNpc,
                                  @Nonnull Map<UUID, World> knownWorldByNpc,
                                  @Nonnull Map<UUID, PendingRelocation> pendingByNpc,
                                  @Nonnull CommandRelocationDropReporter dropReporter,
                                  @Nonnull ApplyScheduler applyScheduler,
                                  @Nonnull PendingRemover pendingRemover,
                                  @Nonnull AdmissionCanceller admissionCanceller) {
        this.pendingByNpc = Objects.requireNonNull(pendingByNpc, "pendingByNpc");
        this.npcTracker = new CommandRelocationNpcTracker(lastKnownByNpc, knownWorldByNpc);
        this.dropReporter = Objects.requireNonNull(dropReporter, "dropReporter");
        this.applyScheduler = Objects.requireNonNull(applyScheduler, "applyScheduler");
        this.pendingRemover = Objects.requireNonNull(pendingRemover, "pendingRemover");
        this.admissionCanceller = Objects.requireNonNull(admissionCanceller, "admissionCanceller");
    }

    void onNpcAdded(@Nullable Ref<EntityStore> reference,
                    @Nullable Store<EntityStore> store) {
        CommandRelocationNpcTracker.TrackedNpc tracked = npcTracker.onAdded(reference, store);
        if (tracked == null || tracked.world() == null) {
            return;
        }
        // Population reconciliation runs after this observer and must publish ACTIVE first.
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
        CommandRelocationNpcTracker.WorldRemovalCandidate candidate =
                npcTracker.onRemoved(reference, reason, store, npcUuidHint);
        if (candidate != null) {
            dropReporter.reportWorldRemoval(candidate);
        }
    }

    void onWorldRemoved(@Nullable World world, long removedAtMs) {
        for (CommandRelocationNpcTracker.WorldRemovalCandidate candidate
                : npcTracker.markDeleteOnRemoveWorld(world, removedAtMs)) {
            PendingRelocation pending = pendingByNpc.get(candidate.npcUuid());
            if (pending != null && !pending.physicalMutationAttempted()
                    && pendingRemover.remove(candidate.npcUuid(), pending)) {
                pending.markCrossWorldTransferFinished();
                admissionCanceller.cancel(pending);
            }
            dropReporter.reportWorldRemoval(candidate);
            npcTracker.completeWorldRemoval(candidate.npcUuid());
        }
    }

    boolean isDeleteOnRemoveRecoveryPending(@Nullable UUID npcUuid) {
        return npcTracker.isWorldRemovalPending(npcUuid);
    }

    @FunctionalInterface
    interface ApplyScheduler {
        void schedule(@Nonnull World world, @Nonnull UUID npcUuid);
    }

    @FunctionalInterface
    interface PendingRemover {
        boolean remove(@Nonnull UUID npcUuid, @Nonnull PendingRelocation pending);
    }

    @FunctionalInterface
    interface AdmissionCanceller {
        void cancel(@Nonnull PendingRelocation pending);
    }
}
