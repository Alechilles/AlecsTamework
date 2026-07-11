package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopRuntimeSweepOrchestrator.SweepOutcome;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** One-second chunk-store tick shell for the decomposed managed-coop runtime. */
public final class ManagedCoopRuntimeSystem extends TickingSystem<ChunkStore> {
    private static final long SWEEP_INTERVAL_MS = 1_000L;

    private final ManagedCoopRuntimeSweepOrchestrator orchestrator;
    private final SweepObserver observer;
    private final LongSupplier wallClock;
    private final StoreScopedState<TickState> states = new StoreScopedState<>(TickState::new);

    public ManagedCoopRuntimeSystem(@Nonnull ManagedCoopRuntimeSweepOrchestrator orchestrator) {
        this(orchestrator, SweepObserver.noop(), System::currentTimeMillis);
    }

    public ManagedCoopRuntimeSystem(@Nonnull ManagedCoopRuntimeSweepOrchestrator orchestrator,
                                    @Nonnull SweepObserver observer) {
        this(orchestrator, observer, System::currentTimeMillis);
    }

    ManagedCoopRuntimeSystem(@Nonnull ManagedCoopRuntimeSweepOrchestrator orchestrator,
                             @Nonnull SweepObserver observer,
                             @Nonnull LongSupplier wallClock) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    }

    @Override
    public synchronized void tick(float dt,
                                  int systemIndex,
                                  @Nonnull Store<ChunkStore> chunkStore) {
        long nowMs = wallClock.getAsLong();
        TickState state = states.get(chunkStore);
        if (nowMs < state.nextSweepAtMs) {
            return;
        }
        state.nextSweepAtMs = saturatedAdd(nowMs, SWEEP_INTERVAL_MS);
        World world = chunkStore.getExternalData() != null
                ? chunkStore.getExternalData().getWorld() : null;
        Store<EntityStore> entityStore = world != null && world.getEntityStore() != null
                ? world.getEntityStore().getStore() : null;
        if (world == null || entityStore == null) {
            return;
        }
        WorldTimeResource time = entityStore.getResource(WorldTimeResource.getResourceType());
        if (time == null) {
            return;
        }
        Instant gameTime = time.getGameTime();
        int gameHour = resolveGameHour(gameTime, nowMs);
        long gameTimeMs = gameTime != null ? gameTime.toEpochMilli() : nowMs;
        SweepOutcome outcome = orchestrator.sweep(
                chunkStore, world, gameHour, gameTimeMs, nowMs);
        try {
            observer.onSweep(outcome);
        } catch (RuntimeException ignored) {
            // Diagnostics must not destabilize a world tick.
        }
    }

    static int resolveGameHour(Instant gameTime, long fallbackEpochMs) {
        Instant resolved = gameTime != null ? gameTime : Instant.ofEpochMilli(fallbackEpochMs);
        return resolved.atZone(ZoneOffset.UTC).getHour();
    }

    private long saturatedAdd(long value, long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    /** Immutable sweep diagnostics boundary. */
    @FunctionalInterface
    public interface SweepObserver {
        void onSweep(@Nonnull SweepOutcome outcome);

        @Nonnull
        static SweepObserver noop() {
            return ignored -> {
            };
        }
    }

    private static final class TickState {
        private long nextSweepAtMs;
    }
}
