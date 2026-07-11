package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.CoopImportConflictRepository.MutationStatus.CONFLICT;
import static com.alechilles.alecstamework.persistence.sqlite.CoopImportConflictRepository.MutationStatus.IDEMPOTENT;
import static com.alechilles.alecstamework.persistence.sqlite.CoopImportConflictRepository.MutationStatus.INSERTED;
import static com.alechilles.alecstamework.persistence.sqlite.CoopImportConflictRepository.MutationStatus.RESOLVED;
import static com.alechilles.alecstamework.persistence.sqlite.CoopImportConflictRepository.ResolutionState.IGNORED;
import static com.alechilles.alecstamework.persistence.sqlite.CoopImportConflictRepository.ResolutionState.UNRESOLVED;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult.FailureKind.INTEGRITY_VIOLATION;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult.FailureKind.SQL_ERROR;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult.Status.FAILED;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult.Status.LOADED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for immutable, fail-closed managed-coop import conflict records. */
class CoopImportConflictRepositoryTest {
    private static final ManagedCoopAuthorityKey AUTHORITY_A =
            new ManagedCoopAuthorityKey("alpha", 1, 2, 3);
    private static final ManagedCoopAuthorityKey AUTHORITY_B =
            new ManagedCoopAuthorityKey("beta", 4, 5, 6);

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceWriteQueue writeQueue;
    private CoopImportConflictRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("coop-import-conflicts.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        writeQueue = new PersistenceWriteQueue(
                connections, new PersistenceHealthService(), null);
        repository = new CoopImportConflictRepository(connections, writeQueue);
    }

    @AfterEach
    void tearDown() {
        if (writeQueue != null) {
            writeQueue.close();
        }
    }

    @Test
    void immutableInsertIsIdempotentButRejectsIdentityOrPayloadMismatch() throws Exception {
        CoopImportConflictRepository.ConflictEvidence evidence = evidence(
                "conflict-a", AUTHORITY_A, "Coop_Chicken", 1,
                "DUPLICATE_EVIDENCE", "  fingerprint-a  ", "  raw payload a  ", 10L);

        CoopImportConflictRepository.MutationResult inserted = committed(repository.insert(evidence));
        CoopImportConflictRepository.MutationResult replayed = committed(repository.insert(evidence(
                "conflict-a", AUTHORITY_A, "Coop_Chicken", 1,
                "DUPLICATE_EVIDENCE", "  fingerprint-a  ", "  raw payload a  ", 99L)));
        CoopImportConflictRepository.MutationResult payloadMismatch = committed(repository.insert(evidence(
                "conflict-a", AUTHORITY_A, "Coop_Chicken", 1,
                "DUPLICATE_EVIDENCE", "  fingerprint-a  ", "changed", 10L)));
        CoopImportConflictRepository.MutationResult sourceRebound = committed(repository.insert(evidence(
                "different-id", AUTHORITY_A, "Coop_Chicken", 1,
                "DUPLICATE_EVIDENCE", "  fingerprint-a  ", "  raw payload a  ", 10L)));

        assertEquals(INSERTED, inserted.status());
        assertEquals("  fingerprint-a  ", inserted.record().sourceFingerprint());
        assertEquals("  raw payload a  ", inserted.record().sourcePayload());
        assertEquals(IDEMPOTENT, replayed.status());
        assertEquals(10L, replayed.record().createdAtMs());
        assertEquals(CONFLICT, payloadMismatch.status());
        assertEquals("  raw payload a  ", payloadMismatch.record().sourcePayload());
        assertEquals(CONFLICT, sourceRebound.status());
        assertEquals("source_identity_already_bound", sourceRebound.detail());
        assertEquals(1, countRows());
    }

    @Test
    void unresolvedReadsAreDeterministicImmutableAndCanBeScopedExactly() throws Exception {
        committed(repository.insert(evidence(
                "beta-null", AUTHORITY_B, "Coop_Beta", null,
                "OVERFLOW", "beta-z", "raw-beta-null", 1L)));
        committed(repository.insert(evidence(
                "alpha-two", AUTHORITY_A, "Coop_Alpha", 2,
                "IDENTITY", "alpha-b", "raw-alpha-two", 2L)));
        committed(repository.insert(evidence(
                "alpha-zero", AUTHORITY_A, "Coop_Alpha", 0,
                "IDENTITY", "alpha-a", "raw-alpha-zero", 3L)));
        committed(repository.insert(evidence(
                "alpha-null", AUTHORITY_A, "Coop_Alpha", null,
                "OVERFLOW", "alpha-c", "raw-alpha-null", 4L)));
        committed(repository.resolve("alpha-null", IGNORED, "reviewed", -25L));

        ManagedCoopReadResult<List<CoopImportConflictRepository.ConflictRecord>> all =
                repository.listUnresolved();
        ManagedCoopReadResult<List<CoopImportConflictRepository.ConflictRecord>> alpha =
                repository.listUnresolved(AUTHORITY_A, "Coop_Alpha");

        assertEquals(LOADED, all.status());
        assertEquals(List.of("alpha-zero", "alpha-two", "beta-null"),
                all.value().stream().map(
                        CoopImportConflictRepository.ConflictRecord::conflictId).toList());
        assertEquals(LOADED, alpha.status());
        assertEquals(List.of("alpha-zero", "alpha-two"),
                alpha.value().stream().map(
                        CoopImportConflictRepository.ConflictRecord::conflictId).toList());
        assertTrue(alpha.value().stream().allMatch(record -> record.resolutionState() == UNRESOLVED));
        assertThrows(UnsupportedOperationException.class, () -> all.value().clear());
    }

    @Test
    void resolutionChangesOnlyStatusTimestampAndNoteAndReplaysIdempotently() throws Exception {
        CoopImportConflictRepository.ConflictEvidence evidence = evidence(
                "resolve-me", AUTHORITY_A, "Coop_Chicken", 4,
                "IDENTITY", "fingerprint", "raw immutable", 123L);
        CoopImportConflictRepository.ConflictRecord before =
                committed(repository.insert(evidence)).record();

        CoopImportConflictRepository.MutationResult resolved = committed(
                repository.resolve("resolve-me", IGNORED, "operator confirmed", -500L));
        CoopImportConflictRepository.MutationResult replayed = committed(
                repository.resolve("resolve-me", IGNORED, "operator confirmed", -999L));
        CoopImportConflictRepository.MutationResult changed = committed(
                repository.resolve(
                        "resolve-me",
                        CoopImportConflictRepository.ResolutionState.RESOLVED,
                        "different",
                        -600L
                ));

        assertEquals(RESOLVED, resolved.status());
        assertEquals(IGNORED, resolved.record().resolutionState());
        assertEquals(-500L, resolved.record().resolvedAtMs());
        assertEquals("operator confirmed", resolved.record().resolutionNote());
        assertImmutableFieldsEqual(before, resolved.record());
        assertEquals(IDEMPOTENT, replayed.status());
        assertEquals(-500L, replayed.record().resolvedAtMs());
        assertEquals(CONFLICT, changed.status());
        assertImmutableFieldsEqual(before, loadDirect("resolve-me"));
    }

    @Test
    void malformedAuthorityEvidenceFailsClosedInsteadOfReturningAnEmptyList() throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO coop_import_conflicts (
                         conflict_id, authority_id, world_name, coop_id, x, y, z,
                         conflict_kind, source_fingerprint, source_payload,
                         resolution_state, created_at_ms
                     ) VALUES ('bad', 'wrong-authority', 'alpha', 'Coop_Alpha', 1, 2, 3,
                               'BAD', 'fingerprint', 'payload', 'UNRESOLVED', 1)
                     """)) {
            statement.executeUpdate();
        }

        ManagedCoopReadResult<List<CoopImportConflictRepository.ConflictRecord>> result =
                repository.listUnresolved();
        ManagedCoopReadResult<List<CoopImportConflictRepository.ConflictRecord>> scoped =
                repository.listUnresolved(AUTHORITY_A, "Coop_Alpha");

        assertEquals(FAILED, result.status());
        assertEquals(INTEGRITY_VIOLATION, result.failure().kind());
        assertTrue(result.failure().detail().contains("authority_mismatch"));
        assertEquals(FAILED, scoped.status());
        assertEquals(INTEGRITY_VIOLATION, scoped.failure().kind());
    }

    @Test
    void missingConflictTableIsAnExplicitSqlFailure() throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE coop_import_conflicts");
        }

        ManagedCoopReadResult<List<CoopImportConflictRepository.ConflictRecord>> result =
                repository.listUnresolved();

        assertEquals(FAILED, result.status());
        assertEquals(SQL_ERROR, result.failure().kind());
        assertNotNull(result.failure().cause());
    }

    @Test
    void resolutionRequiresExplicitTargetNoteAndNonzeroSignedTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> repository.resolve(
                "id", UNRESOLVED, "note", 1L));
        assertThrows(IllegalArgumentException.class, () -> repository.resolve(
                "id", IGNORED, " ", 1L));
        assertThrows(IllegalArgumentException.class, () -> repository.resolve(
                "id", IGNORED, "note", 0L));
    }

    private CoopImportConflictRepository.ConflictEvidence evidence(
            String conflictId,
            ManagedCoopAuthorityKey authority,
            String coopId,
            Integer slot,
            String kind,
            String fingerprint,
            String payload,
            long createdAtMs) {
        return new CoopImportConflictRepository.ConflictEvidence(
                conflictId,
                authority.authorityId(),
                authority,
                coopId,
                slot,
                kind,
                fingerprint,
                payload,
                createdAtMs
        );
    }

    private <T> T committed(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<T> outcome =
                submission.completion().get(3, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status());
        assertNull(outcome.failure());
        assertNotNull(outcome.value());
        return outcome.value();
    }

    private CoopImportConflictRepository.ConflictRecord loadDirect(String conflictId)
            throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT conflict_id, authority_id, world_name, coop_id, x, y, z,
                            resident_slot, conflict_kind, source_fingerprint, source_payload,
                            resolution_state, created_at_ms, resolved_at_ms, resolution_note
                     FROM coop_import_conflicts WHERE conflict_id = ?
                     """)) {
            statement.setString(1, conflictId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                Integer slot = resultSet.getInt("resident_slot");
                if (resultSet.wasNull()) {
                    slot = null;
                }
                ManagedCoopAuthorityKey key = new ManagedCoopAuthorityKey(
                        resultSet.getString("world_name"),
                        resultSet.getInt("x"),
                        resultSet.getInt("y"),
                        resultSet.getInt("z"));
                return new CoopImportConflictRepository.ConflictRecord(
                        resultSet.getString("conflict_id"),
                        resultSet.getString("authority_id"),
                        key,
                        resultSet.getString("coop_id"),
                        slot,
                        resultSet.getString("conflict_kind"),
                        resultSet.getString("source_fingerprint"),
                        resultSet.getString("source_payload"),
                        CoopImportConflictRepository.ResolutionState.valueOf(
                                resultSet.getString("resolution_state")),
                        resultSet.getLong("created_at_ms"),
                        resultSet.getLong("resolved_at_ms"),
                        resultSet.getString("resolution_note")
                );
            }
        }
    }

    private int countRows() throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM coop_import_conflicts")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void assertImmutableFieldsEqual(
            CoopImportConflictRepository.ConflictRecord expected,
            CoopImportConflictRepository.ConflictRecord actual) {
        assertEquals(expected.conflictId(), actual.conflictId());
        assertEquals(expected.authorityId(), actual.authorityId());
        assertEquals(expected.authorityKey(), actual.authorityKey());
        assertEquals(expected.coopId(), actual.coopId());
        assertEquals(expected.residentSlot(), actual.residentSlot());
        assertEquals(expected.conflictKind(), actual.conflictKind());
        assertEquals(expected.sourceFingerprint(), actual.sourceFingerprint());
        assertEquals(expected.sourcePayload(), actual.sourcePayload());
        assertEquals(expected.createdAtMs(), actual.createdAtMs());
    }
}
