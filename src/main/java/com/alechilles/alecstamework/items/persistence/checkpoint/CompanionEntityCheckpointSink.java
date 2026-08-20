package com.alechilles.alecstamework.items.persistence.checkpoint;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Receives one immutable holder capture after world-thread serialization. */
@FunctionalInterface
public interface CompanionEntityCheckpointSink {
    CompanionEntityCheckpointSink IGNORE = capture ->
            CompletableFuture.completedFuture(null);

    CompletionStage<Void> publish(CompanionEntityCheckpointCapture capture);
}
