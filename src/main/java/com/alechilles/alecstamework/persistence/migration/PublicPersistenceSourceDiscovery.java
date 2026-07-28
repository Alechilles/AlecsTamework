package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Selects at most one immutable import source across ordered current, legacy,
 * and historical directories.
 *
 * <p>Discovery performs no writes. Split bundles, co-located SQLite and DAT
 * sources, orphaned SQLite sidecars, and non-regular source paths are refused
 * instead of resolved by precedence.</p>
 */
final class PublicPersistenceSourceDiscovery {

    Result discover(List<Path> candidateDirectories) {
        if (candidateDirectories == null
                || candidateDirectories.stream().anyMatch(path -> path == null)) {
            throw new IllegalArgumentException(
                    "Persistence source candidate directories are required"
            );
        }
        ArrayList<Selected> selected = new ArrayList<>();
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidateDirectories.forEach(path ->
                candidates.add(path.toAbsolutePath().normalize()));
        for (Path directory : candidates) {
            Result inspected = inspect(directory);
            if (inspected instanceof Refused) {
                return inspected;
            }
            if (inspected instanceof Selected source) {
                selected.add(source);
            }
        }
        if (selected.isEmpty()) {
            return new None();
        }
        if (selected.size() != 1) {
            return new Refused(
                    "AMBIGUOUS_PERSISTENCE_SOURCE_DIRECTORIES"
            );
        }
        return selected.getFirst();
    }

    private Result inspect(Path directory) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return new None();
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return new Refused("PERSISTENCE_SOURCE_CANDIDATE_NOT_DIRECTORY");
        }
        Path sqlite = PersistenceFiles.legacyDatabase(directory);
        boolean sqliteExists =
                Files.exists(sqlite, LinkOption.NOFOLLOW_LINKS);
        if (sqliteExists && !isRegular(sqlite)) {
            return new Refused("PERSISTENCE_SQLITE_SOURCE_NOT_REGULAR");
        }
        for (Path sidecar : sqliteSidecars(sqlite)) {
            if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)
                    && (!sqliteExists || !isRegular(sidecar))) {
                return new Refused("ORPHANED_OR_INVALID_SQLITE_SIDECAR");
            }
        }

        boolean datExists = false;
        for (String fileName : LegacyDatBundleSnapshot.FILE_NAMES) {
            Path dat = directory.resolve(fileName);
            if (!Files.exists(dat, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!isRegular(dat)) {
                return new Refused("LEGACY_DAT_SOURCE_NOT_REGULAR");
            }
            datExists = true;
        }
        if (sqliteExists && datExists) {
            return new Refused("AMBIGUOUS_SQLITE_AND_DAT_SOURCES");
        }
        if (sqliteExists) {
            return new Selected(directory, sqlite, Format.SQLITE);
        }
        if (datExists) {
            return new Selected(directory, directory, Format.LEGACY_DAT);
        }
        return new None();
    }

    private boolean isRegular(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private List<Path> sqliteSidecars(Path sqlite) {
        return List.of(
                sqlite.resolveSibling(sqlite.getFileName() + "-wal"),
                sqlite.resolveSibling(sqlite.getFileName() + "-shm")
        );
    }

    sealed interface Result permits None, Selected, Refused {
    }

    record None() implements Result {
    }

    record Selected(
            Path directory,
            Path source,
            Format format
    ) implements Result {
    }

    record Refused(String code) implements Result {
        Refused {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException(
                        "Source discovery refusal code is required"
                );
            }
        }
    }

    enum Format {
        SQLITE,
        LEGACY_DAT
    }
}
