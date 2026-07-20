package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.universe.world.World;
import java.util.UUID;

/** Coordinates relocation retries and delegates terminal side effects to the owning orchestrator. */
final class CommandRelocationRetryCoordinator {
    private final CommandNpcRelocationService owner;
    private final CommandRelocationTimingPolicy timingPolicy;

    CommandRelocationRetryCoordinator(CommandNpcRelocationService owner,
                                      CommandRelocationTimingPolicy timingPolicy) {
        this.owner = owner;
        this.timingPolicy = timingPolicy;
    }

    void retry(World world, UUID npcUuid, PendingRelocation pending) {
        if (world == null || npcUuid == null || pending == null) {
            return;
        }
        if (pending.admissionApplying() && pending.physicalMutationAttempted()) {
            keepingAdmission(world, npcUuid, pending, false);
            return;
        }
        if (pending.admissionPrepared()) {
            owner.cancelAdmission(
                    pending, true, world, () -> retry(world, npcUuid, pending)
            );
            return;
        }
        if (!pending.admissionTransitionInProgress()) {
            keepingAdmission(world, npcUuid, pending, false);
        }
    }

    void afterLiveStateUnavailable(World world, UUID npcUuid, PendingRelocation pending) {
        pending.resetRelocationIssue();
        if (pending.admissionApplying() && pending.physicalMutationAttempted()) {
            keepingAdmission(world, npcUuid, pending, false);
        } else {
            retry(world, npcUuid, pending);
        }
    }

    void keepingAdmission(World world,
                          UUID npcUuid,
                          PendingRelocation pending,
                          boolean liveNpcObservedOutsideDestination) {
        long now = System.currentTimeMillis();
        long retryInterval = Math.max(250L, timingPolicy.retryIntervalMs());
        recordRetry(pending, now, retryInterval);
        CommandRelocationTimeoutDecision.Outcome outcome = CommandRelocationTimeoutDecision.decide(
                exhausted(pending, now),
                pending.admissionApplying(),
                pending.physicalMutationAttempted(),
                liveNpcObservedOutsideDestination,
                pending.crossWorldTransferAttempted(),
                pending.crossWorldDestinationInstalled()
        );
        if (finishTerminal(outcome, world, npcUuid, pending, now)) {
            return;
        }
        owner.logRetryProgress(pending, now);
        owner.requestChunksForPending(world, pending);
        owner.scheduleTryApply(world, npcUuid, retryInterval);
    }

    private void recordRetry(PendingRelocation pending, long now, long retryInterval) {
        if (now - pending.lastRetryCountedAtMs >= retryInterval) {
            pending.retryAttempts++;
            pending.lastRetryCountedAtMs = now;
        }
    }

    private boolean exhausted(PendingRelocation pending, long now) {
        return now - pending.queuedAtMs > timingPolicy.maxWaitMs()
                || pending.retryAttempts > timingPolicy.maxRetryAttempts();
    }

    private boolean finishTerminal(CommandRelocationTimeoutDecision.Outcome outcome,
                                   World world,
                                   UUID npcUuid,
                                   PendingRelocation pending,
                                   long now) {
        return switch (outcome) {
            case CANCEL_CONFIRMED_SAME_WORLD -> {
                owner.cancelObservedSameWorldRelocation(world, npcUuid, pending);
                yield true;
            }
            case COMMIT_UNCONFIRMED_AS_LOST -> {
                owner.commitUnconfirmedRelocationAsLost(world, npcUuid, pending, now);
                yield true;
            }
            case COMMIT_UNCONFIRMED_AS_UNLOADED -> {
                owner.commitUnconfirmedRelocationAsUnloaded(world, npcUuid, pending);
                yield true;
            }
            case DROP_AS_LOST -> {
                owner.dropPendingAsLost(npcUuid, pending, now);
                yield true;
            }
            case RETRY -> false;
        };
    }
}
