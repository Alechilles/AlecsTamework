package com.alechilles.alecstamework.npc.progression;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Bounded shared reservation cache for short-lived NPC position targets.
 */
public final class PositionTargetReservationCache {
    public static final double DEFAULT_TTL_SECONDS = 24.0;
    public static final int MAX_ENTRIES = 4096;

    private static final ConcurrentHashMap<ReservationKey, Reservation> RESERVED_TARGETS = new ConcurrentHashMap<>();

    private PositionTargetReservationCache() {
    }

    public static boolean reserve(@Nullable UUID ownerUuid,
                                  @Nullable String worldName,
                                  @Nullable String label,
                                  @Nullable Vector3d target,
                                  double ttlSeconds,
                                  long nowMs) {
        if (ownerUuid == null || target == null || !isFinite(target)) {
            return false;
        }
        ReservationKey key = ReservationKey.from(worldName, label, target);
        long expiresAtMs = nowMs + ttlMs(ttlSeconds);
        while (true) {
            Reservation existing = RESERVED_TARGETS.get(key);
            if (existing == null) {
                Reservation previous = RESERVED_TARGETS.putIfAbsent(key, new Reservation(ownerUuid, expiresAtMs));
                if (previous == null) {
                    cleanup(nowMs);
                    return true;
                }
                continue;
            }
            if (nowMs >= existing.expiresAtMs()) {
                RESERVED_TARGETS.remove(key, existing);
                continue;
            }
            if (!existing.ownerUuid().equals(ownerUuid)) {
                return false;
            }
            RESERVED_TARGETS.put(key, new Reservation(ownerUuid, expiresAtMs));
            cleanup(nowMs);
            return true;
        }
    }

    public static boolean isReservedByOther(@Nullable UUID ownerUuid,
                                            @Nullable String worldName,
                                            @Nullable String label,
                                            @Nullable Vector3d target,
                                            long nowMs) {
        if (ownerUuid == null || target == null || !isFinite(target)) {
            return false;
        }
        ReservationKey key = ReservationKey.from(worldName, label, target);
        Reservation existing = RESERVED_TARGETS.get(key);
        if (existing == null) {
            return false;
        }
        if (nowMs >= existing.expiresAtMs()) {
            RESERVED_TARGETS.remove(key, existing);
            return false;
        }
        return !existing.ownerUuid().equals(ownerUuid);
    }

    public static boolean hasReservationFor(@Nullable String worldName, @Nullable String label, long nowMs) {
        if (RESERVED_TARGETS.isEmpty()) {
            return false;
        }
        String normalizedWorld = normalizeWorldName(worldName);
        String normalizedLabel = PositionTargetRejectCache.normalizeLabel(label);
        boolean found = false;
        for (var entry : RESERVED_TARGETS.entrySet()) {
            ReservationKey key = entry.getKey();
            Reservation reservation = entry.getValue();
            if (key == null || reservation == null || nowMs >= reservation.expiresAtMs()) {
                if (key != null && reservation != null) {
                    RESERVED_TARGETS.remove(key, reservation);
                }
                continue;
            }
            if (key.worldName().equals(normalizedWorld) && key.label().equals(normalizedLabel)) {
                found = true;
            }
        }
        return found;
    }

    public static void release(@Nullable UUID ownerUuid,
                               @Nullable String worldName,
                               @Nullable String label,
                               @Nullable Vector3d target) {
        if (ownerUuid == null || target == null || !isFinite(target)) {
            return;
        }
        ReservationKey key = ReservationKey.from(worldName, label, target);
        Reservation existing = RESERVED_TARGETS.get(key);
        if (existing != null && existing.ownerUuid().equals(ownerUuid)) {
            RESERVED_TARGETS.remove(key, existing);
        }
    }

    public static void clearForTests() {
        RESERVED_TARGETS.clear();
    }

    /** Removes reservations owned by one world during world teardown. */
    public static void clearWorld(@Nullable String worldName) {
        String normalizedWorld = normalizeWorldName(worldName);
        RESERVED_TARGETS.keySet().removeIf(key -> key.worldName().equals(normalizedWorld));
    }

    public static int countForTests() {
        return RESERVED_TARGETS.size();
    }

    private static long ttlMs(double ttlSeconds) {
        double effectiveSeconds = Double.isFinite(ttlSeconds) && ttlSeconds > 0.0
                ? ttlSeconds
                : DEFAULT_TTL_SECONDS;
        return Math.max(1L, (long) Math.ceil(effectiveSeconds * 1000.0));
    }

    private static void cleanup(long nowMs) {
        if (RESERVED_TARGETS.size() < MAX_ENTRIES) {
            return;
        }
        RESERVED_TARGETS.entrySet().removeIf(entry -> entry == null
                || entry.getKey() == null
                || entry.getValue() == null
                || nowMs >= entry.getValue().expiresAtMs());
        int excess = RESERVED_TARGETS.size() - MAX_ENTRIES;
        if (excess <= 0) {
            return;
        }
        for (ReservationKey key : RESERVED_TARGETS.keySet()) {
            if (excess <= 0) {
                return;
            }
            if (RESERVED_TARGETS.remove(key) != null) {
                excess--;
            }
        }
    }

    @Nonnull
    private static String normalizeWorldName(@Nullable String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return "global";
        }
        return worldName.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isFinite(@Nonnull Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private record Reservation(@Nonnull UUID ownerUuid, long expiresAtMs) {
    }

    private record ReservationKey(@Nonnull String worldName,
                                  @Nonnull String label,
                                  int blockX,
                                  int blockY,
                                  int blockZ) {
        @Nonnull
        private static ReservationKey from(@Nullable String worldName,
                                           @Nullable String label,
                                           @Nonnull Vector3d target) {
            return new ReservationKey(
                    normalizeWorldName(worldName),
                    PositionTargetRejectCache.normalizeLabel(label),
                    (int) Math.floor(target.x),
                    (int) Math.floor(target.y),
                    (int) Math.floor(target.z)
            );
        }
    }
}
