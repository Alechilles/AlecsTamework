package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcCoopService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.hypixel.hytale.math.vector.Vector3d;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkPersistenceRuntimeMigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void importsLegacyDatFilesIntoSqliteAndBacksThemUp() throws Exception {
        Path capturesDat = tempDir.resolve("CommandLinkedNpcCaptures.dat");
        Path coopsDat = tempDir.resolve("CommandLinkedNpcCoops.dat");
        Path lostDat = tempDir.resolve("CommandLinkedNpcLost.dat");

        UUID captureNpc = UUID.randomUUID();
        UUID coopNpc = UUID.randomUUID();
        UUID lostNpc = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        CommandLinkedNpcCaptureService captureService = new CommandLinkedNpcCaptureService(capturesDat);
        captureService.recordCapturedSnapshot(new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                captureNpc,
                owner,
                new String[] {"tool-alpha"},
                "tamed_chicken",
                "Capture Test",
                new Vector3d(1.0, 2.0, 3.0),
                new Vector3d(4.0, 5.0, 6.0),
                System.currentTimeMillis()
        ));

        CommandLinkedNpcCoopService coopService = new CommandLinkedNpcCoopService(coopsDat);
        coopService.captureResident(
                coopNpc,
                "tamed_chicken",
                CommandLinkedNpcCoopService.CoopSlotContext.of("default", "Coop_Chicken", 10, 64, 10, 0),
                owner,
                new String[] {"tool-alpha"},
                "Coop Test",
                null
        );

        CommandLinkedNpcLostService lostService = new CommandLinkedNpcLostService(lostDat);
        lostService.recordLostFromRelocationDrop(
                lostNpc,
                owner,
                new Vector3d(7.0, 8.0, 9.0),
                new Vector3d(1.0, 1.0, 1.0),
                null,
                100L,
                200L,
                2
        );

        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            assertFalse(runtime.getCaptureRepository().loadAll().isEmpty());
            assertFalse(runtime.getCoopLedgerRepository().loadAll().isEmpty());
            assertFalse(runtime.getLostRepository().loadAll().isEmpty());
        }

        assertFalse(Files.exists(capturesDat));
        assertFalse(Files.exists(coopsDat));
        assertFalse(Files.exists(lostDat));

        Path backupRoot = tempDir.resolve("LegacyDatBackup");
        assertTrue(Files.exists(backupRoot));
        try (Stream<Path> backups = Files.list(backupRoot)) {
            assertTrue(backups.findAny().isPresent());
        }
    }
}
