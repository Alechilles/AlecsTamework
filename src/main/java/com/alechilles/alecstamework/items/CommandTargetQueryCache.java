package com.alechilles.alecstamework.items;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Shares short-lived reticle query results between command HUD consumers. */
final class CommandTargetQueryCache {
    private static final int MAX_PLAYERS_PER_STORE = 512;

    private final long ttlMs;
    private final Map<Object, PlayerEntries> entriesByStore = new WeakHashMap<>();

    CommandTargetQueryCache(long ttlMs) {
        this.ttlMs = Math.max(0L, ttlMs);
    }

    @Nullable
    UUID resolve(@Nonnull Object storeKey,
                 @Nonnull UUID playerUuid,
                 long nowMs,
                 @Nonnull Supplier<UUID> query) {
        PlayerEntries entries;
        synchronized (entriesByStore) {
            entries = entriesByStore.computeIfAbsent(
                    storeKey,
                    ignored -> new PlayerEntries()
            );
        }
        synchronized (entries) {
            Entry cached = entries.get(playerUuid);
            if (cached != null && cached.isValid(nowMs, ttlMs)) {
                return cached.targetUuid();
            }
        }
        UUID resolved = query.get();
        synchronized (entries) {
            entries.put(playerUuid, new Entry(resolved, nowMs));
        }
        return resolved;
    }

    private static final class PlayerEntries extends LinkedHashMap<UUID, Entry> {
        private PlayerEntries() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) {
            return size() > MAX_PLAYERS_PER_STORE;
        }
    }

    private record Entry(@Nullable UUID targetUuid, long cachedAtMs) {
        private boolean isValid(long nowMs, long ttlMs) {
            return ttlMs > 0L
                    && nowMs >= cachedAtMs
                    && nowMs - cachedAtMs < ttlMs;
        }
    }
}
