package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the production authorities that make the public timed-summoning capability truthful. */
class CommandTimedSummoningRuntimeWiringTest {
    private static final Path MAIN = Path.of("src/main/java/com/alechilles/alecstamework");

    @Test
    void bootstrapRecoversThenAdvertisesConcreteProjectionAuthority() throws Exception {
        String plugin = Files.readString(MAIN.resolve("Tamework.java"));
        assertTrue(plugin.contains("new HytaleCommandTimedSummonProjectionPort("));
        assertTrue(plugin.contains("service.recover(System.currentTimeMillis())"));
        assertTrue(plugin.contains("commandTimedSummoningRecoveryReady = storedConvergence.ready()"));
        assertTrue(plugin.contains("recovery.unresolved()"));
        assertTrue(plugin.contains("activateCommandTimedSummoningRuntime("));
        assertTrue(plugin.contains("installInitialProjectionHook(runtime.initialProjectionHook())"));
        assertTrue(plugin.contains("service.onOwnerLogout("));
        assertTrue(plugin.contains("persistenceRuntime.getReadExecutor()"));
        assertTrue(plugin.contains("submit(repository::loadAllSessions)"));
        assertTrue(plugin.contains("projection.installLifecycleService(service)"));
        assertTrue(plugin.contains("installProjectionLoadSink("));
        assertTrue(plugin.contains("retryCommandTimedSummoningIfReady()"));
        assertTrue(plugin.contains("|| coopResidentStateSnapshotService == null"));
        assertTrue(plugin.contains("|| !populationGroupRecoveryReady"));
        int snapshotsReady = plugin.indexOf(
                "coopResidentStateSnapshotService = new CoopResidentStateSnapshotService()");
        int firstRetryAfterSnapshots = plugin.indexOf(
                "retryCommandTimedSummoningIfReady();", snapshotsReady);
        assertTrue(snapshotsReady >= 0 && firstRetryAfterSnapshots > snapshotsReady);
        assertTrue(occurrences(plugin, "retryCommandTimedSummoningIfReady();") >= 3);
    }

    @Test
    void projectionPersistsSnapshotBeforeExactRemovalAndUsesFrontPlacement() throws Exception {
        String projection = Files.readString(MAIN.resolve(
                "items/HytaleCommandTimedSummonProjectionPort.java"));
        int snapshot = projection.indexOf("saveProjectionSnapshotAsync(durable)");
        int removal = projection.indexOf("store.removeEntity(ref, RemoveReason.REMOVE)");
        assertTrue(snapshot >= 0 && removal > snapshot);
        assertTrue(projection.contains("CommandCompanionPlacementService"));
        assertTrue(projection.contains("CompanionPreparedSpawnService"));
        assertTrue(projection.contains("LeaseBoundWorldDispatcher"));
        assertTrue(projection.contains("restoreToHolder"));
        assertTrue(projection.contains("repository.findProjectedSession(profileId)"));
        assertTrue(projection.contains("session.rowRevision(), session.summonSessionId()"));
        assertTrue(projection.contains("service.setProjectionLoaded("));
    }

    @Test
    void schemaAndIdentityMarkerAreDurable() throws Exception {
        String schema = Files.readString(MAIN.resolve(
                "persistence/sqlite/SqliteSchemaV9Migration.java"));
        String marker = Files.readString(MAIN.resolve(
                "npc/components/TameworkProjectionIdentityComponent.java"));
        assertTrue(schema.contains("command_timed_summon_snapshots"));
        assertTrue(schema.contains("snapshot_sha256"));
        assertTrue(marker.contains("KIND_COMMAND_ROSTER"));
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        for (int cursor = 0; (cursor = text.indexOf(fragment, cursor)) >= 0;
                cursor += fragment.length()) {
            count++;
        }
        return count;
    }
}
