package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * Lightweight in-memory cache of recent attacker -> victim hits for NPC filtering logic.
 */
public final class DamageTargetMemoryService {
    private static final DamageTargetMemoryService INSTANCE = new DamageTargetMemoryService();

    private static final long CLEANUP_INTERVAL_MS = 30_000L;
    private static final long STALE_ENTRY_MAX_AGE_MS = 5 * 60_000L;
    private static final int MAX_ENTRIES_BEFORE_FORCED_CLEANUP = 4096;

    private final ConcurrentHashMap<HitKey, Long> hitTimeByPair = new ConcurrentHashMap<>();
    private volatile long lastCleanupMs;

    private DamageTargetMemoryService() {
    }

    @Nonnull
    public static DamageTargetMemoryService getInstance() {
        return INSTANCE;
    }

    public void recordHit(@Nonnull Ref<EntityStore> attackerRef,
                          @Nonnull Ref<EntityStore> victimRef,
                          long nowMs) {
        if (!attackerRef.isValid() || !victimRef.isValid()) {
            return;
        }
        hitTimeByPair.put(new HitKey(attackerRef, victimRef), nowMs);
        maybeCleanup(nowMs);
    }

    public boolean hasRecentHit(@Nonnull Ref<EntityStore> attackerRef,
                                @Nonnull Ref<EntityStore> victimRef,
                                long maxAgeMs,
                                long nowMs) {
        if (!attackerRef.isValid() || !victimRef.isValid()) {
            maybeCleanup(nowMs);
            return false;
        }
        HitKey key = new HitKey(attackerRef, victimRef);
        Long hitTime = hitTimeByPair.get(key);
        if (hitTime == null) {
            maybeCleanup(nowMs);
            return false;
        }
        if (maxAgeMs >= 0L && nowMs > hitTime + maxAgeMs) {
            return false;
        }
        maybeCleanup(nowMs);
        return true;
    }

    private void maybeCleanup(long nowMs) {
        boolean intervalElapsed = nowMs - lastCleanupMs >= CLEANUP_INTERVAL_MS;
        if (!intervalElapsed && hitTimeByPair.size() < MAX_ENTRIES_BEFORE_FORCED_CLEANUP) {
            return;
        }
        lastCleanupMs = nowMs;
        for (Map.Entry<HitKey, Long> entry : hitTimeByPair.entrySet()) {
            HitKey key = entry.getKey();
            Long hitTime = entry.getValue();
            if (key == null
                    || hitTime == null
                    || !key.attackerRef.isValid()
                    || !key.victimRef.isValid()
                    || nowMs > hitTime + STALE_ENTRY_MAX_AGE_MS) {
                hitTimeByPair.remove(key);
            }
        }
    }

    private static final class HitKey {
        private final Ref<EntityStore> attackerRef;
        private final Ref<EntityStore> victimRef;

        private HitKey(@Nonnull Ref<EntityStore> attackerRef, @Nonnull Ref<EntityStore> victimRef) {
            this.attackerRef = attackerRef;
            this.victimRef = victimRef;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof HitKey other)) {
                return false;
            }
            return attackerRef.equals(other.attackerRef) && victimRef.equals(other.victimRef);
        }

        @Override
        public int hashCode() {
            int result = attackerRef.hashCode();
            result = 31 * result + victimRef.hashCode();
            return result;
        }
    }
}
