package com.alechilles.alecstamework.api;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Receives durable successful companion activities from the Tamework feed. */
@FunctionalInterface
public interface SuccessfulActivityConsumer {
    /**
     * Applies an activity. The feed advances the consumer checkpoint only for {@link
     * ActivityConsumeResult#APPLIED} or {@link ActivityConsumeResult#DUPLICATE}.
     */
    @Nonnull
    CompletionStage<ActivityConsumeResult> consume(@Nonnull SuccessfulActivityView activity);
}
