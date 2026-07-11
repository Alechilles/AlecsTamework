package com.alechilles.alecstamework.npc.breeding;

import java.util.UUID;
import javax.annotation.Nonnull;

/** Schedules a delayed breeding callback using only its stable job identifier. */
@FunctionalInterface
public interface BreedingJobScheduler {
    void schedule(@Nonnull UUID jobId, long delayMs);
}
