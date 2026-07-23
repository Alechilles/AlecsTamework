package com.alechilles.alecstamework.companion.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Round-trip tests for alias rotation intent and durable projection evidence. */
class CompanionAliasRotationCodecTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS =
            NpcAlias.parse("30000000-0000-0000-0000-000000000001");

    @Test
    void intentRoundTripsSignedTime() {
        CompanionAliasRotation rotation =
                new CompanionAliasRotation(PROFILE, ALIAS, -9_000);

        assertEquals(
                rotation,
                CompanionAliasRotationDefinition.INSTANCE.decode(
                        CompanionAliasRotationDefinition.INSTANCE.encode(rotation)
                )
        );
    }

    @Test
    void outcomeRoundTripsAndRejectsUnknownVersion() {
        CompanionAliasRotationOutcome outcome =
                new CompanionAliasRotationOutcome(PROFILE, ALIAS, 3, -8_000);
        String encoded = CompanionAliasRotationEventCodec.encode(outcome);

        assertEquals(
                outcome,
                CompanionAliasRotationEventCodec.decode(
                        CompanionAliasRotationEventCodec.VERSION,
                        encoded
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CompanionAliasRotationEventCodec.decode(2, encoded)
        );
    }
}
