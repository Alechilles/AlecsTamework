package com.alechilles.alecstamework.items;

import java.util.Objects;

/**
 * Coordinates one bonded-revive inventory debit with durable receipt state.
 *
 * <p>The debit boundary must be all-or-nothing. Any observed partial result is
 * treated as a charged recovery that must compensate before another debit.</p>
 */
final class BondedCompanionChargeCoordinator {

    Outcome find(Attempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        return switch (attempt.state()) {
            case CHARGED -> Outcome.charged(true);
            case DEBITED, COMPENSATING, COMPENSATED, CONFLICT ->
                    Outcome.recoveryPending();
            case ABSENT, PREPARED -> Outcome.unavailable();
        };
    }

    Outcome consume(Attempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        State state = attempt.state();
        if (state == State.CHARGED) return Outcome.charged(true);
        if (state == State.DEBITED || state == State.COMPENSATING
                || state == State.COMPENSATED || state == State.CONFLICT) {
            return compensate(attempt);
        }
        if (state == State.ABSENT && !attempt.installPending()) {
            return Outcome.unavailable();
        }
        if (attempt.state() != State.PREPARED) {
            return Outcome.recoveryPending();
        }
        DebitResult debit = attempt.debitAtomically();
        if (debit == DebitResult.NONE) {
            attempt.releasePrepared();
            return Outcome.unavailable();
        }
        if (debit == DebitResult.PARTIAL) {
            return compensate(attempt);
        }
        if (attempt.markCharged()) return Outcome.charged(false);
        return compensate(attempt);
    }

    private Outcome compensate(Attempt attempt) {
        return attempt.refund()
                ? Outcome.unavailable() : Outcome.recoveryPending();
    }

    enum State {
        ABSENT,
        PREPARED,
        DEBITED,
        CHARGED,
        COMPENSATING,
        COMPENSATED,
        CONFLICT
    }

    enum DebitResult {
        NONE,
        EXACT,
        PARTIAL
    }

    enum Status {
        UNAVAILABLE,
        CHARGED,
        RECOVERY_PENDING
    }

    record Outcome(Status status, boolean replayed) {
        Outcome {
            Objects.requireNonNull(status, "status");
            if (status != Status.CHARGED && replayed) {
                throw new IllegalArgumentException(
                        "Only a charged outcome can be replayed");
            }
        }

        static Outcome unavailable() {
            return new Outcome(Status.UNAVAILABLE, false);
        }

        static Outcome charged(boolean replayed) {
            return new Outcome(Status.CHARGED, replayed);
        }

        static Outcome recoveryPending() {
            return new Outcome(Status.RECOVERY_PENDING, false);
        }
    }

    interface Attempt {
        State state();

        boolean installPending();

        DebitResult debitAtomically();

        boolean markCharged();

        boolean refund();

        boolean releasePrepared();
    }
}
