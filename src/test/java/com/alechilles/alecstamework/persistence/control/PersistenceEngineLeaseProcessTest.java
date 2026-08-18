package com.alechilles.alecstamework.persistence.control;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Operating-system process exclusivity test for both persistence lineages. */
class PersistenceEngineLeaseProcessTest {
    @TempDir
    Path tempDir;

    @Test
    void legacyProcessExcludesBothLineagesUntilItExits() throws Exception {
        assertForkedExclusion(PersistenceEngineLineage.LEGACY_PUBLIC);
    }

    @Test
    void replacementProcessExcludesBothLineagesUntilItExits()
            throws Exception {
        assertForkedExclusion(PersistenceEngineLineage.REPLACEMENT);
    }

    @Test
    void waitsForRecentlyReleasedProcessLock() throws Exception {
        Process child = startChild(PersistenceEngineLineage.REPLACEMENT);
        CompletableFuture<PersistenceEngineLineage> acquired = null;
        try {
            assertEquals("READY", output(child).readLine());
            AtomicReference<Thread> waiter = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            acquired = CompletableFuture.supplyAsync(() -> {
                waiter.set(Thread.currentThread());
                started.countDown();
                try (PersistenceEngineLease lease =
                             PersistenceEngineLease.acquireReplacement(
                                     tempDir
                             )) {
                    return lease.requestedLineage();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            awaitTimedWaiting(waiter.get());
            child.getOutputStream().close();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(
                    PersistenceEngineLineage.REPLACEMENT,
                    acquired.join()
            );
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
            if (acquired != null) {
                acquired.join();
            }
        }
    }

    @Test
    void reportsExternalFormerLockFile() throws Exception {
        Process child = startFormerLockChild();
        try {
            assertEquals("READY", output(child).readLine());
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> PersistenceEngineLease.acquireReplacement(tempDir)
            );
            assertEquals(
                    "persistence_engine_lock_unavailable:path=legacy;scope=external_process",
                    failure.getMessage()
            );
        } finally {
            child.getOutputStream().close();
            if (!child.waitFor(10, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private void assertForkedExclusion(
            PersistenceEngineLineage heldLineage
    ) throws Exception {
        Process child = startChild(heldLineage);
        try {
            BufferedReader output = output(child);
            assertEquals("READY", output.readLine());
            assertUnavailable(PersistenceEngineLineage.LEGACY_PUBLIC);
            assertUnavailable(PersistenceEngineLineage.REPLACEMENT);
        } finally {
            child.getOutputStream().close();
            if (!child.waitFor(10, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
        try (PersistenceEngineLease ignored =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            assertEquals(
                    PersistenceEngineLineage.REPLACEMENT,
                    ignored.requestedLineage()
            );
        }
    }

    private static BufferedReader output(Process child) {
        return new BufferedReader(new InputStreamReader(
                child.getInputStream(),
                StandardCharsets.UTF_8
        ));
    }

    private static void awaitTimedWaiting(Thread thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Persistence acquisition did not wait");
    }

    private Process startChild(PersistenceEngineLineage lineage)
            throws Exception {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        Path java = javaExecutable();
        return new ProcessBuilder(
                java.toString(),
                "-cp",
                classpath,
                PersistenceEngineLeaseChild.class.getName(),
                tempDir.toString(),
                lineage.name()
        ).redirectErrorStream(true).start();
    }

    private Process startFormerLockChild() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        Path java = javaExecutable();
        return new ProcessBuilder(
                java.toString(),
                "-cp",
                classpath,
                PersistenceEngineFormerLockChild.class.getName(),
                tempDir.toString()
        ).redirectErrorStream(true).start();
    }

    private static Path javaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"), "bin");
        Path java = javaHome.resolve("java.exe");
        return Files.isRegularFile(java) ? java : javaHome.resolve("java");
    }

    private void assertUnavailable(PersistenceEngineLineage lineage) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> {
                    if (lineage == PersistenceEngineLineage.LEGACY_PUBLIC) {
                        PersistenceEngineLease.acquireLegacy(tempDir);
                    } else {
                        PersistenceEngineLease.acquireReplacement(tempDir);
                    }
                }
        );
        assertEquals(
                "persistence_engine_lock_unavailable:path=active;scope=external_process",
                failure.getMessage()
        );
    }
}
