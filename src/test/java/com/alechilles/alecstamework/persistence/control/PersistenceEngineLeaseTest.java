package com.alechilles.alecstamework.persistence.control;

import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Engine selection, exclusivity, failed-startup, and rollback guarantees. */
class PersistenceEngineLeaseTest {
    private static final String LEGACY_LOCK_FILENAME =
            ".tamework-persistence-engine.lock";

    @TempDir
    Path tempDir;

    @Test
    void acquiredLeaseCreatesBackupExcludedLockFile() throws Exception {
        try (PersistenceEngineLease lease =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            Path lockPath = tempDir.resolve(
                    PersistenceEngineLease.LOCK_FILENAME
            );
            assertTrue(Files.isRegularFile(lockPath));
            try (Stream<Path> paths = Files.walk(tempDir)) {
                List<Path> backupFiles = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> !path.endsWith("LOCK"))
                        .toList();
                assertFalse(backupFiles.contains(lockPath));
                for (Path backupFile : backupFiles) {
                    assertDoesNotThrow(() -> Files.readAllBytes(backupFile));
                }
            }
        }
    }

    @Test
    void replacesFormerRegularLockFileWithDirectorySentinel()
            throws Exception {
        Path legacyPath = tempDir.resolve(
                LEGACY_LOCK_FILENAME
        );
        Files.writeString(legacyPath, "former engine lock");

        try (PersistenceEngineLease ignored =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            assertTrue(Files.isDirectory(
                    legacyPath,
                    LinkOption.NOFOLLOW_LINKS
            ));
        }
    }

    @Test
    void retainsExistingLegacyDirectorySentinel() throws Exception {
        Path legacyPath = tempDir.resolve(
                LEGACY_LOCK_FILENAME
        );
        Files.createDirectory(legacyPath);

        try (PersistenceEngineLease ignored =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            assertTrue(Files.isDirectory(
                    legacyPath,
                    LinkOption.NOFOLLOW_LINKS
            ));
        }
    }

    @Test
    void rejectsLegacySymbolicLinkWithoutReplacingIt() throws Exception {
        Path target = tempDir.resolve("legacy-lock-target");
        Path legacyPath = tempDir.resolve(
                LEGACY_LOCK_FILENAME
        );
        Files.writeString(target, "target");
        try {
            Files.createSymbolicLink(legacyPath, target.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException failure) {
            Assumptions.assumeTrue(false, "symbolic links unsupported");
        }

        assertThrows(
                IllegalStateException.class,
                () -> PersistenceEngineLease.acquireReplacement(tempDir)
        );
        assertTrue(Files.isSymbolicLink(legacyPath));
    }

    @Test
    void acquisitionNeverFollowsActiveLockSymbolicLink() throws Exception {
        Path target = tempDir.resolve("active-lock-target");
        Path lockPath = tempDir.resolve(PersistenceEngineLease.LOCK_FILENAME);
        Files.writeString(target, "target");
        try {
            Files.createSymbolicLink(lockPath, target.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException failure) {
            Assumptions.assumeTrue(false, "symbolic links unsupported");
        }

        assertThrows(
                IllegalStateException.class,
                () -> PersistenceEngineLease.acquireReplacement(tempDir)
        );
        assertTrue(Files.isSymbolicLink(lockPath));
    }

    @Test
    void heldLeaseExcludesBothOldAndReplacementEngines() {
        try (PersistenceEngineLease ignored =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.LEGACY_PUBLIC,
                             () -> 10
                     )) {
            assertLockUnavailable(PersistenceEngineLineage.LEGACY_PUBLIC);
            assertLockUnavailable(PersistenceEngineLineage.REPLACEMENT);
        }
    }

    @Test
    void waitsForRecentlyReleasedSameProcessLease() {
        PersistenceEngineLease first = PersistenceEngineLease.acquire(
                tempDir,
                PersistenceEngineLineage.REPLACEMENT,
                () -> 10
        );
        CompletableFuture<Void> released = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);
                first.close();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        });

        try (PersistenceEngineLease second =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            assertEquals(
                    PersistenceEngineLineage.REPLACEMENT,
                    second.requestedLineage()
            );
        } finally {
            first.close();
            released.join();
        }
    }

    @Test
    void interruptedSameProcessHandoffDoesNotAcquireReleasedLease()
            throws Exception {
        PersistenceEngineLease first =
                PersistenceEngineLease.acquireReplacement(tempDir);
        AtomicReference<Thread> waiter = new AtomicReference<>();
        CompletableFuture<InterruptedAttempt> attempted =
                new CompletableFuture<>();
        Thread contender = new Thread(() -> {
            waiter.set(Thread.currentThread());
            try (PersistenceEngineLease ignored =
                         PersistenceEngineLease.acquireReplacement(tempDir)) {
                attempted.complete(new InterruptedAttempt(null, false));
            } catch (IllegalStateException failure) {
                attempted.complete(new InterruptedAttempt(
                        failure,
                        Thread.currentThread().isInterrupted()
                ));
            }
        }, "persistence-interrupted-handoff-test");
        contender.start();
        try {
            awaitTimedWaiting(waiter);
            synchronized (System.getProperties()) {
                first.close();
                waiter.get().interrupt();
            }
            InterruptedAttempt result = attempted.join();
            assertEquals(
                    "persistence_engine_lock_unavailable:path=active;scope=same_process",
                    result.failure().getMessage()
            );
            assertTrue(result.interrupted());
        } finally {
            first.close();
            contender.interrupt();
            contender.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void failedReplacementStartupPreservesLegacyManifestByteForByte() throws Exception {
        Path manifestPath = tempDir.resolve(PersistenceFiles.ENGINE_MANIFEST);
        try (PersistenceEngineLease legacy =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.LEGACY_PUBLIC,
                             () -> 10
                     )) {
            legacy.publishStartupComplete();
        }
        byte[] before = Files.readAllBytes(manifestPath);

        try (PersistenceEngineLease replacement =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.REPLACEMENT,
                             () -> 20
                     )) {
            assertEquals(
                    PersistenceEngineLineage.LEGACY_PUBLIC,
                    replacement.manifest().orElseThrow().lineage()
            );
        }

        assertTrue(java.util.Arrays.equals(
                before,
                Files.readAllBytes(manifestPath)
        ));
    }

    @Test
    void successfulReplacementCutoverPermanentlyRefusesLegacyEngine() {
        AtomicLong clock = new AtomicLong(100);
        try (PersistenceEngineLease replacement =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.REPLACEMENT,
                             clock::getAndIncrement
                     )) {
            assertTrue(replacement.manifest().isEmpty());
            replacement.publishStartupComplete();
            PersistenceEngineManifest active =
                    replacement.manifest().orElseThrow();
            assertEquals(
                    PersistenceEngineLineage.REPLACEMENT,
                    active.lineage()
            );
            assertTrue(active.startupComplete());
            assertFalse(active.cleanShutdown());
        }

        IllegalStateException rejected = assertThrows(
                IllegalStateException.class,
                () -> PersistenceEngineLease.acquire(
                        tempDir,
                        PersistenceEngineLineage.LEGACY_PUBLIC,
                        clock::getAndIncrement
                )
        );
        assertEquals(
                "replacement_persistence_lineage_already_selected",
                rejected.getMessage()
        );
        try (PersistenceEngineLease replacement =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.REPLACEMENT,
                             clock::getAndIncrement
                     )) {
            assertTrue(
                    replacement.manifest().orElseThrow().cleanShutdown()
            );
        }
    }

    @Test
    void malformedManifestFailsClosedWithoutLeakingTheLock() throws Exception {
        Path manifestPath = tempDir.resolve(PersistenceFiles.ENGINE_MANIFEST);
        Files.writeString(
                manifestPath,
                "{\"lineage\":\"replacement\"}",
                StandardCharsets.UTF_8
        );

        assertThrows(
                IllegalStateException.class,
                () -> PersistenceEngineLease.acquireReplacement(tempDir)
        );
        Files.delete(manifestPath);
        try (PersistenceEngineLease ignored =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            assertTrue(ignored.manifest().isEmpty());
        }
    }

    @Test
    void legacyStartupAndRepeatedClosePublishCleanState() {
        AtomicLong clock = new AtomicLong(1);
        PersistenceEngineLease legacy = PersistenceEngineLease.acquire(
                tempDir,
                PersistenceEngineLineage.LEGACY_PUBLIC,
                clock::getAndIncrement
        );
        assertFalse(legacy.manifest().orElseThrow().startupComplete());
        legacy.publishStartupComplete();
        legacy.close();
        legacy.close();
        PersistenceEngineManifest closed;
        try {
            closed = new PersistenceEngineManifestStore(tempDir)
                    .read()
                    .orElseThrow();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertTrue(closed.startupComplete());
        assertTrue(closed.cleanShutdown());

        try (PersistenceEngineLease reopened =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.LEGACY_PUBLIC,
                             clock::getAndIncrement
                     )) {
            PersistenceEngineManifest current =
                    reopened.manifest().orElseThrow();
            assertEquals(
                    PersistenceEngineLineage.LEGACY_PUBLIC,
                    current.lineage()
            );
            assertFalse(current.startupComplete());
            assertFalse(current.cleanShutdown());
        }
    }

    @Test
    void uncleanReleaseNeverPublishesAFalseCleanShutdown() {
        AtomicLong clock = new AtomicLong(1);
        PersistenceEngineLease replacement = PersistenceEngineLease.acquire(
                tempDir,
                PersistenceEngineLineage.REPLACEMENT,
                clock::getAndIncrement
        );
        replacement.publishStartupComplete();

        replacement.closeUnclean();
        replacement.close();

        PersistenceEngineManifest closed;
        try {
            closed = new PersistenceEngineManifestStore(tempDir)
                    .read()
                    .orElseThrow();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertTrue(closed.startupComplete());
        assertFalse(closed.cleanShutdown());
    }

    private void assertLockUnavailable(PersistenceEngineLineage lineage) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> PersistenceEngineLease.acquire(
                        tempDir,
                        lineage,
                        () -> 11
                )
        );
        assertEquals(
                "persistence_engine_lock_unavailable:path=active;scope=same_process",
                failure.getMessage()
        );
    }

    private static void awaitTimedWaiting(
            AtomicReference<Thread> thread
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Thread current = thread.get();
            if (current != null
                    && current.getState() == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Persistence acquisition did not wait");
    }

    private record InterruptedAttempt(
            IllegalStateException failure,
            boolean interrupted
    ) {
    }
}
