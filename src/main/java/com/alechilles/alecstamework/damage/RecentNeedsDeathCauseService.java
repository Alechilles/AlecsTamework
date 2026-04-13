package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracks recent needs-damage death hints so linked death snapshots can attribute starvation/dehydration deaths.
 */
public final class RecentNeedsDeathCauseService {
    private static final RecentNeedsDeathCauseService INSTANCE = new RecentNeedsDeathCauseService();
    private static final long CLEANUP_INTERVAL_MS = 30_000L;
    private static final long HINT_MAX_AGE_MS = 15_000L;
    private static final int MAX_ENTRIES_BEFORE_FORCED_CLEANUP = 2048;

    private final ConcurrentHashMap<UUID, NeedsDeathCauseHint> hintByNpcUuid = new ConcurrentHashMap<>();
    private volatile long lastCleanupMs;

    private RecentNeedsDeathCauseService() {
    }

    @Nonnull
    public static RecentNeedsDeathCauseService getInstance() {
        return INSTANCE;
    }

    public void record(@Nullable UUID npcUuid,
                       @Nullable CommandLinkedNpcDeathService.DeathCauseKind causeKind,
                       long nowMs) {
        if (npcUuid == null || causeKind == null) {
            return;
        }
        hintByNpcUuid.put(npcUuid, new NeedsDeathCauseHint(causeKind, nowMs));
        maybeCleanup(nowMs);
    }

    @Nullable
    public CommandLinkedNpcDeathService.DeathCauseKind consumeRecent(@Nullable UUID npcUuid, long nowMs) {
        if (npcUuid == null) {
            maybeCleanup(nowMs);
            return null;
        }
        NeedsDeathCauseHint hint = hintByNpcUuid.remove(npcUuid);
        if (hint == null) {
            maybeCleanup(nowMs);
            return null;
        }
        if (nowMs > hint.recordedAtMs() + HINT_MAX_AGE_MS) {
            maybeCleanup(nowMs);
            return null;
        }
        maybeCleanup(nowMs);
        return hint.causeKind();
    }

    private void maybeCleanup(long nowMs) {
        boolean intervalElapsed = nowMs - lastCleanupMs >= CLEANUP_INTERVAL_MS;
        if (!intervalElapsed && hintByNpcUuid.size() < MAX_ENTRIES_BEFORE_FORCED_CLEANUP) {
            return;
        }
        lastCleanupMs = nowMs;
        for (Map.Entry<UUID, NeedsDeathCauseHint> entry : hintByNpcUuid.entrySet()) {
            UUID npcUuid = entry.getKey();
            NeedsDeathCauseHint hint = entry.getValue();
            if (npcUuid == null || hint == null || nowMs > hint.recordedAtMs() + HINT_MAX_AGE_MS) {
                hintByNpcUuid.remove(npcUuid);
            }
        }
    }

    private record NeedsDeathCauseHint(@Nonnull CommandLinkedNpcDeathService.DeathCauseKind causeKind,
                                       long recordedAtMs) {
    }
}
