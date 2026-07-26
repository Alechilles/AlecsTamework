package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicyResolver;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionTransitionService;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.SpawnerPublishedEffect;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionCapturePersistenceAdapter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionProjectionDurability;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPayload;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionSchemaManager;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStoreResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;
import com.hypixel.hytale.codec.ExtraInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BondedCompanionCapturePipelineTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");
    @TempDir Path tempDir;

    @Test
    void durableProfileAndCleanupPrecedeItemSpendAndEffects() {
        Harness harness = new Harness();

        var result = harness.author.capture(intent(snapshot(), true, true,
                true, true, true));

        assertEquals(BondedCompanionCaptureAuthor.Status.APPLIED, result.status());
        assertEquals(List.of("policy", "persist", "cleanup", "effect", "spend"),
                harness.events);
    }

    @Test
    void chanceFailureLeavesSourceAndItemUntouched() {
        assertRejected(intent(snapshot(), false, true, true, true, true),
                BondedCompanionCaptureAuthor.Status.CHANCE_FAILED);
    }

    @Test
    void nonTranquilizedTargetLeavesSourceAndItemUntouched() {
        assertRejected(intent(snapshot(), true, false, true, true, true),
                BondedCompanionCaptureAuthor.Status.TRANQUILIZED_REQUIRED);
    }

    @Test
    void inaccessibleToolLeavesSourceAndItemUntouched() {
        assertRejected(intent(snapshot(), true, true, false, true, true),
                BondedCompanionCaptureAuthor.Status.TOOL_ACCESS_REQUIRED);
    }

    @Test
    void foreignOwnerLeavesSourceAndItemUntouched() {
        assertRejected(intent(snapshot(), true, true, true, false, true),
                BondedCompanionCaptureAuthor.Status.OWNER_DENIED);
    }

    @Test
    void invalidRoleLeavesSourceAndItemUntouched() {
        assertRejected(intent(snapshot(), true, true, true, true, false),
                BondedCompanionCaptureAuthor.Status.ROLE_DENIED);
    }

    @Test
    void capacityRejectionLeavesSourceAndItemUntouched() {
        Harness harness = new Harness();
        harness.policy = BondedCompanionCaptureAuthor.PolicyDecision.CAPACITY_REJECTED;

        var result = harness.author().capture(validIntent());

        assertEquals(BondedCompanionCaptureAuthor.Status.CAPACITY_REJECTED,
                result.status());
        assertEquals(List.of("policy", "message"), harness.events);
    }

    @Test
    void snapshotFailureLeavesSourceAndItemUntouched() {
        assertRejected(intent(null, true, true, true, true, true),
                BondedCompanionCaptureAuthor.Status.SNAPSHOT_FAILED);
    }

    @Test
    void databaseFailureLeavesSourceAndItemUntouched() {
        Harness harness = new Harness();
        harness.persistence = BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED;

        var result = harness.author().capture(validIntent());

        assertEquals(BondedCompanionCaptureAuthor.Status.DATABASE_FAILED,
                result.status());
        assertEquals(List.of("policy", "persist", "message"), harness.events);
    }

    @Test
    void repeatedInteractionDoesNotSpendOrReplayEffects() {
        Harness harness = new Harness();
        harness.persistence = BondedCompanionCaptureAuthor.PersistenceOutcome.REPLAYED;

        var result = harness.author().capture(validIntent());

        assertEquals(BondedCompanionCaptureAuthor.Status.REPLAYED, result.status());
        assertEquals(List.of("policy", "persist", "message"), harness.events);
    }

    /** Regression: accepted scheduling is not completed item finalization. */
    @Test
    void itemFinalizationFailureIsDurableButNotReportedAsApplied() {
        Harness harness = new Harness();
        harness.spendSuccessful = false;

        var result = harness.author().capture(validIntent());

        assertEquals("FINALIZATION_FAILED", result.status().name());
        assertTrue(result.durable());
        assertEquals(List.of(
                "policy", "persist", "cleanup", "effect", "spend", "message"
        ), harness.events);
    }

    @Test
    void unavailablePolicyIsNotMisreportedAsRoleDenial() {
        Harness harness = new Harness();
        harness.policy = BondedCompanionCaptureAuthor.PolicyDecision.REJECTED;

        var result = harness.author().capture(validIntent());

        assertEquals("POLICY_UNAVAILABLE", result.status().name());
        assertEquals(List.of("policy", "diagnostic", "message"),
                harness.events);
    }

    @Test
    void policyExceptionProducesOneUnavailableDiagnosticAndMessage() {
        Harness harness = new Harness();
        harness.policyFailure = new IllegalStateException("policy offline");

        var result = harness.author().capture(validIntent());

        assertEquals(BondedCompanionCaptureAuthor.Status.POLICY_UNAVAILABLE,
                result.status());
        assertEquals(List.of("policy", "diagnostic", "message"),
                harness.events);
    }

    @Test
    void prefreezeAdmissionRejectionProducesOneActionableMessage() {
        Harness harness = new Harness();

        var result = harness.author.reject(
                BondedCompanionCaptureAuthor.Status.ADMISSION_DENIED, null);

        assertEquals(BondedCompanionCaptureAuthor.Status.ADMISSION_DENIED,
                result.status());
        assertEquals(List.of("message"), harness.events);
    }

    @Test
    void interruptedSourceCleanupStillFinalizesDurableSuccessOnce() {
        Harness harness = new Harness();
        harness.cleanup = BondedCompanionCaptureAuthor.CleanupOutcome.RETRY_PENDING;

        var result = harness.author().capture(validIntent());

        assertEquals(BondedCompanionCaptureAuthor.Status.APPLIED, result.status());
        assertEquals(BondedCompanionCaptureAuthor.CleanupOutcome.RETRY_PENDING,
                result.cleanupOutcome());
        assertEquals(List.of("policy", "persist", "cleanup", "effect", "spend"),
                harness.events);
    }

    @Test
    void invalidTargetIsRejectedBeforePolicyAndPersistence() {
        var source = validIntent();
        var invalid = new BondedCompanionCaptureIntent(
                source.callerNamespace(), source.idempotencyKey(), source.actorUuid(),
                source.worldKey(), source.hotbarSlot(), source.sourceFingerprint(),
                source.sourceNpcUuid(), source.roleId(), source.rosterId(),
                source.rosterRevision(), source.snapshot(), source.completionEffect(),
                false, true, true, true, true, true);
        assertRejected(invalid, BondedCompanionCaptureAuthor.Status.TARGET_INVALID);
    }

    @Test
    void factoryRoutePreservesLiveOwnerAndRosterRoleEvidence() {
        BondedCompanionCaptureIntent intent =
                SpawnerCaptureIntentFactory.freezeBonded(
                        new SpawnerCaptureIntentFactory.FrozenBondedCapture(
                                "spawner-bonded-capture:v1", "attempt", OWNER,
                                "world", 2, "fingerprint", SOURCE,
                                "Dragon_Fire", "hydragon:companions", 4L,
                                snapshot(), null, true, true, true, true,
                                false, false
                        )
                );
        Harness harness = new Harness();

        var result = harness.author.capture(intent);

        assertFalse(intent.ownerAllowed());
        assertFalse(intent.roleAllowed());
        assertEquals(BondedCompanionCaptureAuthor.Status.OWNER_DENIED,
                result.status());
        assertEquals(List.of("message"), harness.events);
    }

    @Test
    void sqliteCommitAtomicallyCreatesProfileCleanupAndReplayReceipt() {
        Path path = tempDir.resolve("bonded-companions.sqlite");
        assertTrue(new BondedCompanionSchemaManager(path, () -> 10L)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase database =
                new SqliteBondedCompanionDatabase(path);
        String encoded = new BondedCompanionSnapshotCodec().encode(snapshot());
        var profile = new BondedCompanionRecord.Profile(
                "profile", OWNER, "hydragon:companions", "hydragon", "Dragon_Fire",
                BondedCompanionState.STORED, 0L,
                BondedCompanionPayload.of(encoded.getBytes(StandardCharsets.UTF_8)),
                10L, 10L, Map.of("policyRevision", "4"), null, null, null,
                null, 0L, 0L, null, null);
        var cleanup = new BondedCompanionRecord.Cleanup(
                "profile:capture-source", OWNER, "hydragon:companions", "profile",
                null, BondedCompanionRecord.CleanupTarget.SOURCE, SOURCE, "world",
                "capture", BondedCompanionRecord.CleanupState.PENDING,
                0, 10L, 10L, 300_010L);
        var operation = new BondedCompanionOperation(
                "spawner-bonded-capture:v1", "key", "a".repeat(64), OWNER,
                "hydragon:companions", "profile", BondedCompanionOperation.Type.CAPTURE,
                10L, 300_010L);

        var first = database.createCapturedProfile(operation, profile, cleanup, 3);
        var replay = database.createCapturedProfile(operation, profile, cleanup, 3);

        assertEquals(BondedCompanionStoreResult.Code.APPLIED, first.code());
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(1, database.listProfiles(OWNER, "hydragon:companions").size());
        assertEquals(1, database.listCleanup(OWNER, "hydragon:companions", 10).size());
    }

    /** Regression: a retry re-reads the NPC later but retains request identity. */
    @Test
    void realAdapterReplaysSameCaptureWhenFreshSnapshotTimestampDiffers()
            throws Exception {
        Path path = tempDir.resolve("fresh-snapshot-replay.sqlite");
        assertTrue(new BondedCompanionSchemaManager(path, () -> 10L)
                .initialize().availability().available());
        BondedCompanionRosterRegistry rosters = rosterRegistry();
        SqliteBondedCompanionDatabase database =
                new SqliteBondedCompanionDatabase(path);
        var adapter = new SqliteBondedCompanionCapturePersistenceAdapter(
                rosters,
                new BondedCompanionTransitionService(
                        new BondedCompanionPolicyResolver(rosters)),
                database, database,
                new SqliteBondedCompanionProjectionDurability(path),
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService
                                .Outcome.RETRY_REQUIRED)
        );
        BondedCompanionCaptureIntent first = frozenIntentAt(10L);
        BondedCompanionCaptureIntent retry = frozenIntentAt(20L);

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                adapter.store(first));
        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.REPLAYED,
                adapter.store(retry));
        assertEquals(1, database.listProfiles(
                OWNER, "hydragon:companions").size());
        assertEquals(1, database.listCleanup(
                OWNER, "hydragon:companions", 10).size());
    }

    @Test
    void realAdapterSeparatesRosterRoleDenialFromMissingPolicy()
            throws Exception {
        Path path = tempDir.resolve("policy-classification.sqlite");
        assertTrue(new BondedCompanionSchemaManager(path, () -> 10L)
                .initialize().availability().available());
        BondedCompanionRosterRegistry rosters = rosterRegistry();
        SqliteBondedCompanionDatabase database =
                new SqliteBondedCompanionDatabase(path);
        var adapter = new SqliteBondedCompanionCapturePersistenceAdapter(
                rosters,
                new BondedCompanionTransitionService(
                        new BondedCompanionPolicyResolver(rosters)),
                database, database,
                new SqliteBondedCompanionProjectionDurability(path),
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService
                                .Outcome.RETRY_REQUIRED)
        );
        var allowed = frozenIntentAt(10L);
        var wrongRole = new BondedCompanionCaptureIntent(
                allowed.callerNamespace(), "wrong-role", allowed.actorUuid(),
                allowed.worldKey(), allowed.hotbarSlot(),
                allowed.sourceFingerprint(), allowed.sourceNpcUuid(),
                "Dragon_Ice", allowed.rosterId(), allowed.rosterRevision(),
                BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                        SOURCE, null, -1, "Dragon_Ice", null, null, null, null,
                        null, null, null, null, null, null, null, null,
                        0.75D, 10L), Map.of()), null,
                true, true, true, true, true, true);
        var missingPolicy = new BondedCompanionCaptureIntent(
                allowed.callerNamespace(), "missing-policy", allowed.actorUuid(),
                allowed.worldKey(), allowed.hotbarSlot(),
                allowed.sourceFingerprint(), allowed.sourceNpcUuid(),
                allowed.roleId(), "missing:roster", allowed.rosterRevision(),
                allowed.snapshot(), null, true, true, true, true, true, true);

        assertEquals(BondedCompanionCaptureAuthor.PolicyDecision.ROLE_REJECTED,
                adapter.validate(wrongRole));
        assertEquals(BondedCompanionCaptureAuthor.PolicyDecision.REJECTED,
                adapter.validate(missingPolicy));
    }

    private void assertRejected(BondedCompanionCaptureIntent intent,
                                BondedCompanionCaptureAuthor.Status status) {
        Harness harness = new Harness();
        var result = harness.author.capture(intent);
        assertEquals(status, result.status());
        assertEquals(List.of("message"), harness.events);
        assertFalse(result.durable());
    }

    private static BondedCompanionCaptureIntent validIntent() {
        return intent(snapshot(), true, true, true, true, true);
    }

    private static BondedCompanionCaptureIntent frozenIntentAt(long capturedAtMs) {
        return SpawnerCaptureIntentFactory.freezeBonded(
                new SpawnerCaptureIntentFactory.FrozenBondedCapture(
                        "spawner-bonded-capture:v1", "source:" + SOURCE,
                        OWNER, "world", 2, "fingerprint", SOURCE,
                        "Dragon_Fire", "hydragon:companions", 4L,
                        snapshotAt(capturedAtMs), null,
                        true, true, true, true, true, true
                ));
    }

    private static BondedCompanionCaptureIntent intent(
            BondedCompanionSnapshot snapshot, boolean chance, boolean tranquilized,
            boolean tool, boolean owner, boolean role) {
        return new BondedCompanionCaptureIntent(
                "spawner-bonded-capture:v1", "source:" + SOURCE, OWNER,
                "world", 2, "fingerprint", SOURCE, "Dragon_Fire",
                "hydragon:companions", 4L, snapshot,
                new SpawnerPublishedEffect(1, 2, 3, "burst", "success"),
                true, chance, tranquilized, tool, owner, role);
    }

    private static BondedCompanionSnapshot snapshot() {
        return snapshotAt(10L);
    }

    private static BondedCompanionSnapshot snapshotAt(long capturedAtMs) {
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                SOURCE, null, -1, "Dragon_Fire", null, null, null, null,
                null, null, null, null, null, null, null, null, 0.75D,
                capturedAtMs),
                Map.of());
    }

    private static BondedCompanionRosterRegistry rosterRegistry()
            throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "hydragon:companions",
                                  "FamilyId": "hydragon:dragon",
                                  "AllowedRoles": ["Dragon_Fire"],
                                  "MaximumOwned": 3,
                                  "MaximumActive": 1,
                                  "Features": {"Capture": true}
                                }
                                """),
                        new ExtraInfo()
                );
        Field id = config.getClass().getDeclaredField("id");
        id.setAccessible(true);
        id.set(config, "HydragonCompanions");
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();
        assertTrue(registry.replace(List.of(config), 4L).applied());
        return registry;
    }

    private static final class Harness {
        private final List<String> events = new ArrayList<>();
        private BondedCompanionCaptureAuthor.PolicyDecision policy =
                BondedCompanionCaptureAuthor.PolicyDecision.ALLOWED;
        private BondedCompanionCaptureAuthor.PersistenceOutcome persistence =
                BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED;
        private BondedCompanionCaptureAuthor.CleanupOutcome cleanup =
                BondedCompanionCaptureAuthor.CleanupOutcome.REMOVED;
        private RuntimeException policyFailure;
        private boolean spendSuccessful = true;
        private BondedCompanionCaptureAuthor author = author();

        private BondedCompanionCaptureAuthor author() {
            author = new BondedCompanionCaptureAuthor(
                    intent -> {
                        events.add("policy");
                        if (policyFailure != null) throw policyFailure;
                        return policy;
                    },
                    intent -> { events.add("persist"); return persistence; },
                    intent -> { events.add("cleanup"); return cleanup; },
                    new BondedCompanionCaptureFeedbackDispatcher(
                            new Sink(events, this)),
                    (intent, failure) -> events.add("diagnostic")
            );
            return author;
        }
    }

    private record Sink(List<String> events, Harness harness)
            implements BondedCompanionCaptureFeedbackDispatcher.Sink {
        @Override public boolean spend(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context) {
            events.add("spend");
            return harness.spendSuccessful;
        }
        @Override public void effect(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context) {
            events.add("effect");
        }
        @Override public void message(BondedCompanionCaptureIntent intent,
                                      BondedCompanionCaptureFeedbackDispatcher.CompletionContext context,
                                      String message) {
            events.add("message");
        }
    }
}
