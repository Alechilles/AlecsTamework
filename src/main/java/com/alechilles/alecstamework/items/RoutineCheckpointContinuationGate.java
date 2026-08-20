package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

/**
 * Prevents an older successful profile continuation from replacing a newer
 * successful profile's routine checkpoint.
 */
final class RoutineCheckpointContinuationGate {
    private final ConcurrentHashMap<UUID, AliasState> states =
            new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /** Registers one routine continuation before its profile stage attaches. */
    @Nonnull
    Ticket register(@Nonnull UUID alias) {
        Objects.requireNonNull(alias, "alias");
        long token = sequence.incrementAndGet();
        AliasState state = states.compute(alias, (ignored, current) -> {
            AliasState retained = current == null ? new AliasState() : current;
            retained.register();
            return retained;
        });
        return new Ticket(alias, token, state);
    }

    /**
     * Returns true when this is the newest profile known to have published.
     * A newer accepted profile that later fails does not suppress this one.
     */
    boolean markProfilePublished(@Nonnull Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        return ticket.state.markPublished(ticket.sequence);
    }

    /** Releases one continuation after it publishes, skips, or fails. */
    void complete(@Nonnull Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (ticket.state.complete()) {
            states.remove(ticket.alias, ticket.state);
        }
    }

    /** Immutable registration retained by one profile continuation. */
    static final class Ticket {
        private final UUID alias;
        private final long sequence;
        private final AliasState state;

        private Ticket(UUID alias, long sequence, AliasState state) {
            this.alias = alias;
            this.sequence = sequence;
            this.state = state;
        }
    }

    private static final class AliasState {
        private long newestPublishedSequence;
        private int continuations;

        private synchronized void register() {
            continuations++;
        }

        private synchronized boolean markPublished(long candidateSequence) {
            if (candidateSequence <= newestPublishedSequence) {
                return false;
            }
            newestPublishedSequence = candidateSequence;
            return true;
        }

        private synchronized boolean complete() {
            continuations--;
            return continuations == 0;
        }
    }
}
