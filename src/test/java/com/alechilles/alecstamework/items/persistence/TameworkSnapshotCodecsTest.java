package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
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
    void createsExactlyTheSixSupportedKeysWithoutRuntimeComposition() {
        SnapshotCodecRegistry registry = TameworkSnapshotCodecs.create();
        LegacyDeathV1Payload death = new LegacyDeathV1SnapshotCodec().decode(
                "{\"diedAtMs\":-1,\"respawnAvailableAtMs\":-2}"
        );
        LegacyLostV1Payload lost = new LegacyLostV1SnapshotCodec().decode(
                "{\"lostAtMs\":-3}"
        );
        CoopResidentStateSnapshot full = fullState();
        DeathSnapshotV2Payload modernDeath = DeathSnapshotV2Payload.capture(
                full,
                -101L,
                -202L,
                DeathSnapshotV2Payload.DeathCauseKind.NPC,
                "Razorbeak"
        );

        assertRoundTrip(registry, TameworkSnapshotCodecs.DEATH, 1, LegacyDeathV1Payload.class, death);
        assertRoundTrip(registry, TameworkSnapshotCodecs.LOST, 1, LegacyLostV1Payload.class, lost);
        assertRoundTrip(registry, TameworkSnapshotCodecs.COOP, 1, CoopResidentStateSnapshot.class, full);
        assertRoundTrip(registry, TameworkSnapshotCodecs.DEATH, 2, DeathSnapshotV2Payload.class, modernDeath);
        assertRoundTrip(registry, CompanionFullStateProjection.KIND, CompanionFullStateProjection.VERSION, CoopResidentStateSnapshot.class, full);
        assertRoundTrip(registry, TameworkSnapshotCodecs.LOST, 2, CoopResidentStateSnapshot.class, full);

        assertUnsupported(registry, TameworkSnapshotCodecs.COOP, 2, CoopResidentStateSnapshot.class);
        assertUnsupported(registry, TameworkSnapshotCodecs.DEATH, 3, DeathSnapshotV2Payload.class);
        assertUnsupported(registry, CompanionFullStateProjection.KIND, CompanionFullStateProjection.VERSION + 1, CoopResidentStateSnapshot.class);
        assertUnsupported(registry, TameworkSnapshotCodecs.LOST, 99, CoopResidentStateSnapshot.class);
        assertUnsupported(registry, new SnapshotKind("arbitrary"), 1, LegacyLostV1Payload.class);
    }

    @Test
    void fullStateAdaptersPreserveSignedTimesAndReportMalformedTypes() {
        SnapshotCodecRegistry registry = TameworkSnapshotCodecs.create();
        SnapshotCodecRegistry.EncodedSnapshot encoded = registry.encode(
                CompanionFullStateProjection.KIND,
                CompanionFullStateProjection.VERSION,
                CoopResidentStateSnapshot.class,
                fullState()
        );

        SnapshotDecodeResult.Decoded<CoopResidentStateSnapshot> decoded = assertInstanceOf(
                SnapshotDecodeResult.Decoded.class,
                registry.decode(
                        snapshot(
                                CompanionFullStateProjection.KIND,
                                CompanionFullStateProjection.VERSION,
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
                                CompanionFullStateProjection.KIND,
                                CompanionFullStateProjection.VERSION,
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

    @Test
    void deathV2EnvelopeHasStableGoldenShapeAndFreezesMutableState() {
        TameworkOwnerComponent owner = new TameworkOwnerComponent(
                UUID.fromString("50000000-0000-0000-0000-000000000005"),
                "Original"
        );
        CoopResidentStateSnapshot state = new CoopResidentStateSnapshot(
                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                null,
                -1,
                "tamework_companion",
                null,
                owner,
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
        DeathSnapshotV2Payload payload = DeathSnapshotV2Payload.capture(
                state,
                -260L,
                -1_000L,
                DeathSnapshotV2Payload.DeathCauseKind.NPC,
                "Razorbeak"
        );
        owner.setOwnerName("Mutated");

        SnapshotCodecRegistry.EncodedSnapshot encoded =
                TameworkSnapshotCodecs.create().encode(
                        TameworkSnapshotCodecs.DEATH,
                        2,
                        DeathSnapshotV2Payload.class,
                        payload
                );

        assertEquals(
                "{\"fullState\":{\"version\":\"1\","
                        + "\"npcUuid\":\"40000000-0000-0000-0000-000000000004\","
                        + "\"coopId\":null,\"residentSlot\":-1,"
                        + "\"roleId\":\"tamework_companion\","
                        + "\"capturedAtMs\":-9001,"
                        + "\"owner\":{\"ownerId\":\"50000000-0000-0000-0000-000000000005\","
                        + "\"ownerName\":\"Original\"},\"healthPercent\":37.5},"
                        + "\"diedAtMs\":-260,\"respawnAvailableAtMs\":-1000,"
                        + "\"deathCauseKind\":\"NPC\",\"deathSourceName\":\"Razorbeak\"}",
                encoded.payloadJson()
        );
        assertEquals("Original", payload.fullState().owner().getOwnerName());
        TameworkOwnerComponent returned = payload.fullState().owner();
        returned.setOwnerName("Also mutated");
        assertEquals("Original", payload.fullState().owner().getOwnerName());
    }

    @Test
    void deathV2RejectsBareStateAndMissingDeathFacts() {
        SnapshotCodecRegistry registry = TameworkSnapshotCodecs.create();
        String fullStateJson = new com.alechilles.alecstamework.items
                .CoopResidentStateSnapshotCodec().encode(fullState());
        SnapshotDecodeResult.Failed<DeathSnapshotV2Payload> bare =
                assertInstanceOf(
                        SnapshotDecodeResult.Failed.class,
                        registry.decode(
                                snapshot(
                                        TameworkSnapshotCodecs.DEATH,
                                        2,
                                        fullStateJson
                                ),
                                DeathSnapshotV2Payload.class
                        )
                );
        SnapshotDecodeResult.Failed<DeathSnapshotV2Payload> missingDeadline =
                assertInstanceOf(
                        SnapshotDecodeResult.Failed.class,
                        registry.decode(
                                snapshot(
                                        TameworkSnapshotCodecs.DEATH,
                                        2,
                                        "{\"fullState\":" + fullStateJson
                                                + ",\"diedAtMs\":-260}"
                                ),
                                DeathSnapshotV2Payload.class
                        )
                );
        SnapshotDecodeResult.Failed<DeathSnapshotV2Payload> missingCause =
                assertInstanceOf(
                        SnapshotDecodeResult.Failed.class,
                        registry.decode(
                                snapshot(
                                        TameworkSnapshotCodecs.DEATH,
                                        2,
                                        "{\"fullState\":" + fullStateJson
                                                + ",\"diedAtMs\":-260,"
                                                + "\"respawnAvailableAtMs\":-1000}"
                                ),
                                DeathSnapshotV2Payload.class
                        )
                );

        assertEquals(SnapshotDecodeResult.Failure.DECODE_FAILED, bare.failure());
        assertEquals(
                SnapshotDecodeResult.Failure.DECODE_FAILED,
                missingDeadline.failure()
        );
        assertEquals(
                SnapshotDecodeResult.Failure.DECODE_FAILED,
                missingCause.failure()
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
