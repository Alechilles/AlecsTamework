package com.alechilles.alecstamework.npc.progression;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Tracks live needs-seek movement progress so role logic can abandon stalled paths before the long timeout.
 */
public final class NeedsResourceMovementProgressTracker {
    private static final ConcurrentHashMap<UUID, NpcProgressRecords> RECORDS_BY_NPC = new ConcurrentHashMap<>();
    private static final long STALE_RECORD_TTL_MS = 5 * 60 * 1_000L;
    private static final int MAX_TRACKED_NPCS = 16_384;

    private NeedsResourceMovementProgressTracker() {
    }

    public static void recordStart(@Nullable UUID npcUuid,
                                   @Nullable String resourceType,
                                   @Nullable Vector3d target,
                                   @Nullable Vector3d currentPosition,
                                   long nowMs) {
        if (npcUuid == null || target == null || currentPosition == null || !isFinite(target) || !isFinite(currentPosition)) {
            return;
        }
        cleanupIfNeeded(nowMs);
        NpcProgressRecords records = RECORDS_BY_NPC.computeIfAbsent(npcUuid, ignored -> new NpcProgressRecords());
        records.record(resourceSlot(resourceType), target, currentPosition, nowMs);
    }

    public static boolean isStalled(@Nullable UUID npcUuid,
                                    @Nullable String resourceType,
                                    @Nullable Vector3d currentPosition,
                                    long nowMs,
                                    double graceSeconds,
                                    double minProgress) {
        if (npcUuid == null || currentPosition == null || !isFinite(currentPosition)) {
            return false;
        }
        NpcProgressRecords records = RECORDS_BY_NPC.get(npcUuid);
        if (records == null) {
            return false;
        }
        return records.isStalled(
                resourceSlot(resourceType),
                currentPosition,
                nowMs,
                sanitizeSeconds(graceSeconds, 8.0),
                sanitizePositive(minProgress, 0.75)
        );
    }

    public static void clear(@Nullable UUID npcUuid, @Nullable String resourceType) {
        if (npcUuid == null) {
            return;
        }
        NpcProgressRecords records = RECORDS_BY_NPC.get(npcUuid);
        if (records == null) {
            return;
        }
        records.clear(resourceSlot(resourceType));
        if (records.isEmpty()) {
            RECORDS_BY_NPC.remove(npcUuid, records);
        }
    }

    static int trackedNpcCountForTests() {
        return RECORDS_BY_NPC.size();
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

    private static boolean isFinite(@Nonnull Vector3d value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static double sanitizeSeconds(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double sanitizePositive(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }

    private static void cleanupIfNeeded(long nowMs) {
        if (RECORDS_BY_NPC.size() <= MAX_TRACKED_NPCS) {
            return;
        }
        long staleBeforeMs = nowMs - STALE_RECORD_TTL_MS;
        RECORDS_BY_NPC.entrySet().removeIf(entry -> entry.getValue().isStale(staleBeforeMs));
    }

    private static final class NpcProgressRecords {
        @Nullable
        private ProgressRecord water;
        @Nullable
        private ProgressRecord foodContainer;
        @Nullable
        private ProgressRecord other;

        private synchronized void record(int slot, @Nonnull Vector3d target, @Nonnull Vector3d currentPosition, long nowMs) {
            ProgressRecord record = new ProgressRecord(target, currentPosition, nowMs);
            if (slot == 0) {
                water = record;
            } else if (slot == 1) {
                foodContainer = record;
            } else {
                other = record;
            }
        }

        private synchronized boolean isStalled(int slot,
                                               @Nonnull Vector3d currentPosition,
                                               long nowMs,
                                               double graceSeconds,
                                               double minProgress) {
            ProgressRecord record = record(slot);
            return record != null && record.isStalled(currentPosition, nowMs, graceSeconds, minProgress);
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

        private synchronized boolean isStale(long staleBeforeMs) {
            return isRecordStale(water, staleBeforeMs)
                    && isRecordStale(foodContainer, staleBeforeMs)
                    && isRecordStale(other, staleBeforeMs);
        }

        @Nullable
        private ProgressRecord record(int slot) {
            if (slot == 0) {
                return water;
            }
            if (slot == 1) {
                return foodContainer;
            }
            return other;
        }

        private static boolean isRecordStale(@Nullable ProgressRecord record, long staleBeforeMs) {
            return record == null || record.startedAtMs() < staleBeforeMs;
        }
    }

    private record ProgressRecord(double targetX,
                                  double targetY,
                                  double targetZ,
                                  double startDistance,
                                  long startedAtMs) {
        private ProgressRecord(@Nonnull Vector3d target, @Nonnull Vector3d currentPosition, long startedAtMs) {
            this(
                    target.x,
                    target.y,
                    target.z,
                    distance(currentPosition.x, currentPosition.y, currentPosition.z, target.x, target.y, target.z),
                    startedAtMs
            );
        }

        private boolean isStalled(@Nonnull Vector3d currentPosition,
                                  long nowMs,
                                  double graceSeconds,
                                  double minProgress) {
            if (nowMs < startedAtMs + (long) (graceSeconds * 1_000.0)) {
                return false;
            }
            double currentDistance = distance(currentPosition.x, currentPosition.y, currentPosition.z, targetX, targetY, targetZ);
            return startDistance - currentDistance < minProgress;
        }

        private static double distance(double ax, double ay, double az, double bx, double by, double bz) {
            double dx = ax - bx;
            double dy = ay - by;
            double dz = az - bz;
            return Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
        }
    }
}
