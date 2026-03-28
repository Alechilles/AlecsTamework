package com.alechilles.alecstamework.api;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record NpcLostRecordedEvent(@Nullable NpcProfileView profile,
                                   @Nonnull UUID npcUuid,
                                   @Nullable Vector3View lastKnownPosition,
                                   @Nullable Vector3View homePosition,
                                   long lastRelocationQueuedAtMs,
                                   long lostAtMs,
                                   int relocationRetryAttempts,
                                   long emittedAtMs) implements TameworkEvent {
}

