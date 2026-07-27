package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.api.BondedCompanionCaptureResolvedEvent;
import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
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
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCaptureEventPublisher;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPayload;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionSchemaManager;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStoreResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Regression: chance must not hide deterministic owner or role denial. */
    @Test
    void factoryRoutePrioritizesOwnerAndRoleOverFailedChance() {
        Harness ownerHarness = new Harness();
        var ownerDenied = frozenIntent(false, false, true);

        var ownerResult = ownerHarness.author.capture(ownerDenied);

        assertEquals(BondedCompanionCaptureAuthor.Status.OWNER_DENIED,
                ownerResult.status());
        assertEquals(List.of("message"), ownerHarness.events);

        Harness roleHarness = new Harness();
        var roleDenied = frozenIntent(false, true, false);

        var roleResult = roleHarness.author.capture(roleDenied);

        assertEquals(BondedCompanionCaptureAuthor.Status.ROLE_DENIED,
                roleResult.status());
        assertEquals(List.of("message"), roleHarness.events);
    }

    @Test
    void routeDoesNotRollChanceWhenOwnerOrRoleAlreadyDenies() {
        AtomicInteger rollAttempts = new AtomicInteger();

        var ownerDecision = BondedCompanionCaptureRoute.resolveAdmission(
                new SpawnerCapturePolicyService.BondedAdmissionEvidence(
                        null, false, true),
                () -> { rollAttempts.incrementAndGet(); return null; });
        var roleDecision = BondedCompanionCaptureRoute.resolveAdmission(
                new SpawnerCapturePolicyService.BondedAdmissionEvidence(
                        null, true, false),
                () -> { rollAttempts.incrementAndGet(); return null; });

        assertEquals(BondedCompanionCaptureAuthor.Status.OWNER_DENIED,
                ownerDecision.denial());
        assertEquals(BondedCompanionCaptureAuthor.Status.ROLE_DENIED,
                roleDecision.denial());
        assertEquals(0, rollAttempts.get());
    }

    @Test
    void admissionEvidenceCarriesTheFamilyAndRosterGenerationTogether() {
        var evidence = new SpawnerCapturePolicyService.BondedAdmissionEvidence(
                null, true, true, "hydragon:dragon", 4L);

        assertEquals("hydragon:dragon", evidence.familyId());
        assertEquals(4L, evidence.rosterRevision());
    }

    @Test
    void failedRequiredEffectIsDurableAndNeverReportedApplied() {
        Harness harness = new Harness();
        harness.effectSuccessful = false;

        var result = harness.author().capture(validIntent());

        assertEquals(BondedCompanionCaptureAuthor.Status.EFFECT_FAILED,
                result.status());
        assertTrue(result.durable());
        assertEquals(List.of(
                "policy", "persist", "cleanup", "effect", "message"
        ), harness.events);
    }

    @Test
    void failedMessageDeliveryIsVisibleInAuthorResult() {
        Harness harness = new Harness();
        harness.messageSuccessful = false;
        var source = validIntent();
        var ownerDenied = new BondedCompanionCaptureIntent(
                source.callerNamespace(), source.idempotencyKey(),
                source.actorUuid(), source.worldKey(), source.hotbarSlot(),
                source.sourceFingerprint(), source.sourceNpcUuid(),
                source.roleId(), source.rosterId(), source.rosterRevision(),
                source.snapshot(), source.completionEffect(), true, true,
                true, true, false, true);

        var result = harness.author.capture(ownerDenied);

        assertEquals(BondedCompanionCaptureAuthor.Status.OWNER_DENIED,
                result.status());
        assertFalse(result.feedbackDelivered());
        assertEquals(List.of("message", "feedback-diagnostic"),
                harness.events);
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
        rewriteOperationHash(path, first, legacyCaptureHash(first));
        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.REPLAYED,
                adapter.store(retry));
        assertEquals(1, database.listProfiles(
                OWNER, "hydragon:companions").size());
        assertEquals(1, database.listCleanup(
                OWNER, "hydragon:companions", 10).size());
    }

    @Test
    void explicitFamilySelectionAddsRequestIdentityProvenance()
            throws Exception {
        Path path = tempDir.resolve("explicit-family-hash.sqlite");
        assertTrue(new BondedCompanionSchemaManager(path, () -> 10L)
                .initialize().availability().available());
        BondedCompanionRosterRegistry rosters = rosterRegistry();
        SqliteBondedCompanionDatabase database =
                new SqliteBondedCompanionDatabase(path);
        var adapter = adapter(path, rosters, database);
        BondedCompanionCaptureIntent inferred = frozenIntentAt(10L);
        BondedCompanionCaptureIntent explicit = explicitFamily(inferred);

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                adapter.store(inferred));
        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED,
                adapter.store(explicit));
    }

    @Test
    void realAdapterPublishesExactEvidenceOnlyAfterBondedCommit()
            throws Exception {
        Path path = tempDir.resolve("capture-event.sqlite");
        assertTrue(new BondedCompanionSchemaManager(path, () -> 10L)
                .initialize().availability().available());
        BondedCompanionRosterRegistry rosters = rosterRegistry();
        SqliteBondedCompanionDatabase database =
                new SqliteBondedCompanionDatabase(path);
        List<BondedCompanionCaptureResolvedEvent> events = new ArrayList<>();
        var publisher = new BondedCompanionCaptureEventPublisher(
                database,
                event -> {
                    assertTrue(database.findCaptureEvidence(
                            OWNER, "hydragon:companions", SOURCE).isPresent());
                    events.add(event);
                },
                () -> 20L
        );
        var adapter = new SqliteBondedCompanionCapturePersistenceAdapter(
                rosters,
                new BondedCompanionTransitionService(
                        new BondedCompanionPolicyResolver(rosters)),
                database, database,
                new SqliteBondedCompanionProjectionDurability(path),
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService
                                .Outcome.RETRY_REQUIRED),
                publisher
        );
        UUID attemptId = UUID.fromString(
                "30000000-0000-0000-0000-000000000003");
        BondedCompanionCaptureIntent intent = new BondedCompanionCaptureIntent(
                "spawner-bonded-capture:v1", "source:" + SOURCE, OWNER,
                "world", 2, "fingerprint", SOURCE,
                new BondedCompanionCaptureAttemptEvidence(
                        attemptId, "HyDragon_Draconic_Stone",
                        "HyDragon_Draconic_Stone", 7L, null, -1L,
                        CaptureSourceConsumption.SUCCESS_ONLY,
                        CaptureSuccessDisposition.STORE_BONDED_COMPANION,
                        CaptureAttemptOutcome.CAPTURED, "capture-success"),
                "Dragon_Fire", null, "hydragon:companions", 4L,
                snapshot(), null, true, true, true, true, true, true
        );

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                adapter.store(intent));
        assertEquals(1, events.size());
        var capture = events.getFirst().capture();
        assertEquals(attemptId, capture.attemptId());
        assertEquals(SOURCE, capture.sourceNpcUuid());
        assertEquals("Dragon_Fire", capture.roleId());
        assertEquals("HyDragon_Draconic_Stone",
                capture.spawnerConfigId());
        assertEquals(7L, capture.spawnerConfigRevision());
        assertEquals("hydragon:dragon", capture.familyId());
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

    @Test
    void realAdapterScopesCaptureCapacityToTheSelectedFamily()
            throws Exception {
        Path path = tempDir.resolve("multi-family-capture.sqlite");
        assertTrue(new BondedCompanionSchemaManager(path, () -> 10L)
                .initialize().availability().available());
        BondedCompanionRosterRegistry rosters = sharedRosterRegistry(false);
        SqliteBondedCompanionDatabase database =
                new SqliteBondedCompanionDatabase(path);
        var adapter = adapter(path, rosters, database);

        var dragon = familyIntent(
                UUID.fromString("20000000-0000-0000-0000-000000000011"),
                "dragon-1", "Shared_Role", "hydragon:dragon"
        );
        var mini = familyIntent(
                UUID.fromString("20000000-0000-0000-0000-000000000012"),
                "mini-1", "Mini_Role", "hydragon:miniwyvern"
        );
        var secondDragon = familyIntent(
                UUID.fromString("20000000-0000-0000-0000-000000000013"),
                "dragon-2", "Shared_Role", "hydragon:dragon"
        );

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                adapter.store(dragon));
        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                adapter.store(mini));
        assertEquals(BondedCompanionCaptureAuthor.PolicyDecision.CAPACITY_REJECTED,
                adapter.validate(secondDragon));
        assertEquals(2, database.listProfiles(OWNER, "hydragon:companions").size());
    }

    @Test
    void realAdapterRejectsAmbiguousRoleWithoutFrozenFamily()
            throws Exception {
        Path path = tempDir.resolve("ambiguous-family-capture.sqlite");
        assertTrue(new BondedCompanionSchemaManager(path, () -> 10L)
                .initialize().availability().available());
        BondedCompanionRosterRegistry rosters = sharedRosterRegistry(true);
        SqliteBondedCompanionDatabase database =
                new SqliteBondedCompanionDatabase(path);
        var adapter = adapter(path, rosters, database);
        var ambiguous = familyIntent(SOURCE, "ambiguous", "Shared_Role", null);
        var explicit = familyIntent(
                SOURCE, "explicit", "Shared_Role", "hydragon:miniwyvern"
        );

        assertEquals(BondedCompanionCaptureAuthor.PolicyDecision.REJECTED,
                adapter.validate(ambiguous));
        assertEquals(BondedCompanionCaptureAuthor.PolicyDecision.ALLOWED,
                adapter.validate(explicit));
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

    private static BondedCompanionCaptureIntent frozenIntent(
            boolean chance, boolean owner, boolean role
    ) {
        return SpawnerCaptureIntentFactory.freezeBonded(
                new SpawnerCaptureIntentFactory.FrozenBondedCapture(
                        "spawner-bonded-capture:v1", "attempt", OWNER,
                        "world", 2, "fingerprint", SOURCE, "Dragon_Fire",
                        "hydragon:companions", 4L, snapshot(),
                        new SpawnerPublishedEffect(1, 2, 3, "burst", "success"),
                        true, chance, true, true, owner, role));
    }

    private static BondedCompanionCaptureIntent frozenIntentAt(long capturedAtMs) {
        return SpawnerCaptureIntentFactory.freezeBonded(
                new SpawnerCaptureIntentFactory.FrozenBondedCapture(
                        "spawner-bonded-capture:v1", "source:" + SOURCE,
                        OWNER, "world", 2, "fingerprint", SOURCE,
                        "Dragon_Fire",
                        BondedCompanionCaptureIntent.legacyEvidence(
                                "source:" + SOURCE, true),
                        null, "hydragon:companions", 4L,
                        snapshotAt(capturedAtMs), null, true, true, true,
                        true, true, true, "hydragon:dragon"
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

    private static BondedCompanionCaptureIntent familyIntent(
            UUID sourceUuid,
            String key,
            String roleId,
            String familyId
    ) {
        return new BondedCompanionCaptureIntent(
                "spawner-bonded-capture:v1", key, OWNER,
                "world", 2, "fingerprint-" + key, sourceUuid, roleId,
                "hydragon:companions", 4L,
                snapshot(sourceUuid, roleId, 10L), null,
                true, true, true, true, true, true, familyId
        );
    }

    private static BondedCompanionCaptureIntent explicitFamily(
            BondedCompanionCaptureIntent intent
    ) {
        return new BondedCompanionCaptureIntent(
                intent.callerNamespace(), intent.idempotencyKey(),
                intent.actorUuid(), intent.worldKey(), intent.hotbarSlot(),
                intent.sourceFingerprint(), intent.sourceNpcUuid(),
                intent.attemptEvidence(), intent.roleId(), intent.species(),
                intent.rosterId(), intent.rosterRevision(), intent.snapshot(),
                intent.completionEffect(), intent.targetValid(),
                intent.chanceSuccessful(), intent.tranquilized(),
                intent.toolAccess(), intent.ownerAllowed(), intent.roleAllowed(),
                intent.familyId(),
                BondedCompanionCaptureIntent.FamilySelection.EXPLICIT
        );
    }

    private static BondedCompanionSnapshot snapshot() {
        return snapshotAt(10L);
    }

    private static BondedCompanionSnapshot snapshotAt(long capturedAtMs) {
        return snapshot(SOURCE, "Dragon_Fire", capturedAtMs);
    }

    private static BondedCompanionSnapshot snapshot(
            UUID sourceUuid,
            String roleId,
            long capturedAtMs
    ) {
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                sourceUuid, null, -1, roleId, null, null, null, null,
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

    private static BondedCompanionRosterRegistry sharedRosterRegistry(
            boolean sharedRole
    ) throws Exception {
        String dragonRole = "Shared_Role";
        String miniRole = sharedRole ? "Shared_Role" : "Mini_Role";
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();
        assertTrue(registry.replace(List.of(
                rosterPolicy("Dragons", "hydragon:dragon", dragonRole),
                rosterPolicy("Minis", "hydragon:miniwyvern", miniRole)
        ), 4L).applied());
        return registry;
    }

    private static TwBondedCompanionRosterConfig rosterPolicy(
            String id,
            String familyId,
            String roleId
    ) throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "hydragon:companions",
                                  "FamilyId": "%s",
                                  "AllowedRoles": ["%s"],
                                  "MaximumOwned": 1,
                                  "MaximumActive": 1,
                                  "Features": {"Capture": true}
                                }
                                """.formatted(familyId, roleId)),
                        new ExtraInfo()
                );
        Field configId = config.getClass().getDeclaredField("id");
        configId.setAccessible(true);
        configId.set(config, id);
        return config;
    }

    private static SqliteBondedCompanionCapturePersistenceAdapter adapter(
            Path path,
            BondedCompanionRosterRegistry rosters,
            SqliteBondedCompanionDatabase database
    ) {
        return new SqliteBondedCompanionCapturePersistenceAdapter(
                rosters,
                new BondedCompanionTransitionService(
                        new BondedCompanionPolicyResolver(rosters)),
                database, database,
                new SqliteBondedCompanionProjectionDurability(path),
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService
                                .Outcome.RETRY_REQUIRED)
        );
    }

    private static void rewriteOperationHash(
            Path database,
            BondedCompanionCaptureIntent intent,
            String hash
    ) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE bonded_companion_operation
                     SET request_hash = ?
                     WHERE caller_namespace = ? AND idempotency_key = ?
                     """)) {
            statement.setString(1, hash);
            statement.setString(2, intent.callerNamespace());
            statement.setString(3, intent.idempotencyKey());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String legacyCaptureHash(
            BondedCompanionCaptureIntent intent
    ) throws Exception {
        BondedCompanionCaptureAttemptEvidence attempt = intent.attemptEvidence();
        String canonical = intent.actorUuid() + "\0" + intent.rosterId()
                + "\0" + intent.roleId() + "\0" + intent.sourceNpcUuid()
                + "\0" + attempt.attemptId()
                + "\0" + attempt.sourceItemId()
                + "\0" + attempt.spawnerConfigId()
                + "\0" + attempt.spawnerConfigRevision()
                + "\0" + attempt.capturePolicyConfigId()
                + "\0" + attempt.capturePolicyConfigRevision()
                + "\0" + attempt.sourceConsumption()
                + "\0" + attempt.successDisposition()
                + "\0" + attempt.outcome()
                + "\0" + attempt.reason()
                + "\0" + new BondedCompanionSnapshotCodec().encode(
                        legacyIdentitySnapshot(intent));
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static BondedCompanionSnapshot legacyIdentitySnapshot(
            BondedCompanionCaptureIntent intent
    ) {
        CoopResidentStateSnapshot source = intent.snapshot().fullState();
        CoopResidentStateSnapshot claimed = new CoopResidentStateSnapshot(
                source.npcUuid(), source.coopId(), source.residentSlot(),
                source.roleId(), source.commandLinks(),
                new com.alechilles.alecstamework.npc.components
                        .TameworkOwnerComponent(intent.actorUuid(), null),
                new com.alechilles.alecstamework.npc.components
                        .TameworkTamedComponent(true),
                source.npcName(), source.happiness(), source.needs(),
                source.breeding(), source.leveling(), source.traits(),
                source.talents(), source.lifeStage(), source.attachments(),
                source.healthPercent(), 0L
        );
        return BondedCompanionSnapshot.of(
                claimed, intent.snapshot().extensionData());
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
        private boolean effectSuccessful = true;
        private boolean messageSuccessful = true;
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
                            new Sink(events, this),
                            (intent, message, failure) -> events.add(
                                    "feedback-diagnostic")),
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
        @Override public boolean effect(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context) {
            events.add("effect");
            return harness.effectSuccessful;
        }
        @Override public boolean message(
                BondedCompanionCaptureIntent intent,
                BondedCompanionCaptureFeedbackDispatcher.CompletionContext context,
                String message) {
            events.add("message");
            return harness.messageSuccessful;
        }
    }
}
