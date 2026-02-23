package com.alechilles.alecstamework.items;

import com.hypixel.hytale.math.vector.Vector3d;
import java.util.UUID;

/**
 * Serialized linked-NPC metadata cached on command items.
 *
 * <p>This model intentionally remains small and mutable-free so it can be passed between
 * command-item orchestration and persistence helpers without introducing side effects.
 */
final class LinkedNpcRecord {
    final UUID npcUuid;
    final Vector3d lastKnownPosition;
    final Vector3d homePosition;
    final String cachedDisplayName;
    final String cachedNameKey;
    final String cachedRoleId;

    LinkedNpcRecord(UUID npcUuid,
                    Vector3d lastKnownPosition,
                    Vector3d homePosition,
                    String cachedDisplayName,
                    String cachedNameKey,
                    String cachedRoleId) {
        this.npcUuid = npcUuid;
        this.lastKnownPosition = lastKnownPosition != null ? new Vector3d(lastKnownPosition) : null;
        this.homePosition = homePosition != null ? new Vector3d(homePosition) : null;
        this.cachedDisplayName = (cachedDisplayName != null && !cachedDisplayName.isBlank()) ? cachedDisplayName : null;
        this.cachedNameKey = (cachedNameKey != null && !cachedNameKey.isBlank()) ? cachedNameKey : null;
        this.cachedRoleId = (cachedRoleId != null && !cachedRoleId.isBlank()) ? cachedRoleId : null;
    }
}

