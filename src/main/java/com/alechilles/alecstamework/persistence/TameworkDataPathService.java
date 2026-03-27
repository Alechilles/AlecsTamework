package com.alechilles.alecstamework.persistence;

import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves and migrates Tamework runtime data storage to a universe-scoped directory.
 */
public final class TameworkDataPathService {
    private static final String UNIVERSE_DIR_NAME = "universe";
    private static final String WORLDS_DIR_NAME = "worlds";
    private static final String MODS_DIR_NAME = "mods";
    private static final String SERVER_DIR_NAME = "server";
    private static final String HYTALE_DIR_NAME = "hytale";
    private static final String TAMEWORK_DIR_NAME = "Tamework";
    private static final String DATA_DIR_NAME = "Data";

    private static final String COOP_LEDGER_FILE = "CommandLinkedNpcCoops.dat";
    private static final String COOP_MIGRATION_MARKER_FILE = "CommandLinkedNpcCoops.dat.runtime-v2.marker";
    private static final String SQLITE_DB_FILE = "tamework.sqlite";
    private static final String SQLITE_WAL_FILE = "tamework.sqlite-wal";
    private static final String SQLITE_SHM_FILE = "tamework.sqlite-shm";
    private static final String SQLITE_IMPORT_MARKER_FILE = "tamework.sqlite.legacy-dat-import-v2.marker";

    private static final String[] KNOWN_DATA_FILES = new String[] {
            "CommandLinkedNpcCaptures.dat",
            COOP_LEDGER_FILE,
            "CommandLinkedNpcDeaths.dat",
            "CommandLinkedNpcLost.dat",
            "CoopResidentSnapshots.dat",
            COOP_MIGRATION_MARKER_FILE
    };

    @Nullable
    private final HytaleLogger logger;

    public TameworkDataPathService() {
        this(null);
    }

    public TameworkDataPathService(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    /**
     * Resolves the preferred runtime data directory and migrates legacy data files when needed.
     *
     * <p>Falls back to the legacy directory if the universe path cannot be resolved or created.
     */
    @Nonnull
    public Path resolveAndMigrateDataDirectory(@Nonnull Path legacyDataDirectory) {
        Path legacyDir = legacyDataDirectory.toAbsolutePath().normalize();
        Path preferredDir = resolvePreferredDataDirectory(legacyDir);
        if (preferredDir.equals(legacyDir)) {
            ensureDirectoryExists(legacyDir);
            return legacyDir;
        }
        if (!ensureDirectoryExists(preferredDir)) {
            log(Level.WARNING,
                    "Failed to initialize universe data directory '" + preferredDir
                            + "'. Falling back to legacy data directory '" + legacyDir + "'.");
            ensureDirectoryExists(legacyDir);
            return legacyDir;
        }
        migrateLegacyDataFiles(legacyDir, preferredDir);
        Path historicalDataDir = resolveHistoricalServerAnchoredDataDirectory(legacyDir);
        if (historicalDataDir != null
                && !historicalDataDir.equals(preferredDir)
                && !historicalDataDir.equals(legacyDir)) {
            migrateLegacyDataFiles(historicalDataDir, preferredDir);
        }
        ensureCoopMigrationMarker(preferredDir);
        return preferredDir;
    }

    @Nonnull
    Path resolvePreferredDataDirectory(@Nonnull Path legacyDataDirectory) {
        Path runtimeRoot = resolveRuntimeRoot(legacyDataDirectory);
        if (runtimeRoot == null) {
            return legacyDataDirectory;
        }
        return runtimeRoot.resolve(UNIVERSE_DIR_NAME).resolve(TAMEWORK_DIR_NAME).resolve(DATA_DIR_NAME).normalize();
    }

    @Nullable
    private Path resolveRuntimeRoot(@Nonnull Path legacyDataDirectory) {
        Path rootWithUniverse = findAncestorWithChildDirectory(legacyDataDirectory, UNIVERSE_DIR_NAME);
        if (rootWithUniverse != null) {
            return rootWithUniverse;
        }

        Path modsDir = findAncestorNamed(legacyDataDirectory, MODS_DIR_NAME);
        if (modsDir != null && modsDir.getParent() != null && isLikelyRuntimeRoot(modsDir.getParent())) {
            return modsDir.getParent();
        }

        Path serverAncestor = findAncestorNamed(legacyDataDirectory, SERVER_DIR_NAME);
        if (serverAncestor != null) {
            return serverAncestor;
        }
        return null;
    }

    @Nullable
    private Path findAncestorNamed(@Nonnull Path startingPath, @Nonnull String expectedName) {
        Path cursor = startingPath;
        while (cursor != null) {
            Path fileName = cursor.getFileName();
            if (fileName != null && expectedName.equalsIgnoreCase(fileName.toString())) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    @Nullable
    private Path findAncestorWithChildDirectory(@Nonnull Path startingPath, @Nonnull String childDirectoryName) {
        Path cursor = startingPath;
        while (cursor != null) {
            if (hasChildDirectory(cursor, childDirectoryName)) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private boolean isLikelyRuntimeRoot(@Nonnull Path directory) {
        Path fileName = directory.getFileName();
        String currentName = fileName != null ? fileName.toString() : null;
        if (currentName != null
                && (SERVER_DIR_NAME.equalsIgnoreCase(currentName) || HYTALE_DIR_NAME.equalsIgnoreCase(currentName))) {
            return true;
        }
        return hasChildDirectory(directory, UNIVERSE_DIR_NAME) || hasChildDirectory(directory, WORLDS_DIR_NAME);
    }

    private boolean hasChildDirectory(@Nonnull Path parent, @Nonnull String childDirectoryName) {
        Path direct = parent.resolve(childDirectoryName);
        if (Files.isDirectory(direct)) {
            return true;
        }
        if (!Files.isDirectory(parent)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child) || child.getFileName() == null) {
                    continue;
                }
                if (childDirectoryName.equalsIgnoreCase(child.getFileName().toString())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    @Nullable
    private Path resolveHistoricalServerAnchoredDataDirectory(@Nonnull Path legacyDataDirectory) {
        Path serverAncestor = findAncestorNamed(legacyDataDirectory, SERVER_DIR_NAME);
        if (serverAncestor == null) {
            return null;
        }
        return serverAncestor.resolve(UNIVERSE_DIR_NAME).resolve(TAMEWORK_DIR_NAME).resolve(DATA_DIR_NAME).normalize();
    }

    private boolean shouldMigrateRuntimeFile(@Nonnull String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".dat")) {
            return true;
        }
        if (normalized.startsWith("tamework.sqlite")) {
            return true;
        }
        return normalized.startsWith("tamework_pre_v2_") && normalized.endsWith(".sqlite.bak");
    }

    private void migrateLegacyDataFiles(@Nonnull Path legacyDirectory, @Nonnull Path targetDirectory) {
        if (legacyDirectory.equals(targetDirectory) || !Files.exists(legacyDirectory)) {
            return;
        }

        Set<String> movedFileNames = new HashSet<>();
        int movedCount = 0;

        for (String fileName : KNOWN_DATA_FILES) {
            Path source = legacyDirectory.resolve(fileName);
            Path target = targetDirectory.resolve(fileName);
            if (moveIfNeeded(source, target)) {
                movedCount++;
                movedFileNames.add(fileName);
            }
        }
        for (String fileName : new String[] {SQLITE_DB_FILE, SQLITE_WAL_FILE, SQLITE_SHM_FILE, SQLITE_IMPORT_MARKER_FILE}) {
            Path source = legacyDirectory.resolve(fileName);
            Path target = targetDirectory.resolve(fileName);
            if (moveIfNeeded(source, target)) {
                movedCount++;
                movedFileNames.add(fileName);
            }
        }

        // Move additional runtime artifacts that may have been introduced by future persistence updates.
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(legacyDirectory)) {
            for (Path source : stream) {
                Path fileNamePath = source.getFileName();
                if (fileNamePath == null || Files.isDirectory(source)) {
                    continue;
                }
                String fileName = fileNamePath.toString();
                if (movedFileNames.contains(fileName)) {
                    continue;
                }
                if (!shouldMigrateRuntimeFile(fileName)) {
                    continue;
                }
                if (moveIfNeeded(source, targetDirectory.resolve(fileName))) {
                    movedCount++;
                }
            }
        } catch (Exception ignored) {
            // Ignore migration scan failures; known files are handled explicitly.
        }

        if (movedCount > 0) {
            log(Level.INFO,
                    "Migrated " + movedCount + " Tamework data file(s) from '"
                            + legacyDirectory + "' to '" + targetDirectory + "'.");
        }
    }

    private boolean moveIfNeeded(@Nonnull Path source, @Nonnull Path target) {
        try {
            if (!Files.exists(source) || Files.isDirectory(source)) {
                return false;
            }
            if (Files.exists(target)) {
                return false;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ex) {
            log(Level.WARNING, "Failed to migrate data file '" + source + "' to '" + target + "': " + ex.getMessage());
            return false;
        }
    }

    private void ensureCoopMigrationMarker(@Nonnull Path targetDirectory) {
        Path coopLedgerPath = targetDirectory.resolve(COOP_LEDGER_FILE);
        Path coopMarkerPath = targetDirectory.resolve(COOP_MIGRATION_MARKER_FILE);
        if (!Files.exists(coopLedgerPath) || Files.exists(coopMarkerPath)) {
            return;
        }
        try {
            Files.writeString(coopMarkerPath, "runtime-v2", StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log(Level.WARNING,
                    "Failed to write coop migration marker '" + coopMarkerPath + "': " + ex.getMessage());
        }
    }

    private boolean ensureDirectoryExists(@Nonnull Path directory) {
        try {
            Files.createDirectories(directory);
            return true;
        } catch (Exception ex) {
            log(Level.WARNING, "Failed to create directory '" + directory + "': " + ex.getMessage());
            return false;
        }
    }

    private void log(@Nonnull Level level, @Nonnull String message) {
        if (logger != null) {
            logger.at(level).log(message);
        }
    }
}
