package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionAdoptionGateway.AdoptionRequest;
import com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionAdoptionGateway.AdoptionResult;
import com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionAdoptionGateway.InspectionRequest;
import com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionAdoptionGateway.InspectionResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.server.core.entity.reference.PersistentRef;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.items.ManagedCoopVanillaImportService.Status.COMPLETE_MANAGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end ordering regression for adopting, rather than duplicating, a deployed source. */
class ManagedCoopDeployedProjectionImportServiceTest {
    private static final UUID SOURCE = new UUID(0L, 401L);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceWriteQueue queue;
    private ManagedCoopResidentRepository residents;
    private CoopLifecycleOperationRepository lifecycle;
    private ManagedCoopImportRepository imports;
    private ManagedCoopImportControl control;
    private RecordingGateway projections;
    private ManagedCoopVanillaImportService service;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("deployed-import.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        queue = new PersistenceWriteQueue(connections, new PersistenceHealthService(), null);
        residents = new ManagedCoopResidentRepository(connections, queue);
        lifecycle = new CoopLifecycleOperationRepository(connections, queue, residents);
        imports = new ManagedCoopImportRepository(connections, queue);
        NpcProfileRepository profiles = new NpcProfileRepository(connections, queue);
        ManagedCoopResidentIndex residentIndex = new ManagedCoopResidentIndex();
        ManagedCoopLifecycleOperationIndex operationIndex =
                new ManagedCoopLifecycleOperationIndex();
        ManagedCoopCompositeIndexRefreshService composite =
                new ManagedCoopCompositeIndexRefreshService(
                        new ManagedCoopResidentIndexRefreshService(
                                residents, residentIndex, null),
                        new ManagedCoopLifecycleOperationIndexRefreshService(
                                lifecycle, operationIndex, null),
                        residentIndex,
                        operationIndex);
        control = new ManagedCoopImportControl();
        projections = new RecordingGateway();
        service = new ManagedCoopVanillaImportService(
                residents,
                lifecycle,
                imports,
                profiles,
                composite,
                new ManagedCoopImportDispositionWriter(imports),
                new VanillaCoopImportAdapter(),
                new VanillaCoopImportAuditPreparer(),
                new VanillaCoopImportEvidenceCodec(),
                new VanillaCoopImportNeutralizer(),
                new VanillaCoopImportAbsenceVerifier(),
                control,
                projections);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    @Test
    void durableRowsCommitBeforeTheSameLiveUuidIsDetachedAndNeutralized() {
        CoopBlock coop = deployedCoop();
        ManagedCoopVanillaImportService.ImportContext context =
                new ManagedCoopVanillaImportService.ImportContext(
                        AUTHORITY, "coop_chicken", 4, true);
        ManagedCoopVanillaImportInspectionService.ImportInspection inspection =
                service.inspect(context, coop, -1_000L);
        assertTrue(control.confirm(
                AUTHORITY, inspection.auditFingerprint(), "test-operator").confirmed());

        ManagedCoopVanillaImportService.SweepResult result = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            result = service.sweep(context, coop, -1_001L - attempt);
            assertTrue(queue.awaitIdle(5_000L));
            if (result.status() == COMPLETE_MANAGED) {
                break;
            }
        }

        assertNotNull(result);
        assertEquals(COMPLETE_MANAGED, result.status(), result.detail());
        assertEquals(1, projections.adoptionCalls);
        assertTrue(projections.observedCommittedImportOperation);
        assertTrue(new VanillaCoopImportAdapter().auditForImport(coop).residents().isEmpty());
        List<ResidentRecord> active = residents.loadAllActiveResidents().value();
        assertEquals(1, active.size());
        ResidentRecord resident = active.getFirst();
        assertEquals(ResidentState.DEPLOYED, resident.state());
        assertEquals(SOURCE, resident.residentUuid());
        assertEquals(SOURCE, resident.sourceNpcUuid());
        assertEquals(SOURCE, resident.deployedNpcUuid());
        assertTrue(lifecycle.loadAllActiveOperations().value().isEmpty());
    }

    private CoopBlock deployedCoop() {
        CapturedNPCMetadata metadata = new CapturedNPCMetadata();
        metadata.setNpcNameKey("Mob_Chicken");
        CoopBlock.CoopResident resident = new CoopBlock.CoopResident(
                metadata, new PersistentRef(SOURCE), Instant.ofEpochMilli(-500L));
        resident.setDeployedToWorld(true);
        return new CoopBlock(
                "coop_chicken", List.of(resident), new SimpleItemContainer((short) 5));
    }

    private final class RecordingGateway
            implements ManagedCoopVanillaProjectionAdoptionGateway {
        private int adoptionCalls;
        private boolean observedCommittedImportOperation;

        @Override
        public InspectionResult inspect(InspectionRequest request) {
            String snapshot = new VanillaCoopImportEvidenceCodec().managedSnapshot(
                    request.sourceNpcUuid(), request.coopId(),
                    request.managedResidentSlot(), request.roleId(), -1_500L);
            return InspectionResult.verified(
                    snapshot,
                    ManagedCoopCaptureClaimValidator.snapshotSha256(snapshot),
                    Integer.parseInt(CoopResidentStateSnapshotCodec.CURRENT_VERSION));
        }

        @Override
        public AdoptionResult adopt(AdoptionRequest request) {
            adoptionCalls++;
            try {
                CoopLifecycleOperationRepository.OperationRecord operation =
                        lifecycle.load(request.operationId());
                observedCommittedImportOperation = operation != null && operation.active()
                        && operation.kind()
                        == CoopLifecycleOperationRepository.OperationKind.IMPORT
                        && operation.state()
                        == CoopLifecycleOperationRepository.OperationState.SOURCE_RETIRE_REQUESTED;
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
            assertTrue(observedCommittedImportOperation,
                    "adoption must not run before the IMPORT operation commits");
            assertEquals(SOURCE, request.sourceNpcUuid());
            return AdoptionResult.adopted();
        }
    }
}
