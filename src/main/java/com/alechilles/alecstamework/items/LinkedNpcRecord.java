package com.alechilles.alecstamework.items;

import org.joml.Vector3d;
import java.util.UUID;

/**
 * Serialized linked-NPC metadata cached on command items.
 *
 * <p>This model intentionally remains small and mutable-free so it can be passed between
 * command-item orchestration and persistence helpers without introducing side effects.
 */
final class LinkedNpcRecord {
    final UUID npcUuid;
    final String profileId;
    final Vector3d lastKnownPosition;
    final String lastKnownWorldName;
    final Vector3d homePosition;
    final String cachedDisplayName;
    final String cachedNameKey;
    final String cachedRoleId;
    final String cachedCommandState;
    final boolean active;
    final boolean breedingEnabled;
    final String groupId;

    LinkedNpcRecord(UUID npcUuid,
                    Vector3d lastKnownPosition,
                    Vector3d homePosition,
                    String cachedDisplayName,
                    String cachedNameKey,
                    String cachedRoleId) {
        this(
                npcUuid,
                lastKnownPosition,
                homePosition,
                cachedDisplayName,
                cachedNameKey,
                cachedRoleId,
                null,
                true,
                false,
                null
        );
    }

    LinkedNpcRecord(UUID npcUuid,
                    Vector3d lastKnownPosition,
                    Vector3d homePosition,
                    String cachedDisplayName,
                    String cachedNameKey,
                    String cachedRoleId,
                    String cachedCommandState,
                    boolean active,
                    boolean breedingEnabled,
                    String groupId) {
        this(
                npcUuid,
                lastKnownPosition,
                null,
                homePosition,
                cachedDisplayName,
                cachedNameKey,
                cachedRoleId,
                cachedCommandState,
                active,
                breedingEnabled,
                groupId
        );
    }

    LinkedNpcRecord(UUID npcUuid,
                    Vector3d lastKnownPosition,
                    String lastKnownWorldName,
                    Vector3d homePosition,
                    String cachedDisplayName,
                    String cachedNameKey,
                    String cachedRoleId,
                    String cachedCommandState,
                    boolean active,
                    boolean breedingEnabled,
                    String groupId) {
        this(
                npcUuid,
                null,
                lastKnownPosition,
                lastKnownWorldName,
                homePosition,
                cachedDisplayName,
                cachedNameKey,
                cachedRoleId,
                cachedCommandState,
                active,
                breedingEnabled,
                groupId
        );
    }

    LinkedNpcRecord(UUID npcUuid,
                    String profileId,
                    Vector3d lastKnownPosition,
                    String lastKnownWorldName,
                    Vector3d homePosition,
                    String cachedDisplayName,
                    String cachedNameKey,
                    String cachedRoleId,
                    String cachedCommandState,
                    boolean active,
                    boolean breedingEnabled,
                    String groupId) {
        this.npcUuid = npcUuid;
        this.profileId = LinkedNpcRecordCodec.normalizeProfileId(profileId);
        this.lastKnownPosition = lastKnownPosition != null ? new Vector3d(lastKnownPosition) : null;
        this.lastKnownWorldName = (lastKnownWorldName != null && !lastKnownWorldName.isBlank())
                ? lastKnownWorldName.trim()
                : null;
        this.homePosition = homePosition != null ? new Vector3d(homePosition) : null;
        this.cachedDisplayName = (cachedDisplayName != null && !cachedDisplayName.isBlank()) ? cachedDisplayName : null;
        this.cachedNameKey = (cachedNameKey != null && !cachedNameKey.isBlank()) ? cachedNameKey : null;
        this.cachedRoleId = (cachedRoleId != null && !cachedRoleId.isBlank()) ? cachedRoleId : null;
        this.cachedCommandState = (cachedCommandState != null && !cachedCommandState.isBlank()) ? cachedCommandState : null;
        this.active = active;
        this.breedingEnabled = breedingEnabled;
        this.groupId = (groupId != null && !groupId.isBlank()) ? groupId : null;
    }
}

