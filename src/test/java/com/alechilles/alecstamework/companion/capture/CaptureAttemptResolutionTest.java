package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaptureAttemptResolutionTest {
    @Test
    void exactTerminalResolutionRoundTrips() {
        CaptureAttemptResolution expected = failure();

        CaptureAttemptResolution actual =
                CaptureAttemptResolutionJsonCodec.decode(
                        CaptureAttemptResolutionJsonCodec.encode(expected)
                );

        assertEquals(expected, actual);
    }

    @Test
    void failedAttemptMustUseResolvedAttemptConsumption() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CaptureAttemptResolution(
                        UUID.randomUUID(),
                        "Wolf_Black",
                        formula(),
                        CaptureSourceConsumption.SUCCESS_ONLY,
                        CaptureSuccessDisposition.CAPTURED_ITEM,
                        CaptureAttemptResolution.Outcome.FAILED_ROLL,
                        "capture-probability-failure",
                        0.35D,
                        false,
                        0.5D,
                        0.8D,
                        42_000L
                )
        );
    }

    @Test
    void successCannotCarryFailureCooldown() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CaptureAttemptResolution(
                        UUID.randomUUID(),
                        "Wolf_Black",
                        formula(),
                        CaptureSourceConsumption.RESOLVED_ATTEMPT,
                        CaptureSuccessDisposition.TAME_AND_COMMAND_LINK,
                        CaptureAttemptResolution.Outcome.SUCCESS,
                        "capture-probability-success",
                        0.35D,
                        false,
                        0.5D,
                        0.1D,
                        42_000L
                )
        );
    }

    private CaptureAttemptResolution failure() {
        return new CaptureAttemptResolution(
                UUID.fromString("44b19a75-722c-4fbd-92c8-6b2c544d275c"),
                "Wolf_Black",
                formula(),
                CaptureSourceConsumption.RESOLVED_ATTEMPT,
                CaptureSuccessDisposition.TAME_AND_COMMAND_LINK,
                CaptureAttemptResolution.Outcome.FAILED_ROLL,
                "capture-probability-failure",
                0.35D,
                false,
                0.5D,
                0.8D,
                42_000L
        );
    }

    private CaptureAttemptFormula formula() {
        return new CaptureAttemptFormula(
                "HydragonSoulStone",
                7L,
                CaptureChanceMode.PROBABILITY,
                4,
                0.2D,
                0.1D,
                0.05D,
                0.95D,
                "HydragonDragonCapture",
                11L,
                2,
                0.1D,
                0.8D,
                0.5D,
                8,
                Sha256Hash.ofUtf8("requirements"),
                13L
        );
    }
}
