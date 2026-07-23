package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for the complete production snapshot codec registry. */
class TameworkSnapshotCodecsTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("30000000-0000-0000-0000-000000000003");

    @Test
    void createsExactlyTheFiveSupportedKeysWithoutRuntimeComposition() {
        SnapshotCodecRegistry registry = TameworkSnapshotCodecs.create();
        LegacyDeathV1Payload death = new LegacyDeathV1SnapshotCodec().decode(
                "{\"diedAtMs\":-1,\"respawnAvailableAtMs\":-2}"
        );
        LegacyLostV1Payload lost = new LegacyLostV1SnapshotCodec().decode(
                "{\"lostAtMs\":-3}"
        );
        CoopResidentStateSnapshot full = fullState();

        assertRoundTrip(registry, TameworkSnapshotCodecs.DEATH, 1, LegacyDeathV1Payload.class, death);
        assertRoundTrip(registry, TameworkSnapshotCodecs.LOST, 1, LegacyLostV1Payload.class, lost);
        assertRoundTrip(registry, TameworkSnapshotCodecs.COOP, 1, CoopResidentStateSnapshot.class, full);
        assertRoundTrip(registry, TameworkSnapshotCodecs.DEATH, 2, CoopResidentStateSnapshot.class, full);
        assertRoundTrip(registry, TameworkSnapshotCodecs.LOST, 2, CoopResidentStateSnapshot.class, full);

        assertUnsupported(registry, TameworkSnapshotCodecs.COOP, 2, CoopResidentStateSnapshot.class);
        assertUnsupported(registry, TameworkSnapshotCodecs.DEATH, 3, CoopResidentStateSnapshot.class);
        assertUnsupported(registry, TameworkSnapshotCodecs.LOST, 99, CoopResidentStateSnapshot.class);
        assertUnsupported(registry, new SnapshotKind("arbitrary"), 1, LegacyLostV1Payload.class);
    }

    @Test
    void fullStateAdaptersPreserveSignedTimesAndReportMalformedTypes() {
        SnapshotCodecRegistry registry = TameworkSnapshotCodecs.create();
        SnapshotCodecRegistry.EncodedSnapshot encoded = registry.encode(
                TameworkSnapshotCodecs.DEATH,
                2,
                CoopResidentStateSnapshot.class,
                fullState()
        );

        SnapshotDecodeResult.Decoded<CoopResidentStateSnapshot> decoded = assertInstanceOf(
                SnapshotDecodeResult.Decoded.class,
                registry.decode(
                        snapshot(
                                TameworkSnapshotCodecs.DEATH,
                                2,
                                encoded.payloadJson()
                        ),
                        CoopResidentStateSnapshot.class
                )
        );
        assertEquals(-9_001L, decoded.value().capturedAtMs());

        SnapshotDecodeResult.Failed<CoopResidentStateSnapshot> malformed = assertInstanceOf(
                SnapshotDecodeResult.Failed.class,
                registry.decode(
                        snapshot(
                                TameworkSnapshotCodecs.DEATH,
                                2,
                                "{\"version\":\"1\",\"npcUuid\":\""
                                        + UUID.randomUUID()
                                        + "\",\"healthPercent\":\"invalid\"}"
                        ),
                        CoopResidentStateSnapshot.class
                )
        );
        assertEquals(SnapshotDecodeResult.Failure.DECODE_FAILED, malformed.failure());
    }

    @Test
    void registryRejectsWrongValueTypesAtTheExactKey() {
        SnapshotCodecRegistry registry = TameworkSnapshotCodecs.create();
        CompanionSnapshot death = snapshot(TameworkSnapshotCodecs.DEATH, 1, "{}");

        SnapshotDecodeResult.Failed<LegacyLostV1Payload> mismatch = assertInstanceOf(
                SnapshotDecodeResult.Failed.class,
                registry.decode(death, LegacyLostV1Payload.class)
        );

        assertEquals(SnapshotDecodeResult.Failure.TYPE_MISMATCH, mismatch.failure());
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.encode(
                        TameworkSnapshotCodecs.DEATH,
                        2,
                        LegacyDeathV1Payload.class,
                        new LegacyDeathV1SnapshotCodec().decode("{}")
                )
        );
    }

    private <T> void assertRoundTrip(SnapshotCodecRegistry registry,
                                     SnapshotKind kind,
                                     int version,
                                     Class<T> type,
                                     T value) {
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                registry.encode(kind, version, type, value);
        SnapshotDecodeResult.Decoded<T> decoded = assertInstanceOf(
                SnapshotDecodeResult.Decoded.class,
                registry.decode(snapshot(kind, version, encoded.payloadJson()), type)
        );
        assertEquals(value, decoded.value());
    }

    private <T> void assertUnsupported(SnapshotCodecRegistry registry,
                                       SnapshotKind kind,
                                       int version,
                                       Class<T> type) {
        SnapshotDecodeResult.Failed<T> result = assertInstanceOf(
                SnapshotDecodeResult.Failed.class,
                registry.decode(snapshot(kind, version, "{}"), type)
        );
        assertEquals(SnapshotDecodeResult.Failure.UNSUPPORTED_CODEC, result.failure());
    }

    private CompanionSnapshot snapshot(SnapshotKind kind, int version, String payloadJson) {
        return new CompanionSnapshot(
                SnapshotId.create(),
                PROFILE,
                kind,
                version,
                payloadJson,
                Sha256Hash.ofUtf8(payloadJson),
                LifecycleRevision.INITIAL,
                true,
                -1_000L
        );
    }

    private CoopResidentStateSnapshot fullState() {
        return new CoopResidentStateSnapshot(
                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                null,
                -1,
                "tamework_companion",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                37.5,
                -9_001L
        );
    }
}
