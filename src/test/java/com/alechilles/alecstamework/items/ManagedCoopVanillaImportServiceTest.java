package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
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
        service = new ManagedCoopVanillaImportService(
                residents, lifecycle, imports, profiles, composite);
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
        CapturedNPCMetadata metadata = new CapturedNPCMetadata();
        metadata.setNpcNameKey("Mob_Chicken");
        metadata.setIconPath("Icons/Chicken.png");
        metadata.setFullItemIcon("Icons/Chicken_Full.png");
        CoopBlock coop = new CoopBlock(
                "coop_chicken",
                List.of(new CoopBlock.CoopResident(
                        metadata, null, Instant.ofEpochMilli(-500L))),
                new SimpleItemContainer((short) 5)
        );

        ManagedCoopVanillaImportService.SweepResult result = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            result = sweep(coop, -1_000L - attempt);
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
        assertTrue(composite.isTrusted());
    }

    private ManagedCoopVanillaImportService.SweepResult sweep(CoopBlock coop, long nowMs) {
        ManagedCoopVanillaImportService.SweepResult result = service.sweep(
                new ManagedCoopVanillaImportService.ImportContext(
                        authority, "coop_chicken", 4, true),
                coop,
                nowMs
        );
        assertTrue(queue.awaitIdle(5_000L));
        return result;
    }
}
