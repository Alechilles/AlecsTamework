package com.alechilles.alecstamework.npc.progression;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stores the most recent needs-resource consume result long enough for the role state machine to decide whether to loop.
 */
public final class NeedsResourceConsumeAttemptTracker {
    private static final long SUCCESS_WINDOW_MS = 5_000L;
    private static final ConcurrentHashMap<UUID, AttemptRecords> RECORDS_BY_NPC = new ConcurrentHashMap<>();

    private NeedsResourceConsumeAttemptTracker() {
    }

    public static void record(@Nullable UUID npcUuid, @Nullable String resourceType, boolean consumed, long nowMs) {
        if (npcUuid == null) {
            return;
        }
        RECORDS_BY_NPC.computeIfAbsent(npcUuid, ignored -> new AttemptRecords())
                .record(resourceSlot(resourceType), consumed, nowMs);
    }

    public static boolean wasSuccessful(@Nullable UUID npcUuid, @Nullable String resourceType, long nowMs) {
        return wasSuccessful(npcUuid, resourceType, nowMs, SUCCESS_WINDOW_MS);
    }

    public static boolean wasSuccessful(@Nullable UUID npcUuid,
                                        @Nullable String resourceType,
                                        long nowMs,
                                        long maxAgeMs) {
        if (npcUuid == null || maxAgeMs <= 0L) {
            return false;
        }
        AttemptRecords records = RECORDS_BY_NPC.get(npcUuid);
        return records != null && records.wasSuccessful(resourceSlot(resourceType), nowMs, maxAgeMs);
    }

    public static void clear(@Nullable UUID npcUuid, @Nullable String resourceType) {
        if (npcUuid == null) {
            return;
        }
        AttemptRecords records = RECORDS_BY_NPC.get(npcUuid);
        if (records == null) {
            return;
        }
        records.clear(resourceSlot(resourceType));
        if (records.isEmpty()) {
            RECORDS_BY_NPC.remove(npcUuid, records);
        }
    }

    static void clearForTests() {
        RECORDS_BY_NPC.clear();
    }

    private static int resourceSlot(@Nullable String resourceType) {
        String normalized = normalize(resourceType);
        if ("water".equals(normalized)) {
            return 0;
        }
        if ("foodcontainer".equals(normalized)) {
            return 1;
        }
        return 2;
    }

    @Nonnull
    private static String normalize(@Nullable String resourceType) {
        return resourceType == null ? "" : resourceType.trim().toLowerCase(Locale.ROOT);
    }

    private static final class AttemptRecords {
        @Nullable
        private AttemptRecord water;
        @Nullable
        private AttemptRecord foodContainer;
        @Nullable
        private AttemptRecord other;

        private synchronized void record(int slot, boolean consumed, long nowMs) {
            AttemptRecord record = new AttemptRecord(consumed, nowMs);
            if (slot == 0) {
                water = record;
            } else if (slot == 1) {
                foodContainer = record;
            } else {
                other = record;
            }
        }

        private synchronized boolean wasSuccessful(int slot, long nowMs, long maxAgeMs) {
            AttemptRecord record = record(slot);
            return record != null && record.consumed() && nowMs <= record.recordedAtMs() + maxAgeMs;
        }

        private synchronized void clear(int slot) {
            if (slot == 0) {
                water = null;
            } else if (slot == 1) {
                foodContainer = null;
            } else {
                other = null;
            }
        }

        private synchronized boolean isEmpty() {
            return water == null && foodContainer == null && other == null;
        }

        @Nullable
        private AttemptRecord record(int slot) {
            if (slot == 0) {
                return water;
            }
            if (slot == 1) {
                return foodContainer;
            }
            return other;
        }
    }

    private record AttemptRecord(boolean consumed, long recordedAtMs) {
    }
}
