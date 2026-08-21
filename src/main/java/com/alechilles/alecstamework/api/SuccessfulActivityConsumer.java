package com.alechilles.alecstamework.api;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Receives live successful companion activities from the Tamework feed.
 *
 * <p>The feed invokes each active consumer in publish order when possible.
 * The callback can run on the publishing thread and has no game-loop or
 * thread-affinity guarantee. The returned stage is observed for compatibility
 * with persistence-backed consumers, but the live feed does not checkpoint or
 * retry it.</p>
 */
@FunctionalInterface
public interface SuccessfulActivityConsumer {
    /** Applies an activity and may report its local handling result. */
    @Nonnull
    CompletionStage<ActivityConsumeResult> consume(@Nonnull SuccessfulActivityView activity);
}
