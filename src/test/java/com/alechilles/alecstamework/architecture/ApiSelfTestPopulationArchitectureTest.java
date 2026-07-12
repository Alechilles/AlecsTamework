package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents the shipped API fixture commands from bypassing population authority. */
class ApiSelfTestPopulationArchitectureTest {
    private static final Path MAIN = Paths.get(
            "src", "main", "java", "com", "alechilles", "alecstamework"
    );

    @Test
    void fixturesUseJournaledAdminForceAndPermanentRelease() throws IOException {
        String manager = read("selftest", "ApiSelfTestFixtureManager.java");
        String authority = read("selftest", "ApiSelfTestPopulationAuthority.java");

        assertFalse(manager.contains("new TameworkOwnerComponent"));
        assertFalse(manager.contains("deleteProfileTreeAsync"));
        assertFalse(authority.contains("deleteProfileTreeAsync"));
        assertTrue(manager.contains("populationAuthority.assignOwnerAsync("));
        assertTrue(manager.contains("populationAuthority.releaseLoadedAsync("));
        assertTrue(authority.contains("OwnerPopulationOperation.ADMIN_FORCE"));
        assertTrue(authority.contains("scheduler.schedulePermanentRelease("));
        assertTrue(authority.contains("upsertSnapshotAsync("));
        assertFalse(authority.contains("getNpcProfileRepository().upsertAsync("));
    }

    @Test
    void fixtureCommandsAreAsynchronousAndWorldDispatched() throws IOException {
        String manager = read("selftest", "ApiSelfTestFixtureManager.java");
        String prepare = read("commands", "TameworkApiTestPrepareCommand.java");
        String reset = read("commands", "TameworkApiTestResetCommand.java");

        assertTrue(manager.contains("CompletableFuture<FixtureOperationResult> prepareAsync("));
        assertTrue(manager.contains("CompletableFuture<FixtureOperationResult> resetAsync("));
        assertFalse(manager.contains(".join()"));
        assertFalse(manager.contains("awaitWriteQueueIdle("));
        assertTrue(prepare.contains("manager.prepareAsync("));
        assertTrue(reset.contains("manager.resetAsync("));
        assertTrue(prepare.contains("LeaseBoundWorldDispatcher.execute(world"));
        assertTrue(reset.contains("LeaseBoundWorldDispatcher.execute(world"));
    }

    @Test
    void changedFixtureClassesStayFocused() throws IOException {
        assertTrue(lineCount(read("selftest", "ApiSelfTestFixtureManager.java")) < 800);
        assertTrue(lineCount(read("selftest", "ApiSelfTestPopulationAuthority.java")) < 500);
    }

    private static int lineCount(String source) {
        return source.split("\\R", -1).length;
    }

    private static String read(String... segments) throws IOException {
        Path path = MAIN;
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
