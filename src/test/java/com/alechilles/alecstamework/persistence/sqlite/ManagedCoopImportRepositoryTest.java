package com.alechilles.alecstamework.persistence.sqlite;

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

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.FinalizationRequest;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.MutationResult;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.MutationStatus;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationProof;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationState;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionState;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportTestFixtures.AUTHORITY;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportTestFixtures.COOP_ID;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportTestFixtures.hash;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the durable managed-coop import session and source journal. */
class ManagedCoopImportRepositoryTest {
    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceWriteQueue writeQueue;
    private ManagedCoopImportRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("import.sqlite"));
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
    void beginAtomicallyFreezesEveryEnvelopeAndAllowsOnlyOneActiveSession()
            throws Exception {
        SourceEvidence source = ManagedCoopImportTestFixtures.source(
                "source-a", 0, 0, UUID.randomUUID());
        ManagedCoopImportRepository.BeginSessionRequest request =
                ManagedCoopImportTestFixtures.request("session-a", List.of(source));

        MutationResult begun = committed(repository.beginSession(request));
        MutationResult replayed = committed(repository.beginSession(request));
        MutationResult changedReplay = committed(repository.beginSession(
                ManagedCoopImportTestFixtures.request("session-a", List.of(
                        ManagedCoopImportTestFixtures.source(
                                "source-b", 0, 0, UUID.randomUUID())))));
        MutationResult secondActive = committed(repository.beginSession(
                ManagedCoopImportTestFixtures.request("session-b", List.of())));

        assertEquals(MutationStatus.APPLIED, begun.status());
        assertEquals(SessionState.ACTIVE, begun.session().state());
        assertEquals(-100L, begun.session().envelope().createdAtMs());
        assertEquals(MutationStatus.IDEMPOTENT, replayed.status());
        assertEquals(MutationStatus.CONFLICT, changedReplay.status());
        assertEquals(MutationStatus.CONFLICT, secondActive.status());
        assertEquals(AuthorityState.IMPORTING_TO_TWORK.name(), authorityState());

        ManagedCoopReadResult<List<ManagedCoopImportRepository.SourceRecord>> loaded =
                repository.loadSources("session-a");
        assertEquals(ManagedCoopReadResult.Status.LOADED, loaded.status());
        assertEquals(List.of(source), loaded.value().stream()
                .map(ManagedCoopImportRepository.SourceRecord::evidence).toList());
        assertEquals(NeutralizationState.NOT_AUTHORIZED,
                loaded.value().getFirst().neutralizationState());
    }

    @Test
    void managedDispositionProofAndFinalizationAreExactReplaySafeTransactions()
            throws Exception {
        SourceEvidence source = ManagedCoopImportTestFixtures.source(
                "source-managed", 0, 0, UUID.randomUUID());
        ManagedCoopImportRepository.BeginSessionRequest request =
                ManagedCoopImportTestFixtures.request("session-managed", List.of(source));
        committed(repository.beginSession(request));
        DispositionBinding binding = ManagedCoopImportTestFixtures.managedBinding(
                request, source, DispositionKind.IMPORTED);

        MutationResult bound = committed(repository.bindDispositionAtomically(
                binding,
                (connection, ignored) -> ManagedCoopImportTestFixtures.insertManagedBinding(
                        connection, binding, source)));
        MutationResult replayed = committed(repository.bindDisposition(binding));
        MutationResult conflicting = committed(repository.bindDisposition(
                new DispositionBinding(
                        binding.sessionId(), binding.sourceId(), binding.auditFingerprint(),
                        binding.sourceFingerprint(), hash("different-command"),
                         binding.disposition(), binding.operationId(), binding.residentId(),
                         binding.profileId(), null, null, -80L)));
        MutationResult premature = committed(repository.finalizeAuthority(
                finalization(request, AuthorityState.TWORK_MANAGED)));

        assertEquals(MutationStatus.APPLIED, bound.status());
        assertEquals(NeutralizationState.AUTHORIZED, bound.source().neutralizationState());
        assertEquals(MutationStatus.IDEMPOTENT, replayed.status());
        assertEquals(MutationStatus.CONFLICT, conflicting.status());
        assertEquals(MutationStatus.INVARIANT_BLOCKED, premature.status());

        NeutralizationProof proof = proof(request, source, binding, "{\"absent\":true}");
        MutationResult verified = committed(repository.recordVerifiedNeutralization(proof));
        MutationResult proofReplay = committed(repository.recordVerifiedNeutralization(proof));
        MutationResult differentProof = committed(repository.recordVerifiedNeutralization(
                proof(request, source, binding, "{\"absent\":true,\"second\":true}")));

        assertEquals(MutationStatus.APPLIED, verified.status());
        assertEquals(NeutralizationState.VERIFIED_ABSENT,
                verified.source().neutralizationState());
        assertEquals(-60L, verified.source().verifiedAbsentAtMs());
        assertEquals(MutationStatus.IDEMPOTENT, proofReplay.status());
        assertEquals(MutationStatus.CONFLICT, differentProof.status());
        assertOperationComplete(binding.operationId());

        FinalizationRequest finalization = finalization(
                request, AuthorityState.TWORK_MANAGED);
        MutationResult finalized = committed(repository.finalizeAuthority(finalization));
        MutationResult finalReplay = committed(repository.finalizeAuthority(finalization));

        assertEquals(MutationStatus.APPLIED, finalized.status());
        assertEquals(SessionState.FINALIZED_MANAGED, finalized.session().state());
        assertFalse(finalized.session().active());
        assertEquals(MutationStatus.IDEMPOTENT, finalReplay.status());
        assertEquals(AuthorityState.TWORK_MANAGED.name(), authorityState());
        assertEquals(1, authorityImportVersion());
        assertEquals(ManagedCoopReadResult.Status.NOT_FOUND,
                repository.loadActiveSession(AUTHORITY, COOP_ID).status());
    }

    @Test
    void quarantineIsTerminalButNeverAuthorizesOrRequiresNeutralization() throws Exception {
        SourceEvidence source = ManagedCoopImportTestFixtures.source(
                "source-quarantine", 0, 0, UUID.randomUUID());
        ManagedCoopImportRepository.BeginSessionRequest request =
                ManagedCoopImportTestFixtures.request("session-quarantine", List.of(source));
        committed(repository.beginSession(request));
        DispositionBinding binding = ManagedCoopImportTestFixtures.quarantineBinding(
                request, source);

        MutationResult quarantined = committed(repository.bindDispositionAtomically(
                binding,
                (connection, ignored) -> ManagedCoopImportTestFixtures.insertConflict(
                        connection, binding, source)));
        MutationResult forbiddenNeutralization = committed(
                repository.recordVerifiedNeutralization(
                        proof(request, source, binding, "{\"absent\":true}")));
        MutationResult wrongTarget = committed(repository.finalizeAuthority(
                finalization(request, AuthorityState.TWORK_MANAGED)));
        MutationResult finalized = committed(repository.finalizeAuthority(
                finalization(request, AuthorityState.CONFLICT)));

        assertEquals(MutationStatus.APPLIED, quarantined.status());
        assertEquals(NeutralizationState.NOT_REQUIRED,
                quarantined.source().neutralizationState());
        assertEquals(MutationStatus.CONFLICT, forbiddenNeutralization.status());
        assertEquals("quarantined_source_must_remain_untouched",
                forbiddenNeutralization.detail());
        assertEquals(MutationStatus.CONFLICT, wrongTarget.status());
        assertEquals(MutationStatus.APPLIED, finalized.status());
        assertEquals(SessionState.FINALIZED_CONFLICT, finalized.session().state());
        assertEquals(AuthorityState.CONFLICT.name(), authorityState());
        assertEquals("UNRESOLVED", scalar(
                "SELECT resolution_state FROM coop_import_conflicts WHERE conflict_id = '"
                        + binding.conflictId() + "'"));
        assertEquals("NOT_REQUIRED|0", scalar(
                "SELECT neutralization_state || '|' || verified_absent_at_ms "
                        + "FROM managed_coop_import_sources WHERE source_id = '"
                        + source.sourceId() + "'"));
    }

    private NeutralizationProof proof(
            ManagedCoopImportRepository.BeginSessionRequest request,
            SourceEvidence source,
            DispositionBinding binding,
            String absenceJson) {
        return new NeutralizationProof(
                request.envelope().sessionId(),
                source.sourceId(),
                request.envelope().auditFingerprint(),
                source.sourceFingerprint(),
                source.sourcePayloadHash(),
                source.sourceSlot(),
                source.sourceOrder(),
                source.persistentUuid(),
                binding.commandId(),
                absenceJson,
                hash(absenceJson),
                1,
                -60L
        );
    }

    private FinalizationRequest finalization(
            ManagedCoopImportRepository.BeginSessionRequest request,
            AuthorityState target) {
        return new FinalizationRequest(
                request.envelope().sessionId(), AUTHORITY, COOP_ID,
                request.envelope().auditFingerprint(), hash("final:" + target), target, -40L);
    }

    private MutationResult committed(
            PersistenceWriteQueue.WriteSubmission<MutationResult> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<MutationResult> outcome =
                submission.completion().get(3, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status());
        assertNull(outcome.failure());
        assertNotNull(outcome.value());
        return outcome.value();
    }

    private String authorityState() throws Exception {
        return scalar("SELECT authority_state FROM managed_coop_authority WHERE authority_id = '"
                + AUTHORITY.authorityId() + "'");
    }

    private int authorityImportVersion() throws Exception {
        return Integer.parseInt(scalar(
                "SELECT import_version FROM managed_coop_authority WHERE authority_id = '"
                        + AUTHORITY.authorityId() + "'"));
    }

    private void assertOperationComplete(String operationId) throws Exception {
        assertEquals("COMPLETE|3|0|-60", scalar(
                "SELECT state || '|' || generation || '|' || active || '|' || completed_at_ms "
                        + "FROM coop_lifecycle_operations WHERE operation_id = '"
                        + operationId + "'"));
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
