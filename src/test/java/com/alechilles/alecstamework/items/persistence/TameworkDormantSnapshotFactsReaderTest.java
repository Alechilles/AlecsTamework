package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for pure released restoration-policy facts. */
class TameworkDormantSnapshotFactsReaderTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("30000000-0000-0000-0000-000000000003");
    private final TameworkDormantSnapshotFactsReader reader =
            new TameworkDormantSnapshotFactsReader();

    @Test
    void readsSignedLegacyDeathDeadlineWithoutClockAssumptions() {
        String payload = new LegacyDeathV1SnapshotCodec().encode(
                new LegacyDeathV1SnapshotCodec().decode(
                        "{\"diedAtMs\":-260,"
                                + "\"respawnAvailableAtMs\":-1000,"
                                + "\"deathCauseKind\":\"NPC\","
                                + "\"deathSourceName\":\"Razorbeak\"}"
                )
        );

        TameworkDormantSnapshotFactsReader.Facts facts =
                reader.read(snapshot(TameworkSnapshotCodecs.DEATH, 1, payload, -10L))
                        .facts();

        assertEquals(LifecycleState.DEAD_REVIVABLE, facts.state());
        assertEquals(-260L, facts.observedAtMs());
        assertEquals(-1000L, facts.restorationAvailableAtMs());
        assertEquals("NPC", facts.deathCauseKind());
        assertEquals("Razorbeak", facts.deathSourceName());
    }

    @Test
    void readsModernDeathDeadlineAndCause() {
        DeathSnapshotV2Payload value = DeathSnapshotV2Payload.capture(
                fullState(),
                -500L,
                -200L,
                DeathSnapshotV2Payload.DeathCauseKind.ENVIRONMENT,
                null
        );
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                TameworkSnapshotCodecs.create().encode(
                        TameworkSnapshotCodecs.DEATH,
                        2,
                        DeathSnapshotV2Payload.class,
                        value
                );

        TameworkDormantSnapshotFactsReader.Facts facts =
                reader.read(snapshot(
                        TameworkSnapshotCodecs.DEATH,
                        2,
                        encoded.payloadJson(),
                        -10L
                )).facts();

        assertEquals(-200L, facts.restorationAvailableAtMs());
        assertEquals("ENVIRONMENT", facts.deathCauseKind());
        assertNull(facts.deathSourceName());
    }

    @Test
    void lostIsImmediatelyRestorableAndPreservesObservationEvidence() {
        String legacy = new LegacyLostV1SnapshotCodec().encode(
                new LegacyLostV1Payload(null, null, -20L, -12L, 2, null, 0L)
        );
        SnapshotCodecRegistry.EncodedSnapshot modern =
                TameworkSnapshotCodecs.create().encode(
                        TameworkSnapshotCodecs.LOST,
                        2,
                        CoopResidentStateSnapshot.class,
                        fullState()
                );

        TameworkDormantSnapshotFactsReader.Facts legacyFacts =
                reader.read(snapshot(
                        TameworkSnapshotCodecs.LOST,
                        1,
                        legacy,
                        -11L
                )).facts();
        TameworkDormantSnapshotFactsReader.Facts modernFacts =
                reader.read(snapshot(
                        TameworkSnapshotCodecs.LOST,
                        2,
                        modern.payloadJson(),
                        -9L
                )).facts();

        assertEquals(-12L, legacyFacts.observedAtMs());
        assertEquals(-9L, modernFacts.observedAtMs());
        assertEquals(0L, legacyFacts.restorationAvailableAtMs());
        assertEquals(0L, modernFacts.restorationAvailableAtMs());
    }

    @Test
    void rejectsNonDormantAndMalformedPayloadsExplicitly() {
        TameworkDormantSnapshotFactsReader.ReadResult nonDormant =
                reader.read(snapshot(new SnapshotKind("capture"), 1, "{}", 1L));
        TameworkDormantSnapshotFactsReader.ReadResult malformed =
                reader.read(snapshot(
                        TameworkSnapshotCodecs.DEATH,
                        2,
                        "{}",
                        1L
                ));

        assertEquals(
                TameworkDormantSnapshotFactsReader.Failure.NOT_DORMANT,
                nonDormant.failure()
        );
        assertEquals(
                TameworkDormantSnapshotFactsReader.Failure.DECODE_FAILED,
                malformed.failure()
        );
        assertTrue(!nonDormant.successful());
        assertTrue(!malformed.successful());
    }

    private CompanionSnapshot snapshot(
            SnapshotKind kind,
            int version,
            String payload,
            long createdAtMs
    ) {
        return new CompanionSnapshot(
                SnapshotId.create(),
                PROFILE,
                kind,
                version,
                payload,
                Sha256Hash.ofUtf8(payload),
                LifecycleRevision.INITIAL,
                true,
                createdAtMs
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
