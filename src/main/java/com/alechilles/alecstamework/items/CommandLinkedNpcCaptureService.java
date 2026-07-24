package com.alechilles.alecstamework.items;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Keeps process-local captured-companion detail for immediate command presentation.
 *
 * <p>Canonical capture lifecycle, snapshots, and restart persistence belong exclusively to the
 * replacement capture operation and profile projection. This cache performs no storage I/O.</p>
 */
public final class CommandLinkedNpcCaptureService {
    private final ConcurrentHashMap<UUID, CapturedLinkedNpcSnapshot>
            capturedByNpc = new ConcurrentHashMap<>();

    @Nullable
    public CapturedLinkedNpcSnapshot getCapturedSnapshot(UUID npcUuid) {
        return npcUuid == null ? null : capturedByNpc.get(npcUuid);
    }

    @Nullable
    public CapturedLinkedNpcSnapshot getCapturedSnapshotForTool(
            UUID npcUuid,
            String toolId,
            @Nullable UUID ownerUuid
    ) {
        CapturedLinkedNpcSnapshot snapshot =
                getCapturedSnapshot(npcUuid);
        return snapshot != null
                && snapshot.containsToolId(toolId)
                && ownerCompatible(snapshot, ownerUuid)
                ? snapshot
                : null;
    }

    @Nullable
    public CapturedLinkedNpcSnapshot getCapturedSnapshotForOwner(
            UUID npcUuid,
            @Nullable UUID ownerUuid
    ) {
        CapturedLinkedNpcSnapshot snapshot =
                getCapturedSnapshot(npcUuid);
        return snapshot != null && ownerCompatible(snapshot, ownerUuid)
                ? snapshot
                : null;
    }

    @Nullable
    public CapturedLinkedNpcSnapshot getCapturedSnapshotForToolOrOwner(
            UUID npcUuid,
            String toolId,
            @Nullable UUID ownerUuid
    ) {
        CapturedLinkedNpcSnapshot byTool =
                getCapturedSnapshotForTool(npcUuid, toolId, ownerUuid);
        return byTool != null
                ? byTool
                : getCapturedSnapshotForOwner(npcUuid, ownerUuid);
    }

    public void recordCapturedSnapshot(
            @Nullable CapturedLinkedNpcSnapshot snapshot
    ) {
        if (snapshot == null || snapshot.npcUuid() == null) {
            return;
        }
        String[] toolIds = sanitizeToolIds(snapshot.toolIds());
        if (toolIds.length == 0) {
            return;
        }
        capturedByNpc.put(
                snapshot.npcUuid(),
                new CapturedLinkedNpcSnapshot(
                        snapshot.npcUuid(),
                        snapshot.ownerId(),
                        toolIds,
                        snapshot.roleId(),
                        snapshot.displayName(),
                        snapshot.lastKnownPosition(),
                        snapshot.homePosition(),
                        snapshot.capturedAtMs() != 0L
                                ? snapshot.capturedAtMs()
                                : System.currentTimeMillis()
                )
        );
    }

    public void clearCapturedSnapshot(UUID npcUuid) {
        if (npcUuid != null) {
            capturedByNpc.remove(npcUuid);
        }
    }

    private boolean ownerCompatible(
            CapturedLinkedNpcSnapshot snapshot,
            @Nullable UUID ownerUuid
    ) {
        return snapshot.ownerId() == null
                || ownerUuid == null
                || snapshot.ownerId().equals(ownerUuid);
    }

    private String[] sanitizeToolIds(@Nullable String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toArray(String[]::new);
    }

    /** Process-local detail captured before canonical projection publication. */
    public record CapturedLinkedNpcSnapshot(
            UUID npcUuid,
            @Nullable UUID ownerId,
            String[] toolIds,
            @Nullable String roleId,
            @Nullable String displayName,
            @Nullable Vector3d lastKnownPosition,
            @Nullable Vector3d homePosition,
            long capturedAtMs
    ) {
        public CapturedLinkedNpcSnapshot {
            toolIds = toolIds == null
                    ? new String[0]
                    : toolIds.clone();
            lastKnownPosition = lastKnownPosition == null
                    ? null
                    : new Vector3d(lastKnownPosition);
            homePosition = homePosition == null
                    ? null
                    : new Vector3d(homePosition);
        }

        @Override
        public String[] toolIds() {
            return toolIds.clone();
        }

        @Override
        public Vector3d lastKnownPosition() {
            return lastKnownPosition == null
                    ? null
                    : new Vector3d(lastKnownPosition);
        }

        @Override
        public Vector3d homePosition() {
            return homePosition == null
                    ? null
                    : new Vector3d(homePosition);
        }

        public boolean containsToolId(String toolId) {
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
