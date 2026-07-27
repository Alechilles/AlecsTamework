package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionCaptureResolvedEvent;
import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.config.bonded
        .BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteBondedCompanionDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Durable bonded capture completion and restart-recovery contract. */
class BondedCompanionCaptureCompletionTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");
    private static final UUID ATTEMPT = UUID.fromString(
            "30000000-0000-0000-0000-000000000003");
    private static final String ROSTER = "hydragon:companions";
    private static final String PROFILE = "profile-1";

    @TempDir Path temporaryDirectory;

    /** Regression: restart recovery must not need a generic profile lookup. */
    @Test
    void captureCommitRetainsExactLookupEvidenceInItsOperationTombstone() {
        SqliteBondedCompanionDatabase database = database();

        var result = database.createCapturedProfile(
                operation(), profile(), cleanup(), 4, evidence()
        );
        var found = database.findCaptureEvidence(OWNER, ROSTER, SOURCE);

        assertEquals(BondedCompanionStoreResult.Code.APPLIED, result.code());
        assertTrue(found.isPresent());
        assertEquals(evidence(), found.orElseThrow());
        assertTrue(database.findCaptureEvidence(
                OWNER, ROSTER, UUID.randomUUID()).isEmpty());
    }

    /** A crash after commit is recovered by replaying the retained event once. */
    @Test
    void pendingCommittedCapturePublishesAfterRestartAndCheckpointsDelivery() {
        SqliteBondedCompanionDatabase database = database();
        database.createCapturedProfile(
                operation(), profile(), cleanup(), 4, evidence()
        );
        SqliteBondedCompanionDatabase restarted = database();
        List<BondedCompanionCaptureResolvedEvent> events = new ArrayList<>();
        BondedCompanionCaptureEventPublisher publisher =
                new BondedCompanionCaptureEventPublisher(
                        restarted, events::add, () -> 11_000L
                );

        int first = publisher.publishPending(16);
        int replay = publisher.publishPending(16);

        assertEquals(1, first);
        assertEquals(0, replay);
        assertEquals(1, events.size());
        BondedCompanionCaptureResolvedEvent event = events.getFirst();
        assertEquals(11_000L, event.emittedAtMs());
        assertEquals(SOURCE, event.capture().sourceNpcUuid());
        assertEquals(PROFILE, event.capture().profileId());
        assertEquals(CaptureSuccessDisposition.STORE_BONDED_COMPANION,
                event.capture().successDisposition());
        assertEquals(CaptureAttemptOutcome.CAPTURED,
                event.capture().outcome());
    }

    /** A failed live sink must leave the durable event available to retry. */
    @Test
    void failedDeliveryDoesNotAdvanceThePublicationCheckpoint() {
        SqliteBondedCompanionDatabase database = database();
        database.createCapturedProfile(
                operation(), profile(), cleanup(), 4, evidence()
        );
        BondedCompanionCaptureEventPublisher failingPublisher =
                new BondedCompanionCaptureEventPublisher(
                        database,
                        ignored -> {
                            throw new IllegalStateException("sink-offline");
                        },
                        () -> 10_500L
                );

        assertEquals(0, failingPublisher.publishPending(16));

        List<BondedCompanionCaptureResolvedEvent> recovered =
                new ArrayList<>();
        BondedCompanionCaptureEventPublisher restartedPublisher =
                new BondedCompanionCaptureEventPublisher(
                        database, recovered::add, () -> 11_000L
                );
        assertEquals(1, restartedPublisher.publishPending(16));
        assertEquals(1, recovered.size());
        assertEquals(evidence().operationId(),
                recovered.getFirst().capture().operationId());
        assertEquals(0, restartedPublisher.publishPending(16));
    }

    /** Profile-lifetime capture evidence survives bounded operation pruning. */
    @Test
    void captureLookupSurvivesOperationRetention() {
        SqliteBondedCompanionDatabase database = database();
        database.createCapturedProfile(
                operation(), profile(), cleanup(), 4, evidence()
        );

        int pruned = database.pruneOperations(20_001L, 16);

        assertEquals(1, pruned);
        assertTrue(database.findCaptureEvidence(
                OWNER, ROSTER, SOURCE).isPresent());
    }

    @Test
    void publicApiFindsExactCaptureWithoutGenericProfileAuthority() {
        SqliteBondedCompanionDatabase database = database();
        database.createCapturedProfile(
                operation(), profile(), cleanup(), 4, evidence()
        );
        TameworkBondedCompanionComposition composition =
                TameworkBondedCompanionComposition.open(
                        temporaryDirectory,
                        new BondedCompanionRosterRegistry(), null,
                        () -> 10_500L
                );
        try {
            var found = composition.api().findCapture(
                    OWNER, ROSTER, SOURCE).join();
            var absent = composition.api().findCapture(
                    OWNER, ROSTER, UUID.randomUUID()).join();

            assertEquals(BondedCompanionResultCode.SUCCESS, found.code());
            assertEquals(PROFILE, found.value().profileId());
            assertEquals(BondedCompanionResultCode.NOT_FOUND, absent.code());
        } finally {
            composition.close();
        }
    }

    private SqliteBondedCompanionDatabase database() {
        Path path = temporaryDirectory.resolve("bonded-companions.sqlite");
        assertTrue(new BondedCompanionSchemaManager(path, () -> 10_000L)
                .initialize().availability().available());
        return new SqliteBondedCompanionDatabase(path);
    }

    private BondedCompanionOperation operation() {
        return new BondedCompanionOperation(
                "spawner-bonded-capture:v1", "capture-key", "a".repeat(64),
                OWNER, ROSTER, PROFILE, BondedCompanionOperation.Type.CAPTURE,
                10_000L, 20_000L
        );
    }

    private BondedCompanionCaptureEvidence evidence() {
        return new BondedCompanionCaptureEvidence(
                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                ATTEMPT, OWNER, ROSTER, "hydragon:full_dragons", SOURCE,
                PROFILE, "Dragon_Fire", "spawner-bonded-capture:v1",
                "capture-key", "HyDragon_Draconic_Stone",
                "HyDragon_Draconic_Stone", 7L, null, -1L,
                CaptureSourceConsumption.SUCCESS_ONLY,
                CaptureSuccessDisposition.STORE_BONDED_COMPANION,
                CaptureAttemptOutcome.CAPTURED, "capture-success", "world",
                10_000L
        );
    }

    private BondedCompanionRecord.Profile profile() {
        return new BondedCompanionRecord.Profile(
                PROFILE, OWNER, ROSTER, "hydragon:full_dragons",
                "Dragon_Fire", BondedCompanionState.STORED, 0L,
                BondedCompanionPayload.of(
                        "snapshot".getBytes(StandardCharsets.UTF_8)),
                10_000L, 10_000L, Map.of(), null, "Fire Dragon", null,
                null, 0L, 0L, null, null
        );
    }

    private BondedCompanionRecord.Cleanup cleanup() {
        return new BondedCompanionRecord.Cleanup(
                PROFILE + ":capture-source", OWNER, ROSTER, PROFILE, null,
                BondedCompanionRecord.CleanupTarget.SOURCE, SOURCE, "world",
                "capture", BondedCompanionRecord.CleanupState.PENDING, 0,
                10_000L, 10_000L, 20_000L
        );
    }
}
