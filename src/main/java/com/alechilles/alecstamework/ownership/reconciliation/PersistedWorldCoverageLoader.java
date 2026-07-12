package com.alechilles.alecstamework.ownership.reconciliation;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.annotation.Nonnull;

/**
 * Uses Hytale's public saved-world loader to make every persisted startup world enumerable.
 * Failed loads remain absent, so the catalog keeps owner admissions fail-closed.
 */
public final class PersistedWorldCoverageLoader {
    private final PersistentWorldDirectoryCatalog directories;

    public PersistedWorldCoverageLoader() {
        this(PersistentWorldDirectoryCatalog.filesystem());
    }

    PersistedWorldCoverageLoader(@Nonnull PersistentWorldDirectoryCatalog directories) {
        this.directories = Objects.requireNonNull(directories, "directories");
    }

    @Nonnull
    public CompletableFuture<Result> ensureLoaded(@Nonnull Universe universe) {
        Objects.requireNonNull(universe, "universe");
        return ensureLoaded(new UniverseAccess() {
            @Nonnull
            @Override
            public Path worldsRoot() {
                return universe.getWorldsPath();
            }

            @Nonnull
            @Override
            public Collection<Path> liveSavePaths() {
                List<Path> paths = new ArrayList<>();
                for (World world : universe.getWorlds().values()) {
                    if (world != null) {
                        paths.add(world.getSavePath());
                    }
                }
                return List.copyOf(paths);
            }

            @Nonnull
            @Override
            public CompletableFuture<?> load(@Nonnull String worldName) {
                return universe.loadWorld(worldName);
            }
        });
    }

    @Nonnull
    CompletableFuture<Result> ensureLoaded(@Nonnull WorldAccess access) {
        Objects.requireNonNull(access, "access");
        final List<Path> missing;
        try {
            missing = directories.snapshot(access.worldsRoot())
                    .compareToLiveWorlds(access.liveSavePaths())
                    .missingWorldDirectories();
        } catch (Throwable throwable) {
            return CompletableFuture.completedFuture(new Result(
                    0, 0, Map.of("<catalog>", rootCauseName(throwable))
            ));
        }
        CompletableFuture<MutableResult> chain = CompletableFuture.completedFuture(
                new MutableResult(missing.size())
        );
        for (Path directory : missing) {
            String worldName = directory.getFileName().toString();
            chain = chain.thenCompose(result -> loadOne(access, directory, worldName)
                    .handle((loaded, failure) -> {
                        if (failure == null && loaded) {
                            result.loaded++;
                        } else {
                            result.failures.put(
                                    worldName,
                                    failure == null
                                            ? "world-remained-unavailable"
                                            : rootCauseName(failure)
                            );
                        }
                        return result;
                    }));
        }
        return chain.thenApply(MutableResult::snapshot);
    }

    @Nonnull
    private static CompletableFuture<Boolean> loadOne(
            @Nonnull WorldAccess access,
            @Nonnull Path expectedSavePath,
            @Nonnull String worldName
    ) {
        if (containsPath(access.liveSavePaths(), expectedSavePath)) {
            return CompletableFuture.completedFuture(true);
        }
        final CompletableFuture<?> loaded;
        try {
            loaded = Objects.requireNonNull(
                    access.load(worldName), "world load future"
            );
        } catch (Throwable throwable) {
            if (containsPath(access.liveSavePaths(), expectedSavePath)) {
                return CompletableFuture.completedFuture(true);
            }
            return CompletableFuture.failedFuture(throwable);
        }
        return loaded.handle((ignored, failure) -> {
            if (containsPath(access.liveSavePaths(), expectedSavePath)) {
                return true;
            }
            if (failure != null) {
                throw new CompletionException(failure);
            }
            return false;
        });
    }

    private static boolean containsPath(
            @Nonnull Collection<Path> paths,
            @Nonnull Path expected
    ) {
        Path normalized = expected.toAbsolutePath().normalize();
        for (Path path : paths) {
            if (path != null && normalized.equals(path.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static String rootCauseName(@Nonnull Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current.getCause() != null)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    interface WorldAccess {
        @Nonnull
        Path worldsRoot();

        @Nonnull
        Collection<Path> liveSavePaths();

        @Nonnull
        CompletableFuture<?> load(@Nonnull String worldName);
    }

    private interface UniverseAccess extends WorldAccess {
    }

    public record Result(int attempted, int loaded, @Nonnull Map<String, String> failures) {
        public Result {
            if (attempted < 0 || loaded < 0 || loaded > attempted) {
                throw new IllegalArgumentException("Invalid persisted-world load counts.");
            }
            failures = Map.copyOf(Objects.requireNonNull(failures, "failures"));
        }

        public boolean complete() {
            return failures.isEmpty() && loaded == attempted;
        }
    }

    private static final class MutableResult {
        private final int attempted;
        private int loaded;
        private final Map<String, String> failures = new LinkedHashMap<>();

        private MutableResult(int attempted) {
            this.attempted = attempted;
        }

        private Result snapshot() {
            return new Result(attempted, loaded, failures);
        }
    }
}
