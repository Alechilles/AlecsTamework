package com.alechilles.alecstamework.items.persistence.checkpoint;

/** Receives one immutable holder capture after world-thread serialization. */
@FunctionalInterface
public interface CompanionEntityCheckpointSink {
    CompanionEntityCheckpointSink IGNORE = capture -> {
    };

    void publish(CompanionEntityCheckpointCapture capture);
}
