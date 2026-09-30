package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runtime-only (no codec, never saved) change tracker on a loaded companion body, plus the
 * decision of when to mark it dirty (spec 6.4). Discrete changes are checked every
 * {@code discreteIntervalMs}; drift is checked every {@code driftIntervalMs}. A body seen for
 * the first time is marked once. Not thread-safe; used only by the owning world thread.
 */
public final class CompanionChangeTracker implements Component<EntityStore> {
    @Nullable
    private static ComponentType<EntityStore, CompanionChangeTracker> type;

    private final long discreteIntervalMs;
    private final long driftIntervalMs;
    private final long staggerMs;
    private boolean seen;
    private int discreteHash;
    private int driftHash;
    private long nextDiscreteAtMs;
    private long nextDriftAtMs;

    public CompanionChangeTracker() {
        this(2_000L, 60_000L, 0L);
    }

    public CompanionChangeTracker(long discreteIntervalMs, long driftIntervalMs, long staggerMs) {
        this.discreteIntervalMs = discreteIntervalMs;
        this.driftIntervalMs = driftIntervalMs;
        this.staggerMs = staggerMs;
    }

    public static void register(@Nonnull Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(CompanionChangeTracker.class, CompanionChangeTracker::new);
    }

    @Nullable
    public static ComponentType<EntityStore, CompanionChangeTracker> getComponentType() {
        return type;
    }

    /** True when either check is due, or the tracker has not been seen yet. */
    public boolean isDue(long nowMs) {
        return !seen || nowMs >= nextDiscreteAtMs || nowMs >= nextDriftAtMs;
    }

    /** Returns true when the body should be marked dirty now. */
    public boolean observe(long nowMs, int currentDiscreteHash, int currentDriftHash) {
        if (!seen) {
            seen = true;
            discreteHash = currentDiscreteHash;
            driftHash = currentDriftHash;
            nextDiscreteAtMs = nowMs + discreteIntervalMs + staggerMs;
            nextDriftAtMs = nowMs + driftIntervalMs + staggerMs;
            return true;
        }
        boolean mark = false;
        if (nowMs >= nextDiscreteAtMs) {
            nextDiscreteAtMs = nowMs + discreteIntervalMs;
            if (currentDiscreteHash != discreteHash) {
                discreteHash = currentDiscreteHash;
                driftHash = currentDriftHash;
                nextDriftAtMs = nowMs + driftIntervalMs;
                mark = true;
            }
        }
        if (!mark && nowMs >= nextDriftAtMs) {
            nextDriftAtMs = nowMs + driftIntervalMs;
            if (currentDriftHash != driftHash) {
                driftHash = currentDriftHash;
                mark = true;
            }
        }
        return mark;
    }

    @Override
    public CompanionChangeTracker clone() {
        CompanionChangeTracker copy = new CompanionChangeTracker(discreteIntervalMs, driftIntervalMs, staggerMs);
        copy.seen = seen;
        copy.discreteHash = discreteHash;
        copy.driftHash = driftHash;
        copy.nextDiscreteAtMs = nextDiscreteAtMs;
        copy.nextDriftAtMs = nextDriftAtMs;
        return copy;
    }
}
