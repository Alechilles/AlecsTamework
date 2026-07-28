package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for rebuildable signed capture failure cooldowns. */
class CaptureAttemptCooldownIndexTest {
    private static final UUID ACTOR = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID FIRST = UUID.fromString(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final UUID SECOND = UUID.fromString(
            "20000000-0000-0000-0000-000000000002"
    );

    @Test
    void latestFailureBlocksOtherAttemptsUntilSignedDeadline() {
        CaptureAttemptCooldownIndex index =
                new CaptureAttemptCooldownIndex();

        assertEquals(
                ProjectionApplyOutcome.APPLIED,
                index.apply(event(1L, failed(FIRST, -100L)))
        );
        assertTrue(index.active(
                ACTOR, "dragon-stone", SECOND, -200L
        ).isPresent());
        assertTrue(index.active(
                ACTOR, "dragon-stone", FIRST, -200L
        ).isEmpty());
        assertTrue(index.active(
                ACTOR, "dragon-stone", SECOND, -100L
        ).isEmpty());

        index.apply(event(2L, failed(SECOND, 50L)));

        assertEquals(
                SECOND,
                index.active(
                        ACTOR, "dragon-stone", FIRST, 0L
                ).orElseThrow().attemptId()
        );
    }

    private ProjectionEvent event(
            long sequence,
            CaptureAttemptResolution resolution
    ) {
        return new ProjectionEvent(
                new ProjectionSequence(sequence),
                new OperationId(UUID.nameUUIDFromBytes(
                        ("operation-" + sequence).getBytes(
                                java.nio.charset.StandardCharsets.UTF_8
                        )
                )),
                CaptureAttemptResolutionEventCodec.EVENT_TYPE,
                "capture-attempt:" + resolution.attemptId(),
                1L,
                CaptureAttemptResolutionEventCodec.LEGACY_VERSION,
                CaptureAttemptResolutionEventCodec.encode(
                        ACTOR, resolution
                ),
                -500L
        );
    }

    private CaptureAttemptResolution failed(
            UUID attemptId,
            long cooldownUntilMs
    ) {
        return new CaptureAttemptResolution(
                attemptId,
                "dragon",
                new CaptureAttemptFormula(
                        "dragon-stone",
                        4L,
                        CaptureChanceMode.PROBABILITY,
                        1,
                        0.25D,
                        0.0D,
                        0.0D,
                        1.0D,
                        null,
                        0L,
                        0,
                        0.0D,
                        1.0D,
                        0.0D,
                        null,
                        Sha256Hash.ofUtf8("[]"),
                        0L
                ),
                CaptureSourceConsumption.RESOLVED_ATTEMPT,
                CaptureSuccessDisposition.CAPTURED_ITEM,
                CaptureAttemptResolution.Outcome.FAILED_ROLL,
                "capture-probability-failure",
                0.25D,
                false,
                0.5D,
                0.75D,
                cooldownUntilMs
        );
    }
}
