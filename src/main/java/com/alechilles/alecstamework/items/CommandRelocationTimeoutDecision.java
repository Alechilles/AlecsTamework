package com.alechilles.alecstamework.items;

/** Classifies terminal relocation retries without conflating a visible NPC with a lost one. */
final class CommandRelocationTimeoutDecision {
    private CommandRelocationTimeoutDecision() {
    }

    static Outcome decide(boolean exhausted,
                          boolean admissionApplying,
                          boolean physicalMutationAttempted,
                          boolean liveNpcObservedOutsideDestination,
                          boolean crossWorldTransferAttempted,
                          boolean crossWorldDestinationInstalled) {
        if (!exhausted) {
            return Outcome.RETRY;
        }
        if (!admissionApplying || !physicalMutationAttempted) {
            return Outcome.DROP_AS_LOST;
        }
        if (liveNpcObservedOutsideDestination && !crossWorldDestinationInstalled) {
            return Outcome.CANCEL_CONFIRMED_SAME_WORLD;
        }
        return crossWorldTransferAttempted
                ? Outcome.COMMIT_UNCONFIRMED_AS_LOST
                : Outcome.COMMIT_UNCONFIRMED_AS_UNLOADED;
    }

    enum Outcome {
        RETRY,
        CANCEL_CONFIRMED_SAME_WORLD,
        COMMIT_UNCONFIRMED_AS_UNLOADED,
        COMMIT_UNCONFIRMED_AS_LOST,
        DROP_AS_LOST
    }
}
