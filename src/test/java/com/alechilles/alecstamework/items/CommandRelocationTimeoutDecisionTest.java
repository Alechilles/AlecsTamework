package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for timeout terminality when the planned UUID remains visibly live. */
class CommandRelocationTimeoutDecisionTest {

    @Test
    void exhaustedSameWorldMoveCancelsInsteadOfCommittingDestination() {
        assertEquals(
                CommandRelocationTimeoutDecision.Outcome.CANCEL_CONFIRMED_SAME_WORLD,
                CommandRelocationTimeoutDecision.decide(true, true, true, false, false)
        );
    }

    @Test
    void exhaustedUnobservedSameWorldMoveRemainsUnloaded() {
        assertEquals(
                CommandRelocationTimeoutDecision.Outcome.COMMIT_UNCONFIRMED_AS_UNLOADED,
                CommandRelocationTimeoutDecision.decide(true, true, false, false, false)
        );
    }

    @Test
    void exhaustedCrossWorldMoveRemainsConservative() {
        assertEquals(
                CommandRelocationTimeoutDecision.Outcome.DROP_UNCONFIRMED_TRANSFER,
                CommandRelocationTimeoutDecision.decide(true, true, false, true, false)
        );
        assertEquals(
                CommandRelocationTimeoutDecision.Outcome.DROP_UNCONFIRMED_TRANSFER,
                CommandRelocationTimeoutDecision.decide(true, true, true, true, true)
        );
    }

    @Test
    void nonTerminalRetryAndUnclaimedDropRemainUnchanged() {
        assertEquals(
                CommandRelocationTimeoutDecision.Outcome.RETRY,
                CommandRelocationTimeoutDecision.decide(false, true, true, false, false)
        );
        assertEquals(
                CommandRelocationTimeoutDecision.Outcome.DROP_RETRY_EXHAUSTED,
                CommandRelocationTimeoutDecision.decide(true, false, false, false, false)
        );
    }
}
