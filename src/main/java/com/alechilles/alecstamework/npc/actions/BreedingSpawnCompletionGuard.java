package com.alechilles.alecstamework.npc.actions;

import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Preserves an already-created offspring projection when best-effort initialization fails.
 *
 * <p>Once Hytale returns a live child reference and NPC, the birth has happened. Reclassifying
 * that birth as a technical zero-child outcome would restore the parents' cooldown and permit an
 * additional litter, so follow-up failures are reported without changing spawn success.</p>
 */
final class BreedingSpawnCompletionGuard {
    boolean complete(@Nonnull Runnable followUp,
                     @Nonnull Consumer<RuntimeException> failureSink) {
        Objects.requireNonNull(followUp, "followUp");
        Objects.requireNonNull(failureSink, "failureSink");
        try {
            followUp.run();
        } catch (RuntimeException exception) {
            try {
                failureSink.accept(exception);
            } catch (RuntimeException ignored) {
                // The child already exists; reporting cannot revoke that authoritative outcome.
            }
        }
        return true;
    }
}
