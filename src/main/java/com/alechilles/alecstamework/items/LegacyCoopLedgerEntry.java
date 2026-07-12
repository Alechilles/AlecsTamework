package com.alechilles.alecstamework.items;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Mutable row used only by the pre-schema-v5 command-coop rollback ledger.
 *
 * <p>This type deliberately has package visibility. Managed coops never read or write it as
 * occupancy authority.</p>
 */
final class LegacyCoopLedgerEntry {
    final String slotKey;
    @Nullable final String worldName;
    @Nullable final String coopId;
    final int x;
    final int y;
    final int z;
    final int residentSlot;
    @Nullable UUID housedNpcUuid;
    @Nullable UUID lastReleasedNpcUuid;
    @Nullable UUID ownerId;
    @Nonnull String[] toolIds;
    @Nullable String roleId;
    @Nullable String displayName;
    long housedAtMs;
    long releasedAtMs;
    @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot;

    LegacyCoopLedgerEntry(@Nonnull String slotKey,
                          @Nullable String worldName,
                          @Nullable String coopId,
                          int x,
                          int y,
                          int z,
                          int residentSlot,
                          @Nullable UUID housedNpcUuid,
                          @Nullable UUID lastReleasedNpcUuid,
                          @Nullable UUID ownerId,
                          @Nonnull String[] toolIds,
                          @Nullable String roleId,
                          @Nullable String displayName,
                          long housedAtMs,
                          long releasedAtMs,
                          @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot) {
        this.slotKey = slotKey;
        this.worldName = worldName;
        this.coopId = coopId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.residentSlot = residentSlot;
        this.housedNpcUuid = housedNpcUuid;
        this.lastReleasedNpcUuid = lastReleasedNpcUuid;
        this.ownerId = ownerId;
        this.toolIds = toolIds;
        this.roleId = roleId;
        this.displayName = displayName;
        this.housedAtMs = housedAtMs;
        this.releasedAtMs = releasedAtMs;
        this.stateSnapshot = stateSnapshot;
    }

    @Nonnull
    CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot toSnapshot(@Nonnull UUID npcUuid) {
        return new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                npcUuid,
                ownerId,
                toolIds.clone(),
                roleId,
                displayName,
                coopId,
                residentSlot,
                housedAtMs
        );
    }
}
