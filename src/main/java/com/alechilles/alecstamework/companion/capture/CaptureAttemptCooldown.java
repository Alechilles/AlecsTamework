package com.alechilles.alecstamework.companion.capture;

import java.util.UUID;
import javax.annotation.Nonnull;

/** Latest actor/config failure cooldown derived from the durable capture outbox. */
public record CaptureAttemptCooldown(
        @Nonnull UUID actorUuid,
        @Nonnull String itemConfigId,
        @Nonnull UUID attemptId,
        long cooldownUntilMs,
        long projectionSequence
) {
    public CaptureAttemptCooldown {
        if (actorUuid == null || attemptId == null
                || itemConfigId == null || itemConfigId.isBlank()
                || projectionSequence <= 0L) {
            throw new IllegalArgumentException(
                    "Complete capture cooldown projection is required"
            );
        }
        itemConfigId = itemConfigId.trim();
    }

    public boolean activeAt(long nowMs) {
        return nowMs < cooldownUntilMs;
    }
}
