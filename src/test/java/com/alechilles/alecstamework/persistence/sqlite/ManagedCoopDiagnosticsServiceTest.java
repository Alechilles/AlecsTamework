package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedCoopDiagnosticsServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsCleanEmptyV5Runtime() {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            ManagedCoopDiagnosticsService.AuditReport report =
                    runtime.getManagedCoopDiagnosticsService().inspect();

            assertEquals(ManagedCoopDiagnosticsService.ReportStatus.COMPLETE, report.status());
            assertEquals(0L, report.activeAuthorities());
            assertEquals(0L, report.activeResidents());
            assertEquals(0L, report.activeOperations());
            assertTrue(report.compositeIndexTrusted());
            assertFalse(report.requiresAttention());
        }
    }

    @Test
    void surfacesAuthorityStateAndUnresolvedImportConflict() throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            SqliteConnectionManager connections = new SqliteConnectionManager(runtime.getSqlitePath());
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

            ManagedCoopDiagnosticsService.AuditReport report =
                    runtime.getManagedCoopDiagnosticsService().inspect();

            assertEquals(1L, report.activeAuthoritiesByState().get("CONFLICT"));
            assertEquals(1L, report.unresolvedImportConflicts());
            assertTrue(report.requiresAttention());
        }
    }
}
