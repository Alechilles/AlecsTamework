package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact actor and inventory context for one captured-spawner release.
 *
 * <p>The receipt stack is the already-cleared empty-spawner value that replaces the source.</p>
 */
public record SpawnerCapturedArtifactReleaseIntent(
        @Nonnull String intentKey,
        @Nonnull UUID actorUuid,
        @Nonnull String worldKey,
        int sourceSlot,
        @Nonnull ItemStack sourceArtifactStack,
        @Nonnull ItemStack receiptArtifactStack,
        @Nullable OwnerId ownerAssignment,
        @Nullable String ownerAssignmentName
) {
    public SpawnerCapturedArtifactReleaseIntent {
        if (intentKey == null || intentKey.isBlank()
                || actorUuid == null || worldKey == null
                || worldKey.isBlank() || sourceSlot < 0
                || sourceArtifactStack == null
                || receiptArtifactStack == null) {
            throw new IllegalArgumentException(
                    "Complete captured-artifact release intent is required"
            );
        }
        intentKey = intentKey.trim();
        worldKey = worldKey.trim();
        ownerAssignmentName = ownerAssignmentName == null
                || ownerAssignmentName.isBlank()
                ? null
                : ownerAssignmentName.trim();
        if (ownerAssignment == null && ownerAssignmentName != null) {
            throw new IllegalArgumentException(
                    "Release owner name requires an owner assignment"
            );
        }
    }

    SpawnerCaptureReleaseContext frozenContext() {
        return new SpawnerCaptureReleaseContext(
                actorUuid, worldKey, sourceSlot
        );
    }
}
