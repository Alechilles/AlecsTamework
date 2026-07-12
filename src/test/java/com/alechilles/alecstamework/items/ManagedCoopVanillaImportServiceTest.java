package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionEnvelope;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.Status.COMPLETE_MANAGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end regression coverage for executable, restart-safe vanilla resident import. */
class ManagedCoopVanillaImportServiceTest {
    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceWriteQueue queue;
    private ManagedCoopResidentRepository residents;
    private CoopLifecycleOperationRepository lifecycle;
    private ManagedCoopImportRepository imports;
    private ManagedCoopCompositeIndexRefreshService composite;
    private ManagedCoopVanillaImportService service;
    private ManagedCoopImportControl importControl;
    private final ManagedCoopAuthorityKey authority =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("runtime-import.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        queue = new PersistenceWriteQueue(connections, new PersistenceHealthService(), null);
        residents = new ManagedCoopResidentRepository(connections, queue);
        lifecycle = new CoopLifecycleOperationRepository(connections, queue, residents);
        imports = new ManagedCoopImportRepository(connections, queue);
        NpcProfileRepository profiles = new NpcProfileRepository(connections, queue);
        ManagedCoopResidentIndex residentIndex = new ManagedCoopResidentIndex();
        ManagedCoopLifecycleOperationIndex operationIndex = new ManagedCoopLifecycleOperationIndex();
        composite = new ManagedCoopCompositeIndexRefreshService(
                new ManagedCoopResidentIndexRefreshService(residents, residentIndex, null),
                new ManagedCoopLifecycleOperationIndexRefreshService(lifecycle, operationIndex, null),
                residentIndex,
                operationIndex
        );
        importControl = new ManagedCoopImportControl();
        service = new ManagedCoopVanillaImportService(
                residents, lifecycle, imports, profiles, composite, importControl);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    @Test
    void emptyManagedCoopCompletesOnlyAfterTrustedCompositeRefresh() {
        CoopBlock coop = new CoopBlock();

        ManagedCoopVanillaImportService.SweepResult first = sweep(coop, -100L);
        ManagedCoopVanillaImportService.SweepResult second = sweep(coop, -101L);

        assertEquals(ManagedCoopVanillaImportService.Status.WRITE_QUEUED, first.status());
        assertEquals(COMPLETE_MANAGED, second.status());
        assertFalse(second.blocksManagedRuntime());
        assertTrue(composite.isTrusted());
    }

    @Test
    void importsOneHousedVanillaResidentWithoutSpawningAndRetiresImportOperation() {
        CoopBlock coop = coop("Mob_Chicken");

        ManagedCoopVanillaImportService.SweepResult reportOnly = sweep(coop, -999L);

        assertEquals(ManagedCoopVanillaImportService.Status.BLOCKED, reportOnly.status());
        assertTrue(reportOnly.detail().startsWith("managed_coop_import_approval_missing:"));
        assertEquals(ManagedCoopReadResult.Status.NOT_FOUND,
                residents.loadAuthority(authority, "coop_chicken").status());
        assertEquals(1, new VanillaCoopImportAdapter().auditForImport(coop).residents().size());
        ManagedCoopVanillaImportInspectionService.ImportInspection inspection =
                importControl.latestInspection(authority).orElseThrow();
        assertTrue(importControl.confirm(
                authority, inspection.auditFingerprint(), "test-operator").confirmed());

        ManagedCoopVanillaImportService.SweepResult result = null;
        boolean finalizationSubmitted = false;
        for (int attempt = 0; attempt < 20; attempt++) {
            result = sweep(coop, -1_000L - attempt);
            if ("finalize_import_authority".equals(result.detail())) {
                finalizationSubmitted = true;
                assertFalse(importControl.hasApproval(authority));
            }
            if (result.status() == COMPLETE_MANAGED) {
                break;
            }
        }

        assertEquals(COMPLETE_MANAGED, result.status(), result.detail());
        assertFalse(result.blocksManagedRuntime());
        assertTrue(new VanillaCoopImportAdapter().auditForImport(coop).residents().isEmpty());
        assertEquals(1, residents.loadAllActiveResidents().value().size());
        ManagedCoopReadResult<List<CoopLifecycleOperationRepository.OperationRecord>> active =
                lifecycle.loadAllActiveOperations();
        assertEquals(ManagedCoopReadResult.Status.LOADED, active.status());
        assertTrue(active.value().isEmpty());
        assertTrue(finalizationSubmitted);
        assertFalse(importControl.hasApproval(authority));
        assertEquals(ManagedCoopVanillaImportInspectionService.InspectionStatus.CLEAR,
                importControl.latestInspection(authority).orElseThrow().status());
        assertTrue(composite.isTrusted());
    }

    @Test
    void finalizedGenerationCannotCollideWithIdenticalRestoredSource() {
        CoopBlock first = coop("Mob_Chicken");
        assertEquals(ManagedCoopVanillaImportService.Status.BLOCKED,
                sweep(first, -1_500L).status());
        ManagedCoopVanillaImportInspectionService.ImportInspection generationOne =
                importControl.latestInspection(authority).orElseThrow();
        assertEquals(1, generationOne.importGeneration());
        assertTrue(importControl.confirm(
                authority, generationOne.auditFingerprint(), "generation-one-operator")
                .confirmed());

        ManagedCoopVanillaImportService.SweepResult result = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            result = sweep(first, -1_501L - attempt);
            if (result.status() == COMPLETE_MANAGED) {
                break;
            }
        }
        assertNotNull(result);
        assertEquals(COMPLETE_MANAGED, result.status(), result.detail());
        assertEquals(1, residents.loadAuthority(authority, "coop_chicken")
                .value().importVersion());

        CoopBlock restored = coop("Mob_Chicken");
        ManagedCoopVanillaImportInspectionService.ImportInspection generationTwoFirst =
                service.inspect(context(true), restored, -1_550L);
        ManagedCoopVanillaImportInspectionService.ImportInspection generationTwoRepeated =
                service.inspect(context(true), restored, -1_551L);

        assertEquals(2, generationTwoFirst.importGeneration());
        assertEquals(2, generationTwoRepeated.importGeneration());
        assertEquals(generationOne.sources().getFirst().sourceFingerprint(),
                generationTwoFirst.sources().getFirst().sourceFingerprint(),
                "the restored vanilla evidence itself should be identical");
        assertNotEquals(generationOne.auditFingerprint(),
                generationTwoFirst.auditFingerprint());
        assertNotEquals(generationOne.sessionId(), generationTwoFirst.sessionId());
        assertNotEquals(generationOne.sources().getFirst().sourceId(),
                generationTwoFirst.sources().getFirst().sourceId());
        assertEquals(generationTwoFirst.auditFingerprint(),
                generationTwoRepeated.auditFingerprint());
        assertEquals(generationTwoFirst.sessionId(), generationTwoRepeated.sessionId());
        assertEquals(generationTwoFirst.sources().getFirst().sourceId(),
                generationTwoRepeated.sources().getFirst().sourceId());

        assertTrue(importControl.confirm(
                authority, generationTwoRepeated.auditFingerprint(), "generation-two-operator")
                .confirmed());
        assertEquals(ManagedCoopVanillaImportService.Status.WRITE_QUEUED,
                sweep(restored, -1_552L).status());
        assertEquals(ManagedCoopVanillaImportService.Status.WRITE_QUEUED,
                sweep(restored, -1_553L).status());

        ManagedCoopImportRepository.SessionRecord active =
                imports.loadActiveSession(authority, "coop_chicken").value();
        assertNotNull(active);
        assertEquals(generationTwoFirst.sessionId(), active.envelope().sessionId());
        assertEquals(generationTwoFirst.auditFingerprint(),
                active.envelope().auditFingerprint());
        assertEquals(2, JsonParser.parseString(active.envelope().auditEnvelopeJson())
                .getAsJsonObject().get(ManagedCoopImportGeneration.ENVELOPE_FIELD).getAsInt());
        assertEquals(generationTwoFirst.sources().getFirst().sourceId(),
                imports.loadSources(active.envelope().sessionId()).value().getFirst()
                        .evidence().sourceId());
        assertEquals(AuthorityState.IMPORTING_TO_TWORK,
                residents.loadAuthority(authority, "coop_chicken").value().state());
        assertEquals(1, residents.loadAuthority(authority, "coop_chicken")
                .value().importVersion(),
                "the completed version advances only when generation two finalizes");
    }

    @Test
    void changedLiveAuditRevokesApprovalBeforeAnyAuthorityWrite() {
        CoopBlock original = coop("Mob_Chicken");
        CoopBlock changed = coop("Mob_Cow");
        ManagedCoopVanillaImportInspectionService.ImportInspection first =
                service.inspect(context(true), original, -2_000L);
        assertTrue(importControl.confirm(
                authority, first.auditFingerprint(), "test-operator").confirmed());

        ManagedCoopVanillaImportService.SweepResult result = sweep(changed, -2_001L);

        assertEquals(ManagedCoopVanillaImportService.Status.BLOCKED, result.status());
        assertTrue(result.detail().startsWith("managed_coop_import_approval_missing:"));
        assertFalse(importControl.hasApproval(authority));
        assertNotEquals(first.auditFingerprint(),
                importControl.latestInspection(authority).orElseThrow().auditFingerprint());
        assertEquals(ManagedCoopReadResult.Status.NOT_FOUND,
                residents.loadAuthority(authority, "coop_chicken").status());
        assertEquals(1, new VanillaCoopImportAdapter().auditForImport(changed).residents().size());
    }

    @Test
    void inspectionReportsOverflowSourcesExplicitly() {
        CoopBlock coop = coop("Mob_Chicken", "Mob_Pigeon");

        ManagedCoopVanillaImportInspectionService.ImportInspection inspection =
                service.inspect(context(true, 1), coop, -2_500L);

        assertEquals(ManagedCoopVanillaImportInspectionService.InspectionStatus.APPROVAL_REQUIRED,
                inspection.status());
        assertEquals(1L, inspection.overflowCount());
        assertEquals(1L, inspection.sources().stream()
                .filter(ManagedCoopVanillaImportInspectionService.SourceSummary::overflow)
                .count());
    }

    @Test
    void activeImportRequiresFreshApprovalAfterRuntimeRestart() {
        CoopBlock coop = coop("Mob_Chicken");
        sweep(coop, -3_000L);
        ManagedCoopVanillaImportInspectionService.ImportInspection inspection =
                importControl.latestInspection(authority).orElseThrow();
        assertTrue(importControl.confirm(
                authority, inspection.auditFingerprint(), "test-operator").confirmed());

        assertEquals(ManagedCoopVanillaImportService.Status.WRITE_QUEUED,
                sweep(coop, -3_001L).status());
        assertEquals(ManagedCoopVanillaImportService.Status.WRITE_QUEUED,
                sweep(coop, -3_002L).status());
        ManagedCoopImportRepository.SessionRecord session =
                imports.loadActiveSession(authority, "coop_chicken").value();
        assertNotNull(session);

        ManagedCoopImportControl restartedControl = new ManagedCoopImportControl();
        ManagedCoopVanillaImportService restarted = new ManagedCoopVanillaImportService(
                residents, lifecycle, imports, new NpcProfileRepository(connections, queue),
                composite, restartedControl);
        ManagedCoopVanillaImportService.SweepResult resumed =
                sweep(restarted, coop, -3_003L);

        assertEquals(ManagedCoopVanillaImportService.Status.BLOCKED, resumed.status());
        assertTrue(resumed.detail().startsWith("managed_coop_import_approval_missing:"));
        assertEquals(session.envelope().auditFingerprint(),
                restartedControl.latestInspection(authority).orElseThrow().auditFingerprint());
        assertEquals(1, new VanillaCoopImportAdapter().auditForImport(coop).residents().size());
    }

    @Test
    void restartReprovesPersistedAbsenceBeforeFinalizingRestoredVanillaSource() {
        CoopBlock original = coop("Mob_Chicken");
        sweep(original, -3_100L);
        ManagedCoopVanillaImportInspectionService.ImportInspection inspection =
                importControl.latestInspection(authority).orElseThrow();
        assertTrue(importControl.confirm(
                authority, inspection.auditFingerprint(), "test-operator").confirmed());

        ManagedCoopImportRepository.SourceRecord verifiedSource = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            sweep(original, -3_101L - attempt);
            ManagedCoopReadResult<ManagedCoopImportRepository.SessionRecord> sessionRead =
                    imports.loadActiveSession(authority, "coop_chicken");
            if (sessionRead.status() != ManagedCoopReadResult.Status.LOADED) {
                continue;
            }
            List<ManagedCoopImportRepository.SourceRecord> sources =
                    imports.loadSources(sessionRead.value().envelope().sessionId()).value();
            if (sources != null && sources.getFirst().neutralizationState()
                    == NeutralizationState.VERIFIED_ABSENT) {
                verifiedSource = sources.getFirst();
                break;
            }
        }
        assertNotNull(verifiedSource);
        assertTrue(new VanillaCoopImportAdapter().auditForImport(original).residents().isEmpty());
        String firstBootProof = verifiedSource.absenceProofJson();

        // Simulate a crash where SQLite committed the proof but the block save restored its source.
        CoopBlock restored = coop("Mob_Chicken");
        ManagedCoopImportControl restartedControl = new ManagedCoopImportControl();
        ManagedCoopVanillaImportService restarted = new ManagedCoopVanillaImportService(
                residents, lifecycle, imports, new NpcProfileRepository(connections, queue),
                composite, restartedControl);
        ManagedCoopVanillaImportService.SweepResult reportOnly =
                sweep(restarted, restored, -3_150L);
        assertEquals(ManagedCoopVanillaImportService.Status.BLOCKED, reportOnly.status());
        ManagedCoopVanillaImportInspectionService.ImportInspection restartedInspection =
                restartedControl.latestInspection(authority).orElseThrow();
        assertTrue(restartedControl.confirm(
                authority, restartedInspection.auditFingerprint(), "restart-operator").confirmed());

        ManagedCoopVanillaImportService.SweepResult revalidation =
                sweep(restarted, restored, -3_151L);

        assertEquals(ManagedCoopVanillaImportService.Status.SOURCE_REMOVED,
                revalidation.status());
        assertTrue(new VanillaCoopImportAdapter().auditForImport(restored).residents().isEmpty());
        assertEquals(AuthorityState.IMPORTING_TO_TWORK,
                residents.loadAuthority(authority, "coop_chicken").value().state());
        ManagedCoopImportRepository.SessionRecord active =
                imports.loadActiveSession(authority, "coop_chicken").value();
        ManagedCoopImportRepository.SourceRecord refreshed =
                imports.loadSources(active.envelope().sessionId()).value().getFirst();
        assertNotEquals(firstBootProof, refreshed.absenceProofJson());

        ManagedCoopVanillaImportService.SweepResult result = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            result = sweep(restarted, restored, -3_152L - attempt);
            if (result.status() == COMPLETE_MANAGED) {
                break;
            }
        }
        assertNotNull(result);
        assertEquals(COMPLETE_MANAGED, result.status(), result.detail());
    }

    @Test
    void activeVersionOneAuditRemainsReadableAfterVersionTwoFingerprintUpgrade() {
        CoopBlock coop = coop("Mob_Chicken");
        VanillaCoopImportAuditPreparer.PreparedAudit prepared =
                new VanillaCoopImportAuditPreparer().prepare(
                        new VanillaCoopImportAuditPreparer.Request(
                                authority, "coop_chicken", 4,
                                new VanillaCoopImportAdapter().auditForImport(coop),
                                List.of(), List.of(), ignored -> null, -3_500L));
        SessionEnvelope current = prepared.beginRequest().envelope();
        JsonObject legacyAudit = JsonParser.parseString(current.auditEnvelopeJson())
                .getAsJsonObject();
        legacyAudit.addProperty("version", 1);
        legacyAudit.remove(ManagedCoopImportGeneration.ENVELOPE_FIELD);
        legacyAudit.remove("sourcePlans");
        String legacyJson = legacyAudit.toString();
        String legacyFingerprint = VanillaCoopImportEvidenceCodec.sha256(legacyJson);
        SessionEnvelope legacy = new SessionEnvelope(
                "managed-coop-import:legacy-v1-test",
                current.authorityKey(), current.coopId(), 1,
                legacyFingerprint, legacyJson,
                VanillaCoopImportEvidenceCodec.sha256(legacyJson),
                current.layoutId(), current.coopAssetId(), current.residentListClassName(),
                current.producePayload(), current.produceFingerprint(),
                VanillaCoopImportEvidenceCodec.sha256("begin:legacy-v1-test"),
                current.createdAtMs());

        residents.registerAuthority(
                authority, "coop_chicken", AuthorityState.VANILLA_DISCOVERED, -3_501L);
        assertTrue(queue.awaitIdle(5_000L));
        imports.beginSession(new ManagedCoopImportRepository.BeginSessionRequest(
                legacy, prepared.beginRequest().sources()));
        assertTrue(queue.awaitIdle(5_000L));

        ManagedCoopVanillaImportInspectionService.ImportInspection inspection =
                service.inspect(context(true), coop, -3_502L);

        assertEquals(
                ManagedCoopVanillaImportInspectionService.InspectionStatus
                        .ACTIVE_IMPORT_APPROVAL_REQUIRED,
                inspection.status());
        assertEquals(legacyFingerprint, inspection.auditFingerprint());
        assertEquals(1, inspection.importGeneration());
        assertEquals(1, inspection.sourceCount());
        assertEquals(0L, inspection.overflowCount(),
                "a missing v1 overflow field must decode as false");
    }

    @Test
    void unmanagedVanillaCoopRemainsVanillaAuthorityWithoutInspectionOrWrites() {
        CoopBlock coop = coop("Mob_Chicken");

        ManagedCoopVanillaImportService.SweepResult result =
                service.sweep(context(false), coop, -4_000L);
        assertTrue(queue.awaitIdle(5_000L));

        assertEquals(ManagedCoopVanillaImportService.Status.NOT_MANAGED, result.status());
        assertFalse(result.blocksManagedRuntime());
        assertTrue(importControl.latestInspection(authority).isEmpty());
        assertEquals(ManagedCoopReadResult.Status.NOT_FOUND,
                residents.loadAuthority(authority, "coop_chicken").status());
        assertEquals(1, new VanillaCoopImportAdapter().auditForImport(coop).residents().size());
    }

    private ManagedCoopVanillaImportService.SweepResult sweep(CoopBlock coop, long nowMs) {
        return sweep(service, coop, nowMs);
    }

    private ManagedCoopVanillaImportService.SweepResult sweep(
            ManagedCoopVanillaImportService target,
            CoopBlock coop,
            long nowMs) {
        ManagedCoopVanillaImportService.SweepResult result =
                target.sweep(context(true), coop, nowMs);
        assertTrue(queue.awaitIdle(5_000L));
        return result;
    }

    private ManagedCoopVanillaImportService.ImportContext context(boolean enabled) {
        return context(enabled, 4);
    }

    private ManagedCoopVanillaImportService.ImportContext context(boolean enabled,
                                                                   int maximumResidents) {
        return new ManagedCoopVanillaImportService.ImportContext(
                authority, "coop_chicken", maximumResidents, enabled);
    }

    private CoopBlock coop(String... roleIds) {
        java.util.ArrayList<CoopBlock.CoopResident> residents = new java.util.ArrayList<>();
        for (String roleId : roleIds) {
            CapturedNPCMetadata metadata = new CapturedNPCMetadata();
            metadata.setNpcNameKey(roleId);
            metadata.setIconPath("Icons/" + roleId + ".png");
            metadata.setFullItemIcon("Icons/" + roleId + "_Full.png");
            residents.add(new CoopBlock.CoopResident(
                    metadata, null, Instant.ofEpochMilli(-500L)));
        }
        return new CoopBlock(
                "coop_chicken",
                residents,
                new SimpleItemContainer((short) 5)
        );
    }
}
