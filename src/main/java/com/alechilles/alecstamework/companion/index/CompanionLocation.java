package com.alechilles.alecstamework.companion.index;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Where a companion is. LIVE carries its world and last known position; COOP carries
 * its world, block position and slot; STORED carries a reason; DEAD, LOST and RELEASED
 * may carry a cause.
 */
public record CompanionLocation(
        @Nonnull LocationKind kind,
        @Nullable String world,
        double x,
        double y,
        double z,
        int slot,
        @Nullable StoredReason reason,
        @Nullable String cause
) {
    public CompanionLocation {
        Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case LIVE -> require(hasText(world), "LIVE location needs a world");
            case COOP -> require(hasText(world) && slot >= 0, "COOP location needs a world and a slot");
            case STORED -> require(reason != null, "STORED location needs a reason");
            default -> { }
        }
        require(kind == LocationKind.STORED || reason == null, "only STORED locations have a reason");
    }

    @Nonnull
    public static CompanionLocation live(@Nonnull String world, double x, double y, double z) {
        return new CompanionLocation(LocationKind.LIVE, world, x, y, z, -1, null, null);
    }

    @Nonnull
    public static CompanionLocation item() {
        return new CompanionLocation(LocationKind.ITEM, null, 0, 0, 0, -1, null, null);
    }

    @Nonnull
    public static CompanionLocation coop(@Nonnull String world, int blockX, int blockY, int blockZ, int slot) {
        return new CompanionLocation(LocationKind.COOP, world, blockX, blockY, blockZ, slot, null, null);
    }

    @Nonnull
    public static CompanionLocation stored(@Nonnull StoredReason reason) {
        return new CompanionLocation(LocationKind.STORED, null, 0, 0, 0, -1, reason, null);
    }

    @Nonnull
    public static CompanionLocation dead(@Nullable String cause) {
        return new CompanionLocation(LocationKind.DEAD, null, 0, 0, 0, -1, null, cause);
    }

    @Nonnull
    public static CompanionLocation lost(@Nullable String cause) {
        return new CompanionLocation(LocationKind.LOST, null, 0, 0, 0, -1, null, cause);
    }

    @Nonnull
    public static CompanionLocation released(@Nullable String cause) {
        return new CompanionLocation(LocationKind.RELEASED, null, 0, 0, 0, -1, null, cause);
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
