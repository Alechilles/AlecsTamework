package com.alechilles.alecstamework.items;

/** Classifies terminal relocation retries without inferring durable lifecycle state. */
final class CommandRelocationTimeoutDecision {
    private CommandRelocationTimeoutDecision() {
    }

    static Outcome decide(boolean exhausted,
                          boolean physicalMutationAttempted,
                          boolean liveNpcObservedOutsideDestination) {
        if (!exhausted) {
            return Outcome.RETRY;
        }
        if (!physicalMutationAttempted) {
            return Outcome.DROP_RETRY_EXHAUSTED;
        }
        if (liveNpcObservedOutsideDestination) {
            return Outcome.CANCEL_CONFIRMED_SAME_WORLD;
        }
        return Outcome.COMMIT_UNCONFIRMED_AS_UNLOADED;
    }

    enum Outcome {
        RETRY,
        CANCEL_CONFIRMED_SAME_WORLD,
        COMMIT_UNCONFIRMED_AS_UNLOADED,
        DROP_RETRY_EXHAUSTED
    }
}
