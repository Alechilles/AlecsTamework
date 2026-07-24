package com.alechilles.alecstamework.persistence.migration;

import java.util.List;
import javax.annotation.Nullable;

/** Strictly decoded rows from the five-file public legacy DAT bundle. */
record LegacyDatRows(
        List<Snapshot> snapshots,
        List<CoopSlot> coopSlots
) {
    LegacyDatRows {
        snapshots = List.copyOf(snapshots);
        coopSlots = List.copyOf(coopSlots);
    }

    record Snapshot(
            String kind,
            String npcUuid,
            @Nullable String ownerUuid,
            @Nullable String ownerName,
            List<String> toolIds,
            @Nullable String roleId,
            @Nullable String displayName,
            @Nullable String customName,
            @Nullable Boolean tamed,
            String payloadJson,
            long eventAtMs
    ) {
        Snapshot {
            toolIds = List.copyOf(toolIds);
        }
    }

    record CoopSlot(
            String worldName,
            String coopId,
            int x,
            int y,
            int z,
            int residentSlot,
            @Nullable String housedNpcUuid,
            @Nullable String lastReleasedNpcUuid,
            @Nullable String ownerUuid,
            List<String> toolIds,
            @Nullable String roleId,
            @Nullable String displayName,
            long housedAtMs,
            long releasedAtMs
    ) {
        CoopSlot {
            toolIds = List.copyOf(toolIds);
        }

        @Nullable
        String profileNpcUuid() {
            return housedNpcUuid != null ? housedNpcUuid : lastReleasedNpcUuid;
        }

        String sourceKey() {
            return worldName + "|" + coopId + "|" + x + "|" + y + "|" + z
                    + "|" + residentSlot;
        }
    }
}
