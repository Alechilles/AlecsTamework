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
 * the first time is marked once. {@code nowMs} is any monotonic millisecond source with an
 * arbitrary origin (it may be negative) and is never persisted. Not thread-safe; used only by
 * the owning world thread.
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
    // MIN_VALUE makes a new tracker due at once for any clock origin, including negative ones.
    private long nextDiscreteAtMs = Long.MIN_VALUE;
    private long nextDriftAtMs = Long.MIN_VALUE;

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

    /** True when either check is due. A new tracker is due at once. */
    public boolean isDue(long nowMs) {
        return nowMs >= nextDiscreteAtMs || nowMs >= nextDriftAtMs;
    }

    /**
     * Pushes each due check to its next interval without observing, so a body whose
     * fingerprint failed is retried at the normal cadence instead of every tick. A tracker
     * that was never seen still marks on its first successful observe.
     */
    public void deferDueChecks(long nowMs) {
        if (nowMs >= nextDiscreteAtMs) {
            nextDiscreteAtMs = nowMs + discreteIntervalMs;
        }
        if (nowMs >= nextDriftAtMs) {
            nextDriftAtMs = nowMs + driftIntervalMs;
        }
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
        // A copied body is a new entity that has not been saved, so it gets its own first-sight mark.
        return new CompanionChangeTracker(discreteIntervalMs, driftIntervalMs, staggerMs);
    }
}
