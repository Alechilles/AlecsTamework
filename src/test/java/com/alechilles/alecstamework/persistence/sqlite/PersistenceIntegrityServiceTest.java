package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceIntegrityServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void cleanMigratedDatabasePassesEveryCheck() throws Exception {
        SqliteConnectionManager connections = migrated("clean.sqlite");

        PersistenceIntegrityService.IntegrityReport report =
                new PersistenceIntegrityService(connections).inspect();

        assertEquals(PersistenceIntegrityService.ReportStatus.COMPLETE, report.status());
        assertTrue(report.isClean());
    }

    @Test
    void reportsCurrentAliasAndForeignKeyViolations() throws Exception {
        SqliteConnectionManager connections = migrated("invalid.sqlite");
        try (Connection connection = connections.openConnection()) {
            insertProfile(connection, "profile-a", uuid(1));
            insertAlias(connection, "profile-a", uuid(1), true);
            insertAlias(connection, "profile-a", uuid(2), true);
        }
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("INSERT INTO npc_uuid_aliases VALUES ('"
                    + uuid(3) + "', 'missing-profile', 0, 1)");
        }

        PersistenceIntegrityService.IntegrityReport report =
                new PersistenceIntegrityService(connections).inspect();

        assertEquals(PersistenceIntegrityService.ReportStatus.COMPLETE, report.status());
        assertTrue(report.issues().stream().anyMatch(
                issue -> issue.id().equals("multiple_current_aliases")
                        && issue.affectedGroups() == 1L));
        assertTrue(report.issues().stream().anyMatch(
                issue -> issue.id().equals("foreign_key_violation")
                        && issue.affectedGroups() == 1L));
    }

    @Test
    void reportsUnresolvedImportConflict() throws Exception {
        SqliteConnectionManager connections = migrated("import-conflict.sqlite");
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO managed_coop_authority (
                      authority_id, world_name, coop_id, x, y, z, authority_state,
                      active, import_version, created_at_ms, updated_at_ms
                    ) VALUES ('authority-1', 'default', 'Coop_Chicken', 1, 2, 3,
                      'CONFLICT', 1, 5, 1, 1)
                    """);
            statement.execute("""
                    INSERT INTO coop_import_conflicts (
                      conflict_id, authority_id, world_name, coop_id, x, y, z,
                      resident_slot, conflict_kind, source_fingerprint, source_payload,
                      resolution_state, created_at_ms
                    ) VALUES ('conflict-1', 'authority-1', 'default', 'Coop_Chicken',
                      1, 2, 3, 0, 'AMBIGUOUS_SOURCE', 'source-1', '{}', 'UNRESOLVED', 1)
                    """);
        }

        PersistenceIntegrityService.IntegrityReport report =
                new PersistenceIntegrityService(connections).inspect();

        assertEquals(PersistenceIntegrityService.ReportStatus.COMPLETE, report.status());
        assertTrue(report.issues().stream().anyMatch(
                issue -> issue.id().equals("unresolved_coop_import_conflict")
                        && issue.affectedGroups() == 1L));
    }

    @Test
    void reportsRetryablePopulationEvidenceForANonbreedingOperation() throws Exception {
        SqliteConnectionManager connections = migrated("invalid-retryable-kind.sqlite");
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            insertProfile(connection, "profile-retryable", uuid(4));
            statement.execute("""
                    INSERT INTO companion_population_operations (
                      operation_id, profile_id, operation_type, state, expected_revision,
                      old_state_json, new_state_json, created_at_ms, updated_at_ms,
                      completed_at_ms
                    ) VALUES (
                      'operation-retryable', 'profile-retryable', 'OWNER_TRANSFER',
                      'RETRYABLE', 0, '{}', '{}', 1, 1, 1
                    )
                    """);
        }

        PersistenceIntegrityService.IntegrityReport report =
                new PersistenceIntegrityService(connections).inspect();

        assertIssue(report, "nonbreeding_retryable_population_operation", 1L);
    }

    @Test
    void reportsBothCoopOperationActivityDirectionMismatches() throws Exception {
        SqliteConnectionManager connections = migrated("operation-activity.sqlite");
        String authorityId = authorityId(10);
        try (Connection connection = connections.openConnection()) {
            insertAuthority(connection, authorityId, "TWORK_MANAGED", 10);
            insertProfile(connection, "profile-finalized", uuid(10));
            insertProfile(connection, "profile-prepared", uuid(11));
            insertOperation(connection, "operation-finalized", "profile-finalized",
                    authorityId, 10, 0, "RELEASE", "FINALIZED", true);
            insertOperation(connection, "operation-prepared", "profile-prepared",
                    authorityId, 10, 1, "CAPTURE", "PREPARED", false);
        }

        PersistenceIntegrityService.IntegrityReport report =
                new PersistenceIntegrityService(connections).inspect();

        assertIssue(report, "active_terminal_coop_operation", 1L);
        assertIssue(report, "inactive_nonterminal_coop_operation", 1L);
    }

    @Test
    void reportsBothImportSessionActivityDirectionMismatches() throws Exception {
        SqliteConnectionManager connections = migrated("import-activity.sqlite");
        try (Connection connection = connections.openConnection()) {
            String inactiveAuthority = authorityId(20);
            insertAuthority(connection, inactiveAuthority, "IMPORTING_TO_TWORK", 20);
            insertImportSession(connection, "session-active-inactive", inactiveAuthority,
                    20, "ACTIVE", false);

            String activeAuthority = authorityId(21);
            insertAuthority(connection, activeAuthority, "IMPORTING_TO_TWORK", 21);
            insertImportSession(connection, "session-finalized-active", activeAuthority,
                    21, "FINALIZED_MANAGED", true);
        }

        PersistenceIntegrityService.IntegrityReport report =
                new PersistenceIntegrityService(connections).inspect();

        assertIssue(report, "inactive_active_import_session", 1L);
        assertIssue(report, "active_finalized_import_session", 1L);
        assertIssue(report, "importing_authority_without_active_session", 2L);
    }

    @Test
    void reportsBothActiveImportAuthorityDirectionMismatches() throws Exception {
        SqliteConnectionManager connections = migrated("import-authority.sqlite");
        try (Connection connection = connections.openConnection()) {
            String managedAuthority = authorityId(30);
            insertAuthority(connection, managedAuthority, "TWORK_MANAGED", 30);
            insertImportSession(connection, "session-on-managed", managedAuthority,
                    30, "ACTIVE", true);

            insertAuthority(connection, authorityId(31), "IMPORTING_TO_TWORK", 31);
        }

        PersistenceIntegrityService.IntegrityReport report =
                new PersistenceIntegrityService(connections).inspect();

        assertIssue(report, "managed_authority_with_active_import", 1L);
        assertIssue(report, "importing_authority_without_active_session", 1L);
    }

    @Test
    void reportsFinalizedConflictSourceWithoutAbsenceProof() throws Exception {
        SqliteConnectionManager connections = migrated("finalized-conflict-source.sqlite");
        try (Connection connection = connections.openConnection()) {
            ManagedCoopImportTestFixtures.insertAuthority(connection);
        }
        ManagedCoopImportRepository.SourceEvidence source =
                ManagedCoopImportTestFixtures.source("source-conflict", 0, 0, uuid(40));
        ManagedCoopImportRepository.BeginSessionRequest request =
                ManagedCoopImportTestFixtures.request("session-conflict", List.of(source));
        ManagedCoopImportRepository.DispositionBinding binding =
                ManagedCoopImportTestFixtures.managedBinding(
                        request, source, ManagedCoopImportRepository.DispositionKind.IMPORTED);
        try (PersistenceWriteQueue queue = new PersistenceWriteQueue(
                connections, new PersistenceHealthService(), null)) {
            ManagedCoopImportRepository repository =
                    new ManagedCoopImportRepository(connections, queue);
            committed(repository.beginSession(request));
            committed(repository.bindDispositionAtomically(
                    binding,
                    (connection, ignored) -> ManagedCoopImportTestFixtures.insertManagedBinding(
                            connection, binding, source)));
        }
        try (Connection connection = connections.openConnection();
             PreparedStatement authority = connection.prepareStatement("""
                     UPDATE managed_coop_authority
                     SET authority_state = 'CONFLICT', updated_at_ms = -40
                     WHERE authority_id = ?
                     """);
             PreparedStatement session = connection.prepareStatement("""
                     UPDATE managed_coop_import_sessions
                     SET state = 'FINALIZED_CONFLICT', active = 0,
                         final_command_id = 'forced-final-conflict',
                         updated_at_ms = -40, finalized_at_ms = -40
                     WHERE session_id = ?
                     """)) {
            authority.setString(1, ManagedCoopImportTestFixtures.AUTHORITY.authorityId());
            assertEquals(1, authority.executeUpdate());
            session.setString(1, request.envelope().sessionId());
            assertEquals(1, session.executeUpdate());
        }

        PersistenceIntegrityService.IntegrityReport report =
                new PersistenceIntegrityService(connections).inspect();

        assertIssue(report, "finalized_import_source_not_absent", 1L);
    }

    private SqliteConnectionManager migrated(String fileName) throws Exception {
        SqliteConnectionManager connections =
                new SqliteConnectionManager(tempDir.resolve(fileName));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        return connections;
    }

    private void insertProfile(Connection connection, String profileId, UUID currentUuid)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                  profile_id, current_npc_uuid, created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, 1, 1, 1)
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, currentUuid.toString());
            statement.executeUpdate();
        }
    }

    private void insertAlias(Connection connection, String profileId, UUID npcUuid,
                             boolean current) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO npc_uuid_aliases VALUES (?, ?, ?, 1)")) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, profileId);
            statement.setInt(3, current ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void insertAuthority(Connection connection,
                                 String authorityId,
                                 String state,
                                 int x) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_authority (
                  authority_id, world_name, coop_id, x, y, z, authority_state,
                  active, import_version, created_at_ms, updated_at_ms
                ) VALUES (?, 'default', 'Coop_Chicken', ?, 2, 3, ?, 1, 0, -10, -10)
                """)) {
            statement.setString(1, authorityId);
            statement.setInt(2, x);
            statement.setString(3, state);
            statement.executeUpdate();
        }
    }

    private void insertOperation(Connection connection,
                                 String operationId,
                                 String profileId,
                                 String authorityId,
                                 int x,
                                 int residentSlot,
                                 String kind,
                                 String state,
                                 boolean active) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_lifecycle_operations (
                  operation_id, operation_kind, profile_id, authority_id,
                  world_name, coop_id, x, y, z, resident_slot,
                  state, active, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, 'default', 'Coop_Chicken', ?, 2, 3, ?, ?, ?, -10, -10)
                """)) {
            int index = 1;
            statement.setString(index++, operationId);
            statement.setString(index++, kind);
            statement.setString(index++, profileId);
            statement.setString(index++, authorityId);
            statement.setInt(index++, x);
            statement.setInt(index++, residentSlot);
            statement.setString(index++, state);
            statement.setInt(index, active ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void insertImportSession(Connection connection,
                                     String sessionId,
                                     String authorityId,
                                     int x,
                                     String state,
                                     boolean active) throws Exception {
        boolean finalized = !"ACTIVE".equals(state);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_import_sessions (
                  session_id, authority_id, world_name, coop_id, x, y, z,
                  audit_version, audit_fingerprint, audit_envelope_json, audit_envelope_hash,
                  layout_id, resident_list_class_name, produce_payload, produce_fingerprint,
                  source_count, state, active, begin_command_id, final_command_id,
                  created_at_ms, updated_at_ms, finalized_at_ms
                ) VALUES (?, ?, 'default', 'Coop_Chicken', ?, 2, 3,
                  1, ?, '{}', ?, 'test-layout', 'java.util.ArrayList', '{}', ?,
                  0, ?, ?, ?, ?, -10, -10, ?)
                """)) {
            int index = 1;
            statement.setString(index++, sessionId);
            statement.setString(index++, authorityId);
            statement.setInt(index++, x);
            statement.setString(index++, "audit-" + sessionId);
            statement.setString(index++, "audit-envelope-" + sessionId);
            statement.setString(index++, "produce-" + sessionId);
            statement.setString(index++, state);
            statement.setInt(index++, active ? 1 : 0);
            statement.setString(index++, "begin-" + sessionId);
            statement.setString(index++, finalized ? "final-" + sessionId : null);
            statement.setLong(index, finalized ? -5L : 0L);
            statement.executeUpdate();
        }
    }

    private ManagedCoopImportRepository.MutationResult committed(
            PersistenceWriteQueue.WriteSubmission<ManagedCoopImportRepository.MutationResult> submission)
            throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<ManagedCoopImportRepository.MutationResult> outcome =
                submission.completion().get(3, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status());
        assertNull(outcome.failure());
        assertNotNull(outcome.value());
        return outcome.value();
    }

    private void assertIssue(PersistenceIntegrityService.IntegrityReport report,
                             String issueId,
                             long affectedGroups) {
        assertEquals(PersistenceIntegrityService.ReportStatus.COMPLETE, report.status());
        assertTrue(report.issues().stream().anyMatch(
                issue -> issue.id().equals(issueId)
                        && issue.affectedGroups() == affectedGroups),
                () -> "Missing integrity issue " + issueId + " in " + report.issues());
    }

    private String authorityId(int x) {
        return new ManagedCoopAuthorityKey("default", x, 2, 3).authorityId();
    }

    private UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
