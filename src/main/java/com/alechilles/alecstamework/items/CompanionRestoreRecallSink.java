package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Turns a recall that cannot move a body into a restore from the snapshot (spec 8.5): a LIVE
 * companion in another world, or one whose body never appeared before the relocation wait ran out.
 *
 * <p>Only the recalling owner's companion is restored: {@link #recover} checks the owner itself,
 * and {@link #restoreNear}'s caller checks it first. {@link RestoreFlow} rechecks the record and
 * hands its entity work to the world threads itself, so both entry points may be called from any
 * thread. Results complete on whichever thread finishes the restore.
 */
public final class CompanionRestoreRecallSink implements ImportedRecallRecoverySink {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final RestoreFlow<Ref<EntityStore>> restoreFlow;
    private final CompanionQueries companions;

    public CompanionRestoreRecallSink(@Nonnull RestoreFlow<Ref<EntityStore>> restoreFlow,
                                      @Nonnull CompanionQueries companions) {
        this.restoreFlow = Objects.requireNonNull(restoreFlow, "restoreFlow");
        this.companions = Objects.requireNonNull(companions, "companions");
    }

    /**
     * Restores {@code profileId} at a placement already computed near its owner on the owner's
     * world thread. The caller has checked that the recalling player owns it.
     */
    @Nonnull
    CompletableFuture<RestoreFlow.Result> restoreNear(@Nonnull UUID profileId,
                                                      @Nonnull CompanionSpawnPlacement placement) {
        return restore(profileId, new RestoreFlow.Destination(placement.worldKey(), placement.x(), placement.y(),
                placement.z(), placement.yawRadians(), placement.pitchRadians()));
    }

    /**
     * Called once when an explicit recall's relocation ran out of time without touching the body.
     * The destination is the safe spot frozen near the owner when the recall was queued.
     */
    @Nonnull
    @Override
    public CompletionStage<RecoveryOutcome> recover(@Nonnull RecallFailure failure) {
        CompanionRecord record = resolve(failure.npcUuid());
        RecallDestination destination = failure.destination();
        if (record == null || destination == null || !failure.ownerUuid().equals(record.ownerUuid())) {
            return CompletableFuture.completedFuture(RecoveryOutcome.NONE);
        }
        return restore(record.profileId(), new RestoreFlow.Destination(destination.worldName(),
                destination.x(), destination.y(), destination.z(), 0f, 0f))
                .thenApply(result -> result == RestoreFlow.Result.RESTORED
                        ? RecoveryOutcome.RECOVERED : RecoveryOutcome.NONE);
    }

    private CompletableFuture<RestoreFlow.Result> restore(UUID profileId, RestoreFlow.Destination destination) {
        return restoreFlow.restore(profileId, RestoreRules.Reason.RECALL, destination)
                .whenComplete((result, error) -> {
                    if (error != null || result != RestoreFlow.Result.RESTORED) {
                        LOGGER.at(Level.INFO).log("Recall restore did not complete for profile=" + profileId
                                + ", world=" + destination.world() + ", result=" + result
                                + (error == null ? "" : ", error=" + error.getClass().getSimpleName()));
                    }
                });
    }

    /** A relocation is keyed by the row's NPC UUID; a row without a known body may carry another id. */
    @Nullable
    private CompanionRecord resolve(UUID npcUuid) {
        CompanionRecord record = companions.byNpcUuid(npcUuid);
        return record != null ? record : companions.get(npcUuid);
    }
}
