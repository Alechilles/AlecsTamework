package com.alechilles.alecstamework.ownership.reconciliation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistedWorldCoverageLoaderTest {
    @Test
    void loadsEveryPersistedWorldMissingFromTheLiveCatalog(@TempDir Path tempDir) throws Exception {
        Path worlds = Files.createDirectories(tempDir.resolve("worlds"));
        Path alpha = Files.createDirectory(worlds.resolve("Alpha"));
        Path beta = Files.createDirectory(worlds.resolve("Beta"));
        TestAccess access = new TestAccess(worlds, List.of(alpha));
        PersistedWorldCoverageLoader loader = new PersistedWorldCoverageLoader();

        PersistedWorldCoverageLoader.Result result = loader.ensureLoaded(access).join();

        assertTrue(result.complete());
        assertEquals(1, result.attempted());
        assertEquals(1, result.loaded());
        assertEquals(List.of("Beta"), access.loadedNames);
        assertTrue(access.liveSavePaths().contains(beta));
    }

    @Test
    void failedLoadRemainsVisibleAndDoesNotPretendCoverage(@TempDir Path tempDir) throws Exception {
        Path worlds = Files.createDirectories(tempDir.resolve("worlds"));
        Files.createDirectory(worlds.resolve("Broken"));
        AtomicInteger attempts = new AtomicInteger();
        PersistedWorldCoverageLoader.WorldAccess access = new PersistedWorldCoverageLoader.WorldAccess() {
            @Override
            public Path worldsRoot() {
                return worlds;
            }

            @Override
            public Collection<Path> liveSavePaths() {
                return List.of();
            }

            @Override
            public CompletableFuture<?> load(String worldName) {
                attempts.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("broken"));
            }
        };

        PersistedWorldCoverageLoader.Result result =
                new PersistedWorldCoverageLoader().ensureLoaded(access).join();

        assertFalse(result.complete());
        assertEquals(1, attempts.get());
        assertEquals("IllegalStateException", result.failures().get("Broken"));
    }

    @Test
    void exactLiveCatalogDoesNotReloadWorlds(@TempDir Path tempDir) throws Exception {
        Path worlds = Files.createDirectories(tempDir.resolve("worlds"));
        Path alpha = Files.createDirectory(worlds.resolve("Alpha"));
        TestAccess access = new TestAccess(worlds, List.of(alpha));

        PersistedWorldCoverageLoader.Result result =
                new PersistedWorldCoverageLoader().ensureLoaded(access).join();

        assertTrue(result.complete());
        assertEquals(0, result.attempted());
        assertTrue(access.loadedNames.isEmpty());
    }

    private static final class TestAccess implements PersistedWorldCoverageLoader.WorldAccess {
        private final Path root;
        private final List<Path> live = new ArrayList<>();
        private final List<String> loadedNames = new ArrayList<>();

        private TestAccess(Path root, Collection<Path> live) {
            this.root = root;
            this.live.addAll(live);
        }

        @Override
        public Path worldsRoot() {
            return root;
        }

        @Override
        public Collection<Path> liveSavePaths() {
            return List.copyOf(live);
        }

        @Override
        public CompletableFuture<?> load(String worldName) {
            loadedNames.add(worldName);
            live.add(root.resolve(worldName));
            return CompletableFuture.completedFuture(worldName);
        }
    }
}
