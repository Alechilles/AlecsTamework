package com.alechilles.alecstamework.ownership.reconciliation;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Snapshots the immediate saved-world directories that Hytale considers during Universe startup.
 *
 * <p>The filesystem implementation intentionally never descends below the Universe worlds root.
 * A snapshot can therefore prove that every startup world directory has a corresponding live
 * world without treating nested chunk/config data or sibling server directories as worlds.</p>
 */
@FunctionalInterface
interface PersistentWorldDirectoryCatalog {
    @Nonnull
    Snapshot snapshot(@Nonnull Path worldsRoot) throws IOException;

    @Nonnull
    static PersistentWorldDirectoryCatalog filesystem() {
        return PersistentWorldDirectoryCatalog::readFilesystemSnapshot;
    }

    @Nonnull
    private static Snapshot readFilesystemSnapshot(@Nonnull Path worldsRoot) throws IOException {
        Path root = normalize(worldsRoot);
        if (Files.notExists(root)) {
            return new Snapshot(root, List.of());
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("Universe worlds root is not a directory: " + root);
        }
        List<Path> directories = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                BasicFileAttributes attributes = Files.readAttributes(
                        entry,
                        BasicFileAttributes.class
                );
                if (attributes.isDirectory()) {
                    directories.add(entry);
                }
            }
        }
        return new Snapshot(root, directories);
    }

    @Nonnull
    private static Path normalize(@Nonnull Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    /** A stable, direct-child-only view of the persistent world directory catalog. */
    record Snapshot(@Nonnull Path worldsRoot, @Nonnull List<Path> worldDirectories) {
        public Snapshot {
            worldsRoot = normalize(worldsRoot);
            Objects.requireNonNull(worldDirectories, "worldDirectories");
            Set<Path> unique = new HashSet<>();
            for (Path directory : worldDirectories) {
                Path normalized = normalize(directory);
                if (!worldsRoot.equals(normalized.getParent())) {
                    throw new IllegalArgumentException(
                            "Persistent world directory is not an immediate child of the worlds root: "
                                    + normalized
                    );
                }
                unique.add(normalized);
            }
            worldDirectories = unique.stream()
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        /** Finds saved startup worlds that are absent from the current live-world catalog. */
        @Nonnull
        Coverage compareToLiveWorlds(@Nonnull Collection<Path> liveWorldSavePaths) {
            Objects.requireNonNull(liveWorldSavePaths, "liveWorldSavePaths");
            Set<Path> live = new HashSet<>();
            for (Path savePath : liveWorldSavePaths) {
                live.add(normalize(savePath));
            }
            List<Path> missing = worldDirectories.stream()
                    .filter(directory -> !live.contains(directory))
                    .toList();
            return new Coverage(missing);
        }
    }

    /** Persistent world directories not represented by a live Hytale world. */
    record Coverage(@Nonnull List<Path> missingWorldDirectories) {
        public Coverage {
            missingWorldDirectories = List.copyOf(missingWorldDirectories);
        }

        boolean complete() {
            return missingWorldDirectories.isEmpty();
        }
    }
}
