package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Lightweight in-memory cache of recent attacker -> victim hits for NPC filtering logic.
 */
public final class DamageTargetMemoryService {
    private static final DamageTargetMemoryService INSTANCE = new DamageTargetMemoryService();

    private static final long CLEANUP_INTERVAL_MS = 30_000L;
    private static final long STALE_ENTRY_MAX_AGE_MS = 5 * 60_000L;

    private final ConcurrentHashMap<HitKey, Long> hitTimeByPair = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RecentAttackerSnapshot> recentAttackerByVictim = new ConcurrentHashMap<>();
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

    public void recordAttacker(@Nonnull UUID victimUuid,
                               @Nonnull RecentAttackerSnapshot snapshot,
                               long nowMs) {
        if (victimUuid == null || snapshot == null) {
            return;
        }
        recentAttackerByVictim.put(victimUuid, snapshot);
        maybeCleanup(nowMs);
    }

    public boolean hasRecentHit(@Nonnull Ref<EntityStore> attackerRef,
                                @Nonnull Ref<EntityStore> victimRef,
                                long maxAgeMs,
                                long nowMs) {
        if (!attackerRef.isValid() || !victimRef.isValid()) {
            return false;
        }
        HitKey key = new HitKey(attackerRef, victimRef);
        Long hitTime = hitTimeByPair.get(key);
        if (hitTime == null) {
            return false;
        }
        if (maxAgeMs >= 0L && nowMs > hitTime + maxAgeMs) {
            hitTimeByPair.remove(key, hitTime);
            return false;
        }
        if (nowMs > hitTime + STALE_ENTRY_MAX_AGE_MS) {
            hitTimeByPair.remove(key, hitTime);
            return false;
        }
        return true;
    }

    @Nullable
    public RecentAttackerSnapshot getRecentAttacker(@Nullable UUID victimUuid,
                                                    long maxAgeMs,
                                                    long nowMs) {
        if (victimUuid == null) {
            return null;
        }
        RecentAttackerSnapshot snapshot = recentAttackerByVictim.get(victimUuid);
        if (snapshot == null) {
            return null;
        }
        if (maxAgeMs >= 0L && nowMs > snapshot.recordedAtMs() + maxAgeMs) {
            recentAttackerByVictim.remove(victimUuid, snapshot);
            return null;
        }
        if (nowMs > snapshot.recordedAtMs() + STALE_ENTRY_MAX_AGE_MS) {
            recentAttackerByVictim.remove(victimUuid, snapshot);
            return null;
        }
        return snapshot;
    }

    private void maybeCleanup(long nowMs) {
        if (nowMs - lastCleanupMs < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupMs = nowMs;
        Iterator<Map.Entry<HitKey, Long>> hitIterator = hitTimeByPair.entrySet().iterator();
        while (hitIterator.hasNext()) {
            Map.Entry<HitKey, Long> entry = hitIterator.next();
            HitKey key = entry.getKey();
            Long hitTime = entry.getValue();
            if (key == null
                    || hitTime == null
                    || !key.attackerRef.isValid()
                    || !key.victimRef.isValid()
                    || nowMs > hitTime + STALE_ENTRY_MAX_AGE_MS) {
                hitIterator.remove();
            }
        }
        Iterator<Map.Entry<UUID, RecentAttackerSnapshot>> attackerIterator = recentAttackerByVictim.entrySet().iterator();
        while (attackerIterator.hasNext()) {
            Map.Entry<UUID, RecentAttackerSnapshot> entry = attackerIterator.next();
            UUID victimUuid = entry.getKey();
            RecentAttackerSnapshot snapshot = entry.getValue();
            if (victimUuid == null
                    || snapshot == null
                    || nowMs > snapshot.recordedAtMs() + STALE_ENTRY_MAX_AGE_MS) {
                attackerIterator.remove();
            }
        }
    }

    public enum AttackerKind {
        PLAYER,
        NPC,
        OTHER
    }

    public record RecentAttackerSnapshot(@Nonnull UUID attackerUuid,
                                         @Nonnull AttackerKind attackerKind,
                                         @Nullable String attackerName,
                                         long recordedAtMs) {
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
