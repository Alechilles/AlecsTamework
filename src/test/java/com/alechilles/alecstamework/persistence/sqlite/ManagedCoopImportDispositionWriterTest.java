package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter.ManagedRows;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter.QuarantineRows;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.BeginSessionRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationProof;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportTestFixtures.AUTHORITY;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportTestFixtures.COOP_ID;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportTestFixtures.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Atomic row-authoring regressions for executable import source bindings. */
class ManagedCoopImportDispositionWriterTest {
    @TempDir
    Path tempDir;
    private SqliteConnectionManager connections;
    private PersistenceWriteQueue queue;
    private ManagedCoopImportRepository repository;
    private ManagedCoopImportDispositionWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("writer.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            ManagedCoopImportTestFixtures.insertAuthority(connection);
        }
        queue = new PersistenceWriteQueue(connections, new PersistenceHealthService(), null);
        repository = new ManagedCoopImportRepository(connections, queue);
        writer = new ManagedCoopImportDispositionWriter(repository);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    @Test
    void importedSourceAtomicallyCreatesAuthorizedGenerationTwoReferenceGraph()
            throws Exception {
        UUID uuid = new UUID(0L, 41L);
        SourceEvidence source = ManagedCoopImportTestFixtures.source("source-a", 0, 0, uuid);
        BeginSessionRequest request = ManagedCoopImportTestFixtures.request("session-a", List.of(source));
        committed(repository.beginSession(request));
        DispositionBinding binding = ManagedCoopImportTestFixtures.managedBinding(
                request, source, DispositionKind.IMPORTED);

        MutationResult result = committed(writer.bindManaged(new ManagedRows(
                binding, source, AUTHORITY, COOP_ID, 0, uuid, source.roleId(),
                source.managedSnapshotJson(), source.managedSnapshotHash(),
                source.managedSnapshotVersion(), ResidentState.HOUSED, 0L, true)));

        assertEquals(ManagedCoopImportRepository.MutationStatus.APPLIED, result.status());
        assertEquals(NeutralizationState.AUTHORIZED, result.source().neutralizationState());
        assertEquals("SOURCE_RETIRE_REQUESTED", scalar(
                "SELECT state FROM coop_lifecycle_operations WHERE operation_id = '"
                        + binding.operationId() + "'"));
        assertEquals("2", scalar(
                "SELECT generation FROM coop_lifecycle_operations WHERE operation_id = '"
                        + binding.operationId() + "'"));
    }

    @Test
    void verifiedAbsenceCompletesImportOperationBeforeFinalizationCanProceed()
            throws Exception {
        UUID uuid = new UUID(0L, 42L);
        SourceEvidence source = ManagedCoopImportTestFixtures.source("source-b", 0, 0, uuid);
        BeginSessionRequest request = ManagedCoopImportTestFixtures.request("session-b", List.of(source));
        committed(repository.beginSession(request));
        DispositionBinding binding = ManagedCoopImportTestFixtures.managedBinding(
                request, source, DispositionKind.IMPORTED);
        committed(writer.bindManaged(new ManagedRows(
                binding, source, AUTHORITY, COOP_ID, 0, uuid, source.roleId(),
                source.managedSnapshotJson(), source.managedSnapshotHash(),
                source.managedSnapshotVersion(), ResidentState.HOUSED, 0L, true)));
        String proofJson = "{\"absent\":true}";

        MutationResult result = committed(repository.recordVerifiedNeutralization(
                new NeutralizationProof(
                        request.envelope().sessionId(), source.sourceId(),
                        request.envelope().auditFingerprint(), source.sourceFingerprint(),
                        source.sourcePayloadHash(), source.sourceSlot(), source.sourceOrder(),
                        source.persistentUuid(), binding.commandId(), proofJson, hash(proofJson),
                        1, -70L)));

        assertEquals(NeutralizationState.VERIFIED_ABSENT, result.source().neutralizationState());
        assertEquals("COMPLETE:3:0", scalar(
                "SELECT state || ':' || generation || ':' || active "
                        + "FROM coop_lifecycle_operations WHERE operation_id = '"
                        + binding.operationId() + "'"));
    }

    @Test
    void quarantinedSourceCreatesNoResidentOrLifecycleOperation() throws Exception {
        SourceEvidence source = ManagedCoopImportTestFixtures.source("source-c", 0, 0, null, null);
        BeginSessionRequest request = ManagedCoopImportTestFixtures.request("session-c", List.of(source));
        committed(repository.beginSession(request));
        DispositionBinding binding = ManagedCoopImportTestFixtures.quarantineBinding(request, source);

        MutationResult result = committed(writer.bindQuarantined(new QuarantineRows(
                binding, source, AUTHORITY, COOP_ID)));

        assertEquals(ManagedCoopImportRepository.MutationStatus.APPLIED, result.status());
        assertEquals(NeutralizationState.NOT_REQUIRED, result.source().neutralizationState());
        assertEquals("0", scalar("SELECT COUNT(*) FROM managed_coop_residents"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM coop_lifecycle_operations"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM coop_import_conflicts"));
    }

    private MutationResult committed(PersistenceWriteQueue.WriteSubmission<MutationResult> submission)
            throws Exception {
        PersistenceWriteQueue.WriteOutcome<MutationResult> outcome =
                submission.completion().get(5, TimeUnit.SECONDS);
        assertTrue(outcome.isCommitted(), outcome.failureReason());
        return outcome.value();
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
