package com.alechilles.alecstamework.items.persistence;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression coverage for the released death-v1 and lost-v1 payload contracts. */
class LegacySnapshotV1CodecTest {
    private final LegacyDeathV1SnapshotCodec deathCodec =
            new LegacyDeathV1SnapshotCodec();
    private final LegacyLostV1SnapshotCodec lostCodec =
            new LegacyLostV1SnapshotCodec();

    @Test
    void deathRoundTripIsDeterministicAndPreservesSignedWorldTimes() {
        String raw = """
                {
                  "ownerId":"10000000-0000-0000-0000-000000000001",
                  "roleId":"Tamework_Dead",
                  "lastKnownPosition":{"x":1.25,"y":-2.5,"z":3.75},
                  "homePosition":{"x":-4.5,"y":5.25,"z":-6.75},
                  "diedAtMs":-260,
                  "respawnAvailableAtMs":-1000,
                  "breedingCooldownUntilMs":-2000,
                  "happinessLastUpdateMs":-3000,
                  "lifeStageBornAtMs":-4000,
                  "lifeStageAdolescentAtMs":-5000,
                  "lifeStageAdultAtMs":-6000,
                  "lifeStageFullyGrownAtMs":-7000,
                  "deathCauseKind":"ENVIRONMENT"
                }
                """;

        LegacyDeathV1Payload decoded = deathCodec.decode(raw);
        String encoded = deathCodec.encode(decoded);

        assertEquals(-260L, decoded.diedAtMs());
        assertEquals(-1000L, decoded.respawnAvailableAtMs());
        assertEquals(-2000L, decoded.breedingCooldownUntilMs());
        assertEquals(-3000L, decoded.happinessLastUpdateMs());
        assertEquals(-4000L, decoded.lifeStageBornAtMs());
        assertEquals(-5000L, decoded.lifeStageAdolescentAtMs());
        assertEquals(-6000L, decoded.lifeStageAdultAtMs());
        assertEquals(-7000L, decoded.lifeStageFullyGrownAtMs());
        assertEquals(new SnapshotVector3(1.25, -2.5, 3.75), decoded.lastKnownPosition());
        assertEquals(LegacyDeathV1Payload.DeathCauseKind.ENVIRONMENT, decoded.deathCauseKind());
        assertEquals(encoded, deathCodec.encode(decoded));
        assertEquals(decoded, deathCodec.decode(encoded));
    }

    @Test
    void deathMissingTimesUseStableSentinelsInsteadOfWallClockDefaults() {
        LegacyDeathV1Payload first = deathCodec.decode("{}");
        LegacyDeathV1Payload second = deathCodec.decode("{}");

        assertEquals(0L, first.diedAtMs());
        assertEquals(0L, first.respawnAvailableAtMs());
        assertEquals(first, second);
    }

    @Test
    void deathRejectsMalformedTypesAndNonFiniteVectors() {
        assertThrows(
                IllegalArgumentException.class,
                () -> deathCodec.decode("{\"diedAtMs\":\"-1\"}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> deathCodec.decode(
                        "{\"lastKnownPosition\":{\"x\":1e999,\"y\":0,\"z\":0}}"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> deathCodec.decode("{\"homePosition\":{\"x\":1,\"y\":2}}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> deathCodec.decode("{\"deathCauseKind\":\"NOT_REAL\"}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SnapshotVector3(Double.NaN, 0.0, 0.0)
        );
    }

    @Test
    void lostRoundTripPreservesSignedTimesWithoutEmbeddingSourceNpcIdentity() {
        UUID replacement = UUID.fromString("20000000-0000-0000-0000-000000000002");
        LegacyLostV1Payload decoded = lostCodec.decode("""
                {
                  "sourceNpcUuid":"00000000-0000-0000-0000-000000000004",
                  "lastKnownPosition":{"x":-1,"y":-2,"z":-3},
                  "lastRelocationQueuedAtMs":-11,
                  "lostAtMs":-12,
                  "relocationRetryAttempts":2,
                  "replacementNpcUuid":"20000000-0000-0000-0000-000000000002",
                  "recoveredAtMs":-13
                }
                """);

        String encoded = lostCodec.encode(decoded);

        assertEquals(-11L, decoded.lastRelocationQueuedAtMs());
        assertEquals(-12L, decoded.lostAtMs());
        assertEquals(-13L, decoded.recoveredAtMs());
        assertEquals(replacement, decoded.replacementNpcUuid());
        assertFalse(encoded.contains("sourceNpcUuid"));
        assertEquals(encoded, lostCodec.encode(decoded));
        assertEquals(decoded, lostCodec.decode(encoded));
    }

    @Test
    void lostUsesDeterministicDefaultsAndRejectsMalformedKnownFields() {
        LegacyLostV1Payload empty = lostCodec.decode("{}");

        assertEquals(0L, empty.lastRelocationQueuedAtMs());
        assertEquals(0L, empty.lostAtMs());
        assertEquals(0, empty.relocationRetryAttempts());
        assertEquals(0L, empty.recoveredAtMs());
        assertNull(empty.replacementNpcUuid());
        assertThrows(
                IllegalArgumentException.class,
                () -> lostCodec.decode("{\"relocationRetryAttempts\":1.5}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> lostCodec.decode("{\"replacementNpcUuid\":false}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> lostCodec.decode("[]")
        );
    }
}
