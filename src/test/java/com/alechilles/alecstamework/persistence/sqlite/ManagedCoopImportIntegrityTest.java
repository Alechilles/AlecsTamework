package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.BeginSessionRequest;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.FinalizationRequest;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.MutationResult;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.MutationStatus;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationProof;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportTestFixtures.AUTHORITY;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportTestFixtures.COOP_ID;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportTestFixtures.hash;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for fail-closed import authorization and replay boundaries. */
class ManagedCoopImportIntegrityTest {
    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceWriteQueue writeQueue;
    private ManagedCoopImportRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("import-integrity.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            ManagedCoopImportTestFixtures.insertAuthority(connection);
        }
        writeQueue = new PersistenceWriteQueue(
                connections, new PersistenceHealthService(), null);
        repository = new ManagedCoopImportRepository(connections, writeQueue);
    }

    @AfterEach
    void tearDown() {
        if (writeQueue != null) {
            writeQueue.close();
        }
    }

    @Test
    void preflightRejectsConflictsAndSkipsHooksOnIdempotentReplay() throws Exception {
        SourceEvidence source = ManagedCoopImportTestFixtures.source(
                "source-preflight", 0, 0, UUID.randomUUID());
        BeginSessionRequest request = ManagedCoopImportTestFixtures.request(
                "session-preflight", List.of(source));
        committed(repository.beginSession(request));
        DispositionBinding binding = ManagedCoopImportTestFixtures.managedBinding(
                request, source, DispositionKind.IMPORTED);
        committed(repository.bindDispositionAtomically(binding,
                (connection, ignored) -> ManagedCoopImportTestFixtures.insertManagedBinding(
                        connection, binding, source)));

        AtomicInteger calls = new AtomicInteger();
        MutationResult replay = committed(repository.bindDispositionAtomically(
                binding, (connection, ignored) -> calls.incrementAndGet()));
        DispositionBinding wrongAudit = new DispositionBinding(
                binding.sessionId(), binding.sourceId(), hash("wrong-audit"),
                binding.sourceFingerprint(), binding.commandId(), binding.disposition(),
                binding.operationId(), binding.residentId(), binding.profileId(),
                null, null, binding.boundAtMs());
        MutationResult conflict = committed(repository.bindDispositionAtomically(
                wrongAudit, (connection, ignored) -> calls.incrementAndGet()));

        assertEquals(MutationStatus.IDEMPOTENT, replay.status());
        assertEquals(MutationStatus.CONFLICT, conflict.status());
        assertEquals(0, calls.get());
    }

    @Test
    void postHookInvariantFailureRollsBackEveryCreatedRow() throws Exception {
        TestContext context = beginManaged("rollback", UUID.randomUUID());

        MutationResult result = committed(repository.bindDispositionAtomically(
                context.binding,
                (connection, ignored) -> {
                    ManagedCoopImportTestFixtures.insertProfile(
                            connection, context.binding.profileId(), context.source.persistentUuid());
                    ManagedCoopImportTestFixtures.insertResident(
                            connection, context.binding, context.source, 0, "HOUSED", 0L);
                    ManagedCoopImportTestFixtures.insertImportOperation(
                            connection, context.binding, context.source, 1,
                            "SOURCE_RETIRE_REQUESTED", 2L, true, 0L, 0L);
                }));

        assertEquals(MutationStatus.INVARIANT_BLOCKED, result.status());
        assertEquals("import_operation_binding_invalid", result.detail());
        assertHookRowsAbsent(context.binding);
        assertNull(sourceDisposition(context.source.sourceId()));
    }

    @Test
    void hookExceptionRollsBackRowsBeforeWriteFailureEscapes() throws Exception {
        TestContext context = beginManaged("exception", UUID.randomUUID());

        PersistenceWriteQueue.WriteSubmission<MutationResult> submission =
                repository.bindDispositionAtomically(context.binding, (connection, ignored) -> {
                    ManagedCoopImportTestFixtures.insertProfile(
                            connection, context.binding.profileId(), context.source.persistentUuid());
                    throw new IllegalStateException("forced_hook_failure");
                });
        PersistenceWriteQueue.WriteOutcome<MutationResult> outcome =
                submission.completion().get(5, TimeUnit.SECONDS);

        assertEquals(PersistenceWriteQueue.WriteStatus.FAILED, outcome.status());
        assertNotNull(outcome.failure());
        assertEquals(0, count("npc_profiles", "profile_id", context.binding.profileId()));
        assertNull(sourceDisposition(context.source.sourceId()));
    }

    @Test
    void firstBindingRejectsAnAlreadyCompleteImportOperation() throws Exception {
        TestContext context = beginManaged("complete", UUID.randomUUID());

        MutationResult result = committed(repository.bindDispositionAtomically(
                context.binding,
                (connection, ignored) -> {
                    ManagedCoopImportTestFixtures.insertProfile(
                            connection, context.binding.profileId(), context.source.persistentUuid());
                    ManagedCoopImportTestFixtures.insertResident(
                            connection, context.binding, context.source, 0, "HOUSED", 0L);
                    ManagedCoopImportTestFixtures.insertImportOperation(
                            connection, context.binding, context.source, 0,
                            "COMPLETE", 3L, false, -70L, 0L);
                }));

        assertEquals(MutationStatus.INVARIANT_BLOCKED, result.status());
        assertEquals("import_operation_binding_invalid", result.detail());
        assertHookRowsAbsent(context.binding);
    }

    @Test
    void authorizationRejectsNonTerminalResidentWrongTargetAndMissingAlias() throws Exception {
        TestContext context = beginManaged("joined", UUID.randomUUID());

        MutationResult badState = attemptManagedBinding(
                context, "QUARANTINED", 0, true);
        MutationResult wrongTarget = attemptManagedBinding(
                context, "HOUSED", 1, true);
        MutationResult missingAlias = attemptManagedBinding(
                context, "HOUSED", 0, false);

        assertEquals("import_resident_binding_invalid", badState.detail());
        assertEquals("import_operation_binding_invalid", wrongTarget.detail());
        assertEquals("import_profile_binding_invalid", missingAlias.detail());
        assertHookRowsAbsent(context.binding);
        assertNull(sourceDisposition(context.source.sourceId()));
    }

    @Test
    void exactDuplicateAliasesMayMatchOneImportedResident() throws Exception {
        UUID sharedUuid = UUID.randomUUID();
        String sharedProfile = "profile-shared";
        String sharedResident = "resident-shared";
        SourceEvidence imported = ManagedCoopImportTestFixtures.source(
                "source-imported", 0, 0, sharedUuid, sharedProfile);
        SourceEvidence matched = ManagedCoopImportTestFixtures.withManagedSnapshot(
                ManagedCoopImportTestFixtures.source(
                        "source-matched", 1, 1, sharedUuid, sharedProfile), imported);
        BeginSessionRequest request = ManagedCoopImportTestFixtures.request(
                "session-shared", List.of(imported, matched));
        committed(repository.beginSession(request));
        DispositionBinding importedBinding = ManagedCoopImportTestFixtures.managedBinding(
                request, imported, DispositionKind.IMPORTED,
                "operation-imported", sharedResident, sharedProfile);
        DispositionBinding matchedBinding = ManagedCoopImportTestFixtures.managedBinding(
                request, matched, DispositionKind.MATCHED,
                "operation-matched", sharedResident, sharedProfile);

        assertEquals(MutationStatus.APPLIED, committed(repository.bindDispositionAtomically(
                importedBinding,
                (connection, ignored) -> ManagedCoopImportTestFixtures.insertManagedBinding(
                        connection, importedBinding, imported))).status());
        committed(repository.recordVerifiedNeutralization(
                proof(request, imported, importedBinding, -60L)));
        assertEquals(MutationStatus.APPLIED, committed(repository.bindDispositionAtomically(
                matchedBinding,
                (connection, ignored) -> ManagedCoopImportTestFixtures.insertImportOperation(
                        connection, matchedBinding, matched, 0,
                        "SOURCE_RETIRE_REQUESTED", 2L, true, 0L, 0L))).status());
        committed(repository.recordVerifiedNeutralization(
                proof(request, matched, matchedBinding, -50L)));

        MutationResult finalized = committed(repository.finalizeAuthority(
                finalization(request, AuthorityState.TWORK_MANAGED)));
        assertEquals(MutationStatus.APPLIED, finalized.status());
        assertEquals(2, count("managed_coop_import_sources", "resident_id", sharedResident));
        assertEquals(1, count("managed_coop_residents", "resident_id", sharedResident));
    }

    @Test
    void quarantineRequiresExactLocationKindAndUnresolvedState() throws Exception {
        SourceEvidence source = ManagedCoopImportTestFixtures.source(
                "source-quarantine-exact", 0, 0, UUID.randomUUID());
        BeginSessionRequest request = ManagedCoopImportTestFixtures.request(
                "session-quarantine-exact", List.of(source));
        committed(repository.beginSession(request));
        DispositionBinding binding = ManagedCoopImportTestFixtures.quarantineBinding(
                request, source);

        MutationResult wrongSlot = quarantineAttempt(binding, source, 1,
                binding.conflictKind(), "UNRESOLVED");
        MutationResult wrongKind = quarantineAttempt(binding, source, 0,
                "DIFFERENT_KIND", "UNRESOLVED");
        MutationResult resolved = quarantineAttempt(binding, source, 0,
                binding.conflictKind(), "RESOLVED");
        MutationResult exact = committed(repository.bindDispositionAtomically(
                binding, (connection, ignored) -> ManagedCoopImportTestFixtures.insertConflict(
                        connection, binding, source)));

        assertEquals(MutationStatus.INVARIANT_BLOCKED, wrongSlot.status());
        assertEquals(MutationStatus.INVARIANT_BLOCKED, wrongKind.status());
        assertEquals(MutationStatus.INVARIANT_BLOCKED, resolved.status());
        assertEquals(MutationStatus.APPLIED, exact.status());
    }

    @Test
    void unresolvedAuthorityConflictBlocksManagedPublication() throws Exception {
        TestContext context = beginManaged("authority-conflict", UUID.randomUUID());
        committed(repository.bindDispositionAtomically(
                context.binding,
                (connection, ignored) -> ManagedCoopImportTestFixtures.insertManagedBinding(
                        connection, context.binding, context.source)));
        committed(repository.recordVerifiedNeutralization(
                proof(context.request, context.source, context.binding, -60L)));
        insertUnresolvedAuthorityConflict();

        MutationResult blocked = committed(repository.finalizeAuthority(
                finalization(context.request, AuthorityState.TWORK_MANAGED)));
        resolveAuthorityConflict();
        MutationResult finalized = committed(repository.finalizeAuthority(
                finalization(context.request, AuthorityState.TWORK_MANAGED)));

        assertEquals(MutationStatus.INVARIANT_BLOCKED, blocked.status());
        assertEquals("unresolved_authority_import_conflict", blocked.detail());
        assertEquals(MutationStatus.APPLIED, finalized.status());
    }

    @Test
    void importSessionAndSourcePrimaryIdsAreImmutable() throws Exception {
        SourceEvidence source = ManagedCoopImportTestFixtures.source(
                "source-immutable", 0, 0, UUID.randomUUID());
        BeginSessionRequest request = ManagedCoopImportTestFixtures.request(
                "session-immutable", List.of(source));
        committed(repository.beginSession(request));

        assertThrows(SQLException.class, () -> updateId(
                "managed_coop_import_sessions", "session_id",
                request.envelope().sessionId(), "changed-session"));
        assertThrows(SQLException.class, () -> updateId(
                "managed_coop_import_sources", "source_id",
                source.sourceId(), "changed-source"));
        assertEquals(1, count("managed_coop_import_sessions", "session_id",
                request.envelope().sessionId()));
        assertEquals(1, count("managed_coop_import_sources", "source_id", source.sourceId()));
    }

    private MutationResult attemptManagedBinding(TestContext context,
                                                 String residentState,
                                                 int operationSlot,
                                                 boolean mapAlias) throws Exception {
        MutationResult result = committed(repository.bindDispositionAtomically(
                context.binding,
                (connection, ignored) -> {
                    ManagedCoopImportTestFixtures.insertProfile(
                            connection, context.binding.profileId(),
                            mapAlias ? context.source.persistentUuid() : null);
                    ManagedCoopImportTestFixtures.insertResident(
                            connection, context.binding, context.source, 0, residentState, 0L);
                    ManagedCoopImportTestFixtures.insertImportOperation(
                            connection, context.binding, context.source, operationSlot,
                            "SOURCE_RETIRE_REQUESTED", 2L, true, 0L, 0L);
                }));
        assertEquals(MutationStatus.INVARIANT_BLOCKED, result.status());
        assertHookRowsAbsent(context.binding);
        return result;
    }

    private MutationResult quarantineAttempt(DispositionBinding binding,
                                             SourceEvidence source,
                                             int slot,
                                             String kind,
                                             String state) throws Exception {
        MutationResult result = committed(repository.bindDispositionAtomically(
                binding, (connection, ignored) -> ManagedCoopImportTestFixtures.insertConflict(
                        connection, binding, source, slot, kind, state)));
        assertEquals(0, count("coop_import_conflicts", "conflict_id", binding.conflictId()));
        return result;
    }

    private TestContext beginManaged(String suffix, UUID uuid) throws Exception {
        SourceEvidence source = ManagedCoopImportTestFixtures.source(
                "source-" + suffix, 0, 0, uuid);
        BeginSessionRequest request = ManagedCoopImportTestFixtures.request(
                "session-" + suffix, List.of(source));
        committed(repository.beginSession(request));
        return new TestContext(request, source,
                ManagedCoopImportTestFixtures.managedBinding(
                        request, source, DispositionKind.IMPORTED));
    }

    private NeutralizationProof proof(BeginSessionRequest request,
                                      SourceEvidence source,
                                      DispositionBinding binding,
                                      long verifiedAtMs) {
        String json = "{\"absent\":true,\"source\":\"" + source.sourceId() + "\"}";
        return new NeutralizationProof(
                request.envelope().sessionId(), source.sourceId(),
                request.envelope().auditFingerprint(), source.sourceFingerprint(),
                source.sourcePayloadHash(), source.sourceSlot(), source.sourceOrder(),
                source.persistentUuid(), binding.commandId(), json, hash(json), 1, verifiedAtMs);
    }

    private FinalizationRequest finalization(BeginSessionRequest request, AuthorityState target) {
        return new FinalizationRequest(
                request.envelope().sessionId(), AUTHORITY, COOP_ID,
                request.envelope().auditFingerprint(), hash("final:" + target), target, -40L);
    }

    private MutationResult committed(
            PersistenceWriteQueue.WriteSubmission<MutationResult> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<MutationResult> outcome =
                submission.completion().get(5, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status());
        assertNull(outcome.failure());
        assertNotNull(outcome.value());
        return outcome.value();
    }

    private void assertHookRowsAbsent(DispositionBinding binding) throws Exception {
        assertEquals(0, count("npc_profiles", "profile_id", binding.profileId()));
        assertEquals(0, count("managed_coop_residents", "resident_id", binding.residentId()));
        assertEquals(0, count("coop_lifecycle_operations", "operation_id", binding.operationId()));
    }

    private String sourceDisposition(String sourceId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT disposition_kind FROM managed_coop_import_sources WHERE source_id = ?")) {
            statement.setString(1, sourceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private int count(String table, String column, String value) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private void insertUnresolvedAuthorityConflict() throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO coop_import_conflicts (
                         conflict_id, authority_id, world_name, coop_id, x, y, z,
                         conflict_kind, source_fingerprint, source_payload,
                         resolution_state, created_at_ms
                     ) VALUES ('unresolved-extra', ?, ?, ?, ?, ?, ?, 'AUDIT_AMBIGUITY', ?,
                               '{}', 'UNRESOLVED', -50)
                     """)) {
            statement.setString(1, AUTHORITY.authorityId());
            statement.setString(2, AUTHORITY.worldName());
            statement.setString(3, COOP_ID);
            statement.setInt(4, AUTHORITY.x());
            statement.setInt(5, AUTHORITY.y());
            statement.setInt(6, AUTHORITY.z());
            statement.setString(7, hash("unresolved-extra"));
            statement.executeUpdate();
        }
    }

    private void resolveAuthorityConflict() throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE coop_import_conflicts
                     SET resolution_state = 'RESOLVED', resolved_at_ms = -30,
                         resolution_note = 'test resolution'
                     WHERE conflict_id = 'unresolved-extra'
                     """)) {
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void updateId(String table, String column, String current, String replacement)
            throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + table + " SET " + column + " = ? WHERE " + column + " = ?")) {
            statement.setString(1, replacement);
            statement.setString(2, current);
            statement.executeUpdate();
        }
    }

    private record TestContext(BeginSessionRequest request,
                               SourceEvidence source,
                               DispositionBinding binding) {
    }
}
