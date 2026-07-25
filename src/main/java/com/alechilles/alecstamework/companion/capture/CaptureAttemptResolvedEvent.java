package com.alechilles.alecstamework.companion.capture;

import java.util.UUID;
import javax.annotation.Nonnull;

/** Actor-qualified terminal capture evidence published by the canonical operation. */
public record CaptureAttemptResolvedEvent(
        @Nonnull UUID actorUuid,
        @Nonnull CaptureAttemptResolution resolution
) {
    public CaptureAttemptResolvedEvent {
        if (actorUuid == null || resolution == null) {
            throw new IllegalArgumentException(
                    "Complete resolved capture event is required"
            );
        }
    }
}
