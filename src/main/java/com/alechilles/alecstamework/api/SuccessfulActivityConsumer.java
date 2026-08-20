package com.alechilles.alecstamework.api;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Receives durable successful companion activities from the Tamework feed.
 *
 * <p>The feed calls one consumer serially and in global-sequence order. The callback can run on
 * any platform executor and has no game-loop or thread-affinity guarantee. A callback must return
 * a non-null stage with a non-null result. A synchronous throw, null stage or result, exceptional
 * completion, or completion after the coordinator's bounded timeout means {@link
 * ActivityConsumeResult#RETRY}; the failed sequence keeps its checkpoint and later records wait.
 */
@FunctionalInterface
public interface SuccessfulActivityConsumer {
    /**
     * Applies an activity. The feed advances the consumer checkpoint only for {@link
     * ActivityConsumeResult#APPLIED} or {@link ActivityConsumeResult#DUPLICATE}.
     */
    @Nonnull
    CompletionStage<ActivityConsumeResult> consume(@Nonnull SuccessfulActivityView activity);
}
