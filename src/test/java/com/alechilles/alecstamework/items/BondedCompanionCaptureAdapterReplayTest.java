package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicyResolver;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionTransitionService;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionCapturePersistenceAdapter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionProjectionDurability;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperationProbe;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionSchemaManager;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Adapter-level regression coverage for exact durable capture replay. */
class BondedCompanionCaptureAdapterReplayTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");
    private static final UUID OTHER = UUID.fromString(
            "90000000-0000-0000-0000-000000000009");
    private static final UUID OTHER_SOURCE = UUID.fromString(
            "80000000-0000-0000-0000-000000000008");
    @TempDir Path tempDir;

    @Test
    void replayIgnoresFreshObservationTimestamp() throws Exception {
        Fixture fixture = fixture("fresh-snapshot-replay.sqlite",
                rosterRegistry());

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                fixture.persistence.store(frozenIntentAt(10L)));
        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.REPLAYED,
                fixture.persistence.store(frozenIntentAt(20L)));
        assertEquals(1, fixture.database.listProfiles(
                OWNER, "hydragon:companions").size());
        assertEquals(1, fixture.database.listCleanup(
                OWNER, "hydragon:companions", 10).size());
    }

    /** Regression: world identity is part of direct replay authority. */
    @Test
    void wrongWorldDirectReplayConflictsWithoutStartingCleanup()
            throws Exception {
        Fixture fixture = fixture("wrong-world-direct-replay.sqlite",
                rosterRegistry());
        AtomicInteger cleanups = new AtomicInteger();
        var author = new BondedCompanionCaptureAuthor(
                fixture.persistence, fixture.persistence::validate,
                fixture.persistence::store, intent -> {
                    cleanups.incrementAndGet();
                    return fixture.persistence.cleanup(intent);
                }, BondedCompanionCaptureFeedbackDispatcher.production(),
                (intent, failure) -> {});
        var original = frozenIntentAt(10L);

        assertTrue(author.capture(original).durable());
        var rejected = author.capture(withWorld(original, "other-world"));

        assertEquals(BondedCompanionCaptureAuthor.Status.DATABASE_FAILED,
                rejected.status());
        assertEquals(1, cleanups.get());
    }

    @Test
    void roleInferredCaptureReplaysPreFamilyRequestHash() throws Exception {
        Fixture fixture = fixture("pre-family-capture-hash.sqlite",
                rosterRegistry());
        var intent = frozenIntentAt(10L);

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                fixture.persistence.store(intent));
        rewriteOperationHash(fixture.path, intent, preFamilyHash(intent));

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.REPLAYED,
                fixture.persistence.store(intent));
    }

    @Test
    void replaySurvivesRemovalOfCurrentFamily() throws Exception {
        BondedCompanionRosterRegistry rosters = rosterRegistry();
        Fixture fixture = fixture("removed-family-replay.sqlite", rosters);
        var intent = frozenIntentAt(10L);

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                fixture.persistence.store(intent));
        assertTrue(rosters.replace(List.of(), 5L).applied());
        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.REPLAYED,
                fixture.persistence.store(intent));
    }

    @Test
    void replaySurvivesRoleBecomingFamilyAmbiguous() throws Exception {
        BondedCompanionRosterRegistry rosters = registry(
                rosterPolicy("Dragons", "hydragon:dragon", "Shared_Role"));
        Fixture fixture = fixture("ambiguous-family-replay.sqlite", rosters);
        var intent = familyIntent("ambiguous-replay", "Shared_Role");

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                fixture.persistence.store(intent));
        assertTrue(rosters.replace(List.of(
                rosterPolicy("Dragons", "hydragon:dragon", "Shared_Role"),
                rosterPolicy("Minis", "hydragon:miniwyvern", "Shared_Role")
        ), 5L).applied());
        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.REPLAYED,
                fixture.persistence.store(intent));
    }

    @Test
    void explicitFamilySelectionRemainsPartOfRequestIdentity() throws Exception {
        Fixture fixture = fixture("explicit-family-hash.sqlite",
                rosterRegistry());
        var inferred = frozenIntentAt(10L);

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                fixture.persistence.store(inferred));
        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED,
                fixture.persistence.store(explicitFamily(inferred)));
    }

    @Test
    void uniqueSourceIndexRollsBackEveryPartOfSecondCapture() throws Exception {
        Fixture fixture = fixture("duplicate-source-rejected.sqlite",
                rosterRegistry());
        var original = frozenIntentAt(10L);
        var duplicate = forActor(original, OTHER);

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                fixture.persistence.store(original));
        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED,
                fixture.persistence.store(duplicate));
        assertTrue(fixture.database.listProfiles(
                OTHER, duplicate.rosterId()).isEmpty());
        assertTrue(fixture.database.listCleanup(
                OTHER, duplicate.rosterId(), 10).isEmpty());
        assertTrue(fixture.database.findProfileOperationByIdentity(
                operationProbe(duplicate)).isEmpty());
        assertEquals(BondedCompanionCaptureReplayGateway.LookupStatus.MATCHED,
                fixture.persistence.lookup(request(original)).status());
    }

    @Test
    void cleanupConflictRollsBackProfileSourceAndOperation() throws Exception {
        Fixture fixture = fixture("cleanup-conflict-rollback.sqlite",
                rosterRegistry());
        var original = frozenIntentAt(10L);
        var duplicate = withSource(forActor(original, OTHER), OTHER_SOURCE);

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                fixture.persistence.store(original));
        injectBlockingCleanup(fixture.path, original, duplicate);

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED,
                fixture.persistence.store(duplicate));
        assertTrue(fixture.database.listProfiles(
                OTHER, duplicate.rosterId()).isEmpty());
        assertTrue(fixture.database.findCaptureEvidence(
                OTHER, duplicate.rosterId(), OTHER_SOURCE).isEmpty());
        assertTrue(fixture.database.findProfileOperationByIdentity(
                operationProbe(duplicate)).isEmpty());
    }

    /** Regression: bounded operation pruning cannot release source ownership. */
    @Test
    void sourceAuthoritySurvivesOperationPruningAndRejectsSecondOwner()
            throws Exception {
        Fixture fixture = fixture("pruned-operation-source-claim.sqlite",
                rosterRegistry());
        var original = frozenIntentAt(10L);
        var duplicate = forActor(original, OTHER);

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                fixture.persistence.store(original));
        assertEquals(BondedCompanionCaptureAuthor.CleanupOutcome.RETRY_PENDING,
                fixture.persistence.cleanup(original));
        assertEquals(1, fixture.database.pruneOperations(Long.MAX_VALUE, 16));

        assertEquals(BondedCompanionCaptureReplayGateway.LookupStatus.MATCHED,
                fixture.persistence.lookup(request(original)).status());
        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.REPLAYED,
                fixture.persistence.store(original));
        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.FAILED,
                fixture.persistence.store(duplicate));
        assertTrue(fixture.database.listProfiles(
                OTHER, duplicate.rosterId()).isEmpty());
        assertTrue(fixture.database.listCleanup(
                OTHER, duplicate.rosterId(), 10).isEmpty());
    }

    @Test
    void globalLookupFailsClosedIfCorruptionContainsMultipleEvidence()
            throws Exception {
        Fixture fixture = fixture("duplicate-source-corruption.sqlite",
                rosterRegistry());
        dropSourceIndex(fixture.path);
        var original = frozenIntentAt(10L);
        var duplicate = forActor(original, OTHER);

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                fixture.persistence.store(original));
        injectDuplicateCaptureEvidence(fixture.path, original, duplicate);
        assertEquals(BondedCompanionCaptureReplayGateway.LookupStatus.CONFLICT,
                fixture.persistence.lookup(request(original)).status());
    }

    @Test
    void currentSchemaContainsAtomicCaptureSourceFence() throws Exception {
        Fixture fixture = fixture("source-index-contract.sqlite",
                rosterRegistry());

        String sql = indexSql(fixture.path);
        assertTrue(sql.contains("CREATE UNIQUE INDEX"));
        assertTrue(sql.contains("source_npc_uuid"));
    }

    @Test
    void v5StartupAppliesCaptureSourceFenceMigration() throws Exception {
        Path path = tempDir.resolve("v5-source-index-upgrade.sqlite");
        BondedCompanionSchemaManager manager =
                new BondedCompanionSchemaManager(path, () -> 10L);
        assertTrue(manager.initialize().availability().available());
        try (Connection connection = new SqliteConnectionFactory(path)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(2, statement.executeUpdate(
                    "DELETE FROM bonded_schema_history WHERE version IN (6, 7)"));
            statement.execute("DROP TABLE bonded_companion_capture_source");
        }

        assertTrue(manager.initialize().availability().available());
        assertFalse(indexSql(path).isBlank());
    }

    @Test
    void droppedCaptureSourceFenceMakesReadinessFailClosed() throws Exception {
        Fixture fixture = fixture("source-index-tamper.sqlite",
                rosterRegistry());
        dropSourceIndex(fixture.path);

        var readiness = new BondedCompanionSchemaManager(fixture.path).verify();

        assertFalse(readiness.availability().available());
        assertEquals("bonded-capture-source-fence-missing",
                readiness.diagnosticCode());
    }

    private Fixture fixture(String name, BondedCompanionRosterRegistry rosters) {
        Path path = tempDir.resolve(name);
        assertTrue(new BondedCompanionSchemaManager(path, () -> 10L)
                .initialize().availability().available());
        var database = new SqliteBondedCompanionDatabase(path);
        var persistence = new SqliteBondedCompanionCapturePersistenceAdapter(
                rosters,
                new BondedCompanionTransitionService(
                        new BondedCompanionPolicyResolver(rosters)),
                database, database,
                new SqliteBondedCompanionProjectionDurability(path),
                new BondedCompanionProjectionCleanupService(
                        ignored -> BondedCompanionProjectionCleanupService
                                .Outcome.RETRY_REQUIRED));
        return new Fixture(path, database, persistence);
    }

    private static BondedCompanionCaptureIntent frozenIntentAt(long time) {
        return new BondedCompanionCaptureIntent(
                "spawner-bonded-capture:v1",
                OWNER + ":hydragon:companions:" + SOURCE,
                OWNER, "world", 2, "fingerprint", SOURCE, attempt(),
                "Dragon_Fire", null, "hydragon:companions", 4L,
                snapshot("Dragon_Fire", time), null,
                true, true, true, true, true, true, "hydragon:dragon",
                BondedCompanionCaptureIntent.FamilySelection.ROLE_INFERRED);
    }

    private static BondedCompanionCaptureIntent familyIntent(
            String key, String roleId
    ) {
        return new BondedCompanionCaptureIntent(
                "spawner-bonded-capture:v1", key, OWNER, "world", 2,
                "fingerprint-" + key, SOURCE, attempt(), roleId, null,
                "hydragon:companions", 4L, snapshot(roleId, 10L), null,
                true, true, true, true, true, true, null,
                BondedCompanionCaptureIntent.FamilySelection.ROLE_INFERRED);
    }

    private static BondedCompanionCaptureIntent explicitFamily(
            BondedCompanionCaptureIntent source
    ) {
        return new BondedCompanionCaptureIntent(
                source.callerNamespace(), source.idempotencyKey(),
                source.actorUuid(), source.worldKey(), source.hotbarSlot(),
                source.sourceFingerprint(), source.sourceNpcUuid(),
                source.attemptEvidence(), source.roleId(), source.species(),
                source.rosterId(), source.rosterRevision(), source.snapshot(),
                source.completionEffect(), true, true, true, true, true, true,
                source.familyId(),
                BondedCompanionCaptureIntent.FamilySelection.EXPLICIT);
    }

    private static BondedCompanionCaptureIntent forActor(
            BondedCompanionCaptureIntent source,
            UUID actorUuid
    ) {
        return new BondedCompanionCaptureIntent(
                source.callerNamespace(),
                actorUuid + ":" + source.rosterId() + ":"
                        + source.sourceNpcUuid(),
                actorUuid, source.worldKey(), source.hotbarSlot(),
                source.sourceFingerprint(), source.sourceNpcUuid(),
                source.attemptEvidence(), source.roleId(), source.species(),
                source.rosterId(), source.rosterRevision(), source.snapshot(),
                source.completionEffect(), true, true, true, true, true, true,
                source.familyId(), source.familySelection());
    }

    private static BondedCompanionCaptureIntent withWorld(
            BondedCompanionCaptureIntent source,
            String worldKey
    ) {
        return new BondedCompanionCaptureIntent(
                source.callerNamespace(), source.idempotencyKey(),
                source.actorUuid(), worldKey, source.hotbarSlot(),
                source.sourceFingerprint(), source.sourceNpcUuid(),
                source.attemptEvidence(), source.roleId(), source.species(),
                source.rosterId(), source.rosterRevision(), source.snapshot(),
                source.completionEffect(), true, true, true, true, true, true,
                source.familyId(), source.familySelection());
    }

    private static BondedCompanionCaptureIntent withSource(
            BondedCompanionCaptureIntent source,
            UUID sourceNpcUuid
    ) {
        CoopResidentStateSnapshot state = source.snapshot().fullState();
        BondedCompanionSnapshot snapshot = BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshot(
                        sourceNpcUuid, state.coopId(), state.residentSlot(),
                        state.roleId(), state.commandLinks(), state.owner(),
                        state.tamed(), state.npcName(), state.happiness(),
                        state.needs(), state.breeding(), state.leveling(),
                        state.traits(), state.talents(), state.lifeStage(),
                        state.attachments(), state.healthPercent(),
                        state.capturedAtMs()),
                source.snapshot().extensionData());
        return new BondedCompanionCaptureIntent(
                source.callerNamespace(), source.actorUuid() + ":"
                + source.rosterId() + ":" + sourceNpcUuid,
                source.actorUuid(), source.worldKey(), source.hotbarSlot(),
                "fingerprint-" + sourceNpcUuid, sourceNpcUuid,
                source.attemptEvidence(), source.roleId(), source.species(),
                source.rosterId(), source.rosterRevision(), snapshot,
                source.completionEffect(), true, true, true, true, true, true,
                source.familyId(), source.familySelection());
    }

    private static BondedCompanionCaptureReplayGateway.Request request(
            BondedCompanionCaptureIntent intent
    ) {
        return new BondedCompanionCaptureReplayGateway.Request(
                intent.callerNamespace(), intent.idempotencyKey(),
                intent.actorUuid(), intent.rosterId(), intent.sourceNpcUuid(),
                intent.worldKey(), intent.attemptEvidence().sourceItemId(),
                intent.roleId());
    }

    private static BondedCompanionOperationProbe operationProbe(
            BondedCompanionCaptureIntent intent
    ) {
        return new BondedCompanionOperationProbe(
                intent.callerNamespace(), intent.idempotencyKey(),
                intent.actorUuid(), intent.rosterId(), intent.profileId(),
                BondedCompanionOperation.Type.CAPTURE);
    }

    private static void dropSourceIndex(Path path) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(path)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX bonded_capture_source_uuid_uq");
        }
    }

    private static void injectDuplicateCaptureEvidence(
            Path path,
            BondedCompanionCaptureIntent original,
            BondedCompanionCaptureIntent duplicate
    ) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(path)
                .openWriterConnection();
             PreparedStatement profile = connection.prepareStatement("""
                     INSERT INTO bonded_companion_profile
                     SELECT ?, ?, roster_id, family_id, role_id, state,
                            revision, snapshot_json, created_at_ms,
                            updated_at_ms, policy_json, display_name, species,
                            gender, died_at_ms, revive_cooldown_until_ms,
                            revive_count, quarantine_reason, quarantined_at_ms
                     FROM bonded_companion_profile WHERE profile_id = ?
                     """);
             PreparedStatement source = connection.prepareStatement("""
                     INSERT INTO bonded_companion_capture_source
                     SELECT ?, ?, roster_id, source_npc_uuid, source_world_key,
                            caller_namespace, ?, request_hash,
                            json_set(capture_evidence_json,
                                '$.ownerUuid', ?, '$.profileId', ?,
                                '$.idempotencyKey', ?),
                            json_set(capture_snapshot_json,
                                '$.ownerUuid', ?, '$.profileId', ?),
                            committed_at_ms, event_published_at_ms
                     FROM bonded_companion_capture_source
                     WHERE profile_id = ?
                     """)) {
            profile.setString(1, duplicate.profileId());
            profile.setString(2, duplicate.actorUuid().toString());
            profile.setString(3, original.profileId());
            assertEquals(1, profile.executeUpdate());
            source.setString(1, duplicate.profileId());
            source.setString(2, duplicate.actorUuid().toString());
            source.setString(3, duplicate.idempotencyKey());
            source.setString(4, duplicate.actorUuid().toString());
            source.setString(5, duplicate.profileId());
            source.setString(6, duplicate.idempotencyKey());
            source.setString(7, duplicate.actorUuid().toString());
            source.setString(8, duplicate.profileId());
            source.setString(9, original.profileId());
            assertEquals(1, source.executeUpdate());
        }
    }

    private static void injectBlockingCleanup(
            Path path,
            BondedCompanionCaptureIntent existing,
            BondedCompanionCaptureIntent blocked
    ) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(path)
                .openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO bonded_companion_cleanup(
                         cleanup_id, owner_uuid, roster_id, profile_id,
                         lease_token, target_kind, target_npc_uuid,
                         cleanup_reason, world_key, cleanup_state,
                         attempt_count, next_attempt_at_ms, created_at_ms,
                         retained_until_ms
                     )
                     SELECT ?, owner_uuid, roster_id, profile_id, lease_token,
                            target_kind, target_npc_uuid, cleanup_reason,
                            world_key, cleanup_state, attempt_count,
                            next_attempt_at_ms, created_at_ms,
                            retained_until_ms
                     FROM bonded_companion_cleanup WHERE cleanup_id = ?
                     """)) {
            statement.setString(1, blocked.profileId() + ":capture-source");
            statement.setString(2, existing.profileId() + ":capture-source");
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String indexSql(Path path) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(path)
                .openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT sql FROM sqlite_master
                     WHERE type = 'index'
                       AND name = 'bonded_capture_source_uuid_uq'
                     """)) {
            return row.next() ? row.getString(1) : "";
        }
    }

    private static void rewriteOperationHash(
            Path path,
            BondedCompanionCaptureIntent intent,
            String hash
    ) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(path)
                .openWriterConnection();
             PreparedStatement operation = connection.prepareStatement("""
                     UPDATE bonded_companion_operation SET request_hash = ?
                     WHERE caller_namespace = ? AND idempotency_key = ?
                     """);
             PreparedStatement source = connection.prepareStatement("""
                     UPDATE bonded_companion_capture_source SET request_hash = ?
                     WHERE caller_namespace = ? AND idempotency_key = ?
                     """)) {
            operation.setString(1, hash);
            operation.setString(2, intent.callerNamespace());
            operation.setString(3, intent.idempotencyKey());
            assertEquals(1, operation.executeUpdate());
            source.setString(1, hash);
            source.setString(2, intent.callerNamespace());
            source.setString(3, intent.idempotencyKey());
            assertEquals(1, source.executeUpdate());
        }
    }

    private static String preFamilyHash(
            BondedCompanionCaptureIntent intent
    ) throws Exception {
        var attempt = intent.attemptEvidence();
        String canonical = intent.actorUuid() + "\0" + intent.rosterId()
                + "\0" + intent.roleId() + "\0" + intent.sourceNpcUuid()
                + "\0" + attempt.attemptId() + "\0" + attempt.sourceItemId()
                + "\0" + attempt.spawnerConfigId()
                + "\0" + attempt.spawnerConfigRevision()
                + "\0" + attempt.capturePolicyConfigId()
                + "\0" + attempt.capturePolicyConfigRevision()
                + "\0" + attempt.sourceConsumption()
                + "\0" + attempt.successDisposition()
                + "\0" + attempt.outcome() + "\0" + attempt.reason()
                + "\0" + new BondedCompanionSnapshotCodec().encode(
                legacyIdentitySnapshot(intent));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static BondedCompanionSnapshot legacyIdentitySnapshot(
            BondedCompanionCaptureIntent intent
    ) {
        CoopResidentStateSnapshot source = intent.snapshot().fullState();
        var claimed = new CoopResidentStateSnapshot(
                source.npcUuid(), source.coopId(), source.residentSlot(),
                source.roleId(), source.commandLinks(),
                new TameworkOwnerComponent(intent.actorUuid(), null),
                new TameworkTamedComponent(true), source.npcName(),
                source.happiness(), source.needs(), source.breeding(),
                source.leveling(), source.traits(), source.talents(),
                source.lifeStage(), source.attachments(), source.healthPercent(),
                0L);
        return BondedCompanionSnapshot.of(
                claimed, intent.snapshot().extensionData());
    }

    private static BondedCompanionCaptureAttemptEvidence attempt() {
        return new BondedCompanionCaptureAttemptEvidence(
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                "Ancient_Stone", "HydragonCapture", 7L, null, -1L,
                CaptureSourceConsumption.SUCCESS_ONLY,
                CaptureSuccessDisposition.STORE_BONDED_COMPANION,
                CaptureAttemptOutcome.CAPTURED, "guaranteed");
    }

    private static BondedCompanionSnapshot snapshot(String role, long time) {
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                SOURCE, null, -1, role, null, null, null, null, null, null,
                null, null, null, null, null, null, 0.75D, time), Map.of());
    }

    private static BondedCompanionRosterRegistry rosterRegistry()
            throws Exception {
        return registry(rosterPolicy(
                "HydragonCompanions", "hydragon:dragon", "Dragon_Fire"));
    }

    private static BondedCompanionRosterRegistry registry(
            TwBondedCompanionRosterConfig... policies
    ) {
        var registry = new BondedCompanionRosterRegistry();
        assertTrue(registry.replace(List.of(policies), 4L).applied());
        return registry;
    }

    private static TwBondedCompanionRosterConfig rosterPolicy(
            String id, String familyId, String roleId
    ) throws Exception {
        var config = TwBondedCompanionRosterConfig.CODEC.decode(
                BsonDocument.parse("""
                        {"RosterId":"hydragon:companions","FamilyId":"%s",
                         "AllowedRoles":["%s"],"MaximumOwned":3,
                         "MaximumActive":1,"Features":{"Capture":true}}
                        """.formatted(familyId, roleId)), new ExtraInfo());
        Field configId = config.getClass().getDeclaredField("id");
        configId.setAccessible(true);
        configId.set(config, id);
        return config;
    }

    private record Fixture(
            Path path,
            SqliteBondedCompanionDatabase database,
            SqliteBondedCompanionCapturePersistenceAdapter persistence
    ) {}
}
