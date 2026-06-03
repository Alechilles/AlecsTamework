package com.alechilles.alecstamework.items;

import org.joml.Vector3d;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Listener for relocation requests that were dropped after retry exhaustion.
 */
@FunctionalInterface
public interface CommandRelocationDropListener {
    void onRelocationDropped(UUID npcUuid,
                             @Nullable UUID ownerUuid,
                             @Nullable Vector3d sourceHintPosition,
                             @Nullable Vector3d alternateSourceHintPosition,
                             @Nullable Vector3d destination,
                             long queuedAtMs,
                             long droppedAtMs,
                             int retryAttempts);
}
