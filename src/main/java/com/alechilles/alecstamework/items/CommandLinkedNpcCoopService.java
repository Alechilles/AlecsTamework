package com.alechilles.alecstamework.items;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps process-local coop presentation detail for command-linked companions.
 *
 * <p>The direct-live coop author and canonical profile projection own durable
 * occupancy. This cache exists only for same-process presentation fallbacks and
 * command filtering while a gameplay transition is still in memory.</p>
 */
public final class CommandLinkedNpcCoopService {
    private final ConcurrentHashMap<UUID, CoopLinkedNpcSnapshot> snapshots =
            new ConcurrentHashMap<>();

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshot(@Nullable UUID npcUuid) {
        return npcUuid == null ? null : snapshots.get(npcUuid);
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForTool(
            @Nullable UUID npcUuid,
            @Nullable String toolId,
            @Nullable UUID ownerUuid
    ) {
        CoopLinkedNpcSnapshot snapshot = getCoopSnapshot(npcUuid);
        return snapshot != null
                && snapshot.containsToolId(toolId)
                && ownerCompatible(snapshot, ownerUuid)
                ? snapshot
                : null;
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForOwner(
            @Nullable UUID npcUuid,
            @Nullable UUID ownerUuid
    ) {
        CoopLinkedNpcSnapshot snapshot = getCoopSnapshot(npcUuid);
        return snapshot != null && ownerCompatible(snapshot, ownerUuid)
                ? snapshot
                : null;
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForToolOrOwner(
            @Nullable UUID npcUuid,
            @Nullable String toolId,
            @Nullable UUID ownerUuid
    ) {
        CoopLinkedNpcSnapshot byTool =
                getCoopSnapshotForTool(npcUuid, toolId, ownerUuid);
        return byTool != null
                ? byTool
                : getCoopSnapshotForOwner(npcUuid, ownerUuid);
    }

    public void recordCoopSnapshot(
            @Nullable CoopLinkedNpcSnapshot snapshot
    ) {
        if (snapshot != null) {
            snapshots.put(snapshot.npcUuid(), snapshot);
        }
    }

    public void clearCoopSnapshot(@Nullable UUID npcUuid) {
        if (npcUuid != null) {
            snapshots.remove(npcUuid);
        }
    }

    public void clearAll() {
        snapshots.clear();
    }

    private boolean ownerCompatible(
            @Nonnull CoopLinkedNpcSnapshot snapshot,
            @Nullable UUID ownerUuid
    ) {
        return snapshot.ownerId() == null
                || ownerUuid == null
                || snapshot.ownerId().equals(ownerUuid);
    }

    /** Immutable same-process detail for a companion currently assigned to a coop. */
    public record CoopLinkedNpcSnapshot(
            @Nonnull UUID npcUuid,
            @Nullable UUID ownerId,
            @Nullable String[] toolIds,
            @Nullable String roleId,
            @Nullable String displayName,
            @Nullable String coopId,
            int residentSlot,
            long housedAtMs
    ) {
        public CoopLinkedNpcSnapshot {
            if (npcUuid == null) {
                throw new IllegalArgumentException("NPC UUID is required");
            }
            toolIds = toolIds == null ? new String[0] : toolIds.clone();
        }

        @Override
        public String[] toolIds() {
            return toolIds.clone();
        }

        public boolean containsToolId(@Nullable String toolId) {
            if (toolId == null || toolId.isBlank()) {
                return false;
            }
            for (String value : toolIds) {
                if (toolId.equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
