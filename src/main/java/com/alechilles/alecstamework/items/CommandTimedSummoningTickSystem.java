package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One process-wide lease checkpoint sweep, regardless of how many world stores tick it. */
public final class CommandTimedSummoningTickSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 1_000L;
    private final AtomicLong nextSweepAtMs = new AtomicLong();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    @Nullable private volatile CommandTimedSummoningService service;

    public void install(@Nullable CommandTimedSummoningService service) {
        this.service = service;
        nextSweepAtMs.set(0L);
        inFlight.set(false);
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        CommandTimedSummoningService current = service;
        long nowMs = System.currentTimeMillis();
        long next = nextSweepAtMs.get();
        if (current == null || nowMs < next
                || !nextSweepAtMs.compareAndSet(next, nowMs + SWEEP_INTERVAL_MS)
                || !inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            current.tick(nowMs).whenComplete((ignored, failure) -> inFlight.set(false));
        } catch (RuntimeException | LinkageError failure) {
            inFlight.set(false);
        }
    }
}
