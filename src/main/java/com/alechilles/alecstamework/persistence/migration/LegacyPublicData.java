package com.alechilles.alecstamework.persistence.migration;

import java.util.List;
import javax.annotation.Nullable;

/** Immutable public-v2-v4 rows loaded from one classified source snapshot. */
record LegacyPublicData(
        List<Profile> profiles,
        List<Alias> aliases,
        List<ToolLink> toolLinks,
        List<Snapshot> snapshots,
        List<CoopSlot> coopSlots,
        List<ProfileState> profileStates,
        List<ExtensionData> extensionData
) {
    LegacyPublicData {
        profiles = List.copyOf(profiles);
        aliases = List.copyOf(aliases);
        toolLinks = List.copyOf(toolLinks);
        snapshots = List.copyOf(snapshots);
        coopSlots = List.copyOf(coopSlots);
        profileStates = List.copyOf(profileStates);
        extensionData = List.copyOf(extensionData);
    }

    record Profile(
            String profileId,
            @Nullable String currentNpcUuid,
            @Nullable String ownerUuid,
            @Nullable String displayName,
            @Nullable String roleId,
            @Nullable String stateJson,
            @Nullable String stateHash,
            @Nullable String lastWorldName,
            long createdAtMs,
            long updatedAtMs,
            long lastActiveAtMs
    ) {
    }

    record Alias(
            String npcUuid,
            String profileId,
            int current,
            long mappedAtMs
    ) {
    }

    record ToolLink(
            String profileId,
            String toolUuid,
            String linkType,
            long createdAtMs,
            long updatedAtMs
    ) {
    }

    /** Public snapshot row whose source revision is an aggregate counter, not a codec version. */
    record Snapshot(
            long sourceSnapshotId,
            String profileId,
            String kind,
            int sourceRevision,
            String payloadJson,
            int active,
            long createdAtMs
    ) {
    }

    record CoopSlot(
            String worldName,
            String coopId,
            int x,
            int y,
            int z,
            int residentSlot,
            @Nullable String profileId,
            @Nullable String housedNpcUuid,
            @Nullable String lastReleasedNpcUuid,
            long capturedAtMs,
            long releasedAtMs,
            long updatedAtMs,
            @Nullable String stateSnapshotJson
    ) {
        String coopKey() {
            return worldName + "|" + coopId + "|" + x + "|" + y + "|" + z + "|" + residentSlot;
        }
    }

    record ProfileState(
            String profileId,
            int captureActive,
            int deathActive,
            int lostActive,
            int inCoop,
            @Nullable String coopKey,
            long updatedAtMs
    ) {
    }

    record ExtensionData(
            String profileId,
            String namespace,
            String dataKey,
            String jsonPayload,
            long createdAtMs,
            long updatedAtMs
    ) {
    }
}
