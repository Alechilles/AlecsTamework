package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Pure normalization and matching helpers for the rollback-only command-coop ledger. */
final class LegacyCoopLedgerSupport {
    static final int UNKNOWN_COORDINATE = Integer.MIN_VALUE;

    private LegacyCoopLedgerSupport() {
    }

    @Nullable
    static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    static String slotKey(@Nonnull CommandLinkedNpcCoopService.CoopSlotContext context) {
        String world = normalize(context.worldName());
        return (world != null ? world : "<unknown>")
                + "|" + context.x() + "," + context.y() + "," + context.z()
                + "|" + context.residentSlot();
    }

    static boolean hasKnownCoordinates(@Nonnull CommandLinkedNpcCoopService.CoopSlotContext context) {
        return hasKnownCoordinates(context.x(), context.y(), context.z());
    }

    static boolean hasKnownCoordinates(int x, int y, int z) {
        return x != UNKNOWN_COORDINATE && y != UNKNOWN_COORDINATE && z != UNKNOWN_COORDINATE;
    }

    static boolean worldMatches(@Nullable String left, @Nullable String right) {
        return left == null || left.isBlank() || right == null || right.isBlank() || left.equals(right);
    }

    @Nonnull
    static String[] sanitizeToolIds(@Nullable String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String toolId : toolIds) {
            if (toolId != null && !toolId.isBlank()) {
                unique.add(toolId);
            }
        }
        return unique.toArray(new String[0]);
    }

    static boolean sharesAnyToolId(@Nullable String[] left, @Nullable String[] right) {
        String[] leftValues = sanitizeToolIds(left);
        String[] rightValues = sanitizeToolIds(right);
        for (String leftValue : leftValues) {
            for (String rightValue : rightValues) {
                if (leftValue.equals(rightValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean roleMatches(@Nullable String snapshotRoleId, @Nullable String currentRoleId) {
        String snapshot = normalize(snapshotRoleId);
        String current = normalize(currentRoleId);
        return snapshot == null || current == null || snapshot.equals(current);
    }

    static boolean ownerMatches(@Nullable UUID snapshotOwnerId, @Nullable UUID ownerId) {
        return snapshotOwnerId == null || ownerId == null || snapshotOwnerId.equals(ownerId);
    }

    static boolean coopMatches(@Nullable String snapshotCoopId, @Nullable String currentCoopId) {
        String snapshot = normalize(snapshotCoopId);
        String current = normalize(currentCoopId);
        return snapshot != null && current != null && snapshot.equals(current);
    }

    static UUID ownerId(@Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                        @Nullable UUID fallback) {
        UUID result = fallback;
        if (snapshot == null) {
            return result;
        }
        TameworkCommandLinksComponent links = snapshot.commandLinks();
        if (links != null && links.getOwnerId() != null) {
            result = links.getOwnerId();
        }
        TameworkOwnerComponent owner = snapshot.owner();
        return owner != null && owner.getOwnerId() != null ? owner.getOwnerId() : result;
    }

    @Nonnull
    static String[] toolIds(@Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                            @Nullable String[] fallback) {
        if (snapshot != null) {
            TameworkCommandLinksComponent links = snapshot.commandLinks();
            if (links != null && links.getToolIds() != null && links.getToolIds().length > 0) {
                return sanitizeToolIds(links.getToolIds());
            }
        }
        return sanitizeToolIds(fallback);
    }

    @Nullable
    static String displayName(@Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                              @Nullable String fallback) {
        if (snapshot != null) {
            TameworkNpcNameComponent name = snapshot.npcName();
            if (name != null && name.getName() != null && !name.getName().isBlank()) {
                return name.getName();
            }
        }
        return fallback == null || fallback.isBlank() ? null : fallback;
    }
}
