package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Self-contained terminal capture evidence published by the canonical operation.
 *
 * <p>The complete operation request is retained so replay consumers never join mutable profile,
 * lifecycle, snapshot, inventory, or configuration state.</p>
 */
public record CaptureAttemptResolvedEvent(
        @Nullable OperationId operationId,
        @Nullable IdempotencyKey operationIdempotencyKey,
        @Nullable CompanionCaptureRequest request,
        long resolvedAtMs,
        @Nullable UUID legacyActorUuid,
        @Nullable CaptureAttemptResolution legacyResolution
) {
    public CaptureAttemptResolvedEvent {
        boolean complete = operationId != null
                && operationIdempotencyKey != null
                && request != null
                && legacyActorUuid == null
                && legacyResolution == null;
        boolean legacy = operationId == null
                && operationIdempotencyKey == null
                && request == null
                && legacyActorUuid != null
                && legacyResolution != null;
        if (!complete && !legacy) {
            throw new IllegalArgumentException(
                    "Complete resolved capture event is required"
            );
        }
    }

    /** Creates the version-three replay-safe event. */
    @Nonnull
    public static CaptureAttemptResolvedEvent complete(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey operationIdempotencyKey,
            @Nonnull CompanionCaptureRequest request,
            long resolvedAtMs
    ) {
        return new CaptureAttemptResolvedEvent(
                operationId, operationIdempotencyKey, request,
                resolvedAtMs, null, null
        );
    }

    /** Decodes the prior actor-and-roll-only payload without inventing absent facts. */
    @Nonnull
    public static CaptureAttemptResolvedEvent legacy(
            @Nonnull UUID actorUuid,
            @Nonnull CaptureAttemptResolution resolution
    ) {
        return new CaptureAttemptResolvedEvent(
                null, null, null, 0L, actorUuid, resolution
        );
    }

    public boolean replayComplete() {
        return request != null;
    }

    @Nonnull
    public UUID actorUuid() {
        return replayComplete()
                ? request.source().actorUuid()
                : legacyActorUuid;
    }

    @Nonnull
    public CaptureAttemptResolution resolution() {
        return replayComplete()
                ? request.resolution()
                : legacyResolution;
    }
}
